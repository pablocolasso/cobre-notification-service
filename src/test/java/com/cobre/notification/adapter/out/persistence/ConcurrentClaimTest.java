package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.AbstractIntegrationTest;
import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.PlatformEvent;
import com.cobre.notification.domain.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real overlapping claim transactions against PostgreSQL, one connection per thread.
 */
class ConcurrentClaimTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Subscription SUBSCRIPTION =
            new Subscription(null, "CLIENT001", "credit_card_payment", "https://client.example/hook", true);

    @Autowired
    NotificationEventRepository notifications;

    @Autowired
    DeliveryRepository deliveries;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void cleanDatabase() {
        deleteAllNotifications();
    }

    @Test
    void concurrentClaimsPartitionTheDueRowsWithoutOverlap() throws Exception {
        int rows = 120;
        int workers = 4;
        int batchSize = 7;
        Set<UUID> all = insertDue(rows);
        var claimedBy = new ConcurrentHashMap<String, List<UUID>>();
        var concurrentRounds = new ConcurrentHashMap<Integer, Set<String>>();
        var phaser = new Phaser(workers);

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                String workerId = "worker-" + w;
                futures.add(pool.submit(() -> {
                    List<UUID> mine = claimedBy.computeIfAbsent(workerId, id -> new ArrayList<>());
                    while (true) {
                        int round = phaser.arriveAndAwaitAdvance();
                        List<DeliveryTask> tasks = claim(workerId, batchSize);
                        if (tasks.isEmpty()) {
                            phaser.arriveAndDeregister();
                            return;
                        }
                        concurrentRounds.computeIfAbsent(round, r -> ConcurrentHashMap.newKeySet()).add(workerId);
                        tasks.forEach(task -> mine.add(task.notificationEventId()));
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        }

        List<UUID> claimed = claimedBy.values().stream().flatMap(List::stream).toList();
        assertThat(claimed).hasSize(rows).doesNotHaveDuplicates();
        assertThat(new HashSet<>(claimed)).isEqualTo(all);
        assertThat(claimedBy.values()).allSatisfy(mine -> assertThat(mine).isNotEmpty());
        assertThat(concurrentRounds.values()).anySatisfy(round -> assertThat(round).hasSize(workers));

        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM notification_events
                        WHERE delivery_status = 'PROCESSING' AND attempt_count = 1
                        """).query(Long.class).single()).isEqualTo(rows);
        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM (
                            SELECT notification_event_id FROM delivery_attempts
                            GROUP BY notification_event_id HAVING count(*) = 1) single_attempt
                        """).query(Long.class).single()).isEqualTo(rows);
        assertThat(jdbcClient.sql("SELECT count(*) FROM delivery_attempts").query(Long.class).single())
                .isEqualTo(rows);
    }

    @Test
    void claimSkipsRowsLockedByAnOpenTransactionWithoutWaiting() throws Exception {
        insertDue(5);
        List<String> locked = List.of("EVT-0", "EVT-1", "EVT-2");
        var lockHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> holder = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("SELECT id FROM notification_events WHERE event_id IN (:eventIds) FOR UPDATE")
                        .param("eventIds", locked)
                        .query()
                        .listOfRows();
                lockHeld.countDown();
                await(release);
            }));
            assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

            long started = System.nanoTime();
            List<DeliveryTask> tasks = CompletableFuture.supplyAsync(() -> claim("worker-1", 10), pool)
                    .get(5, TimeUnit.SECONDS);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(tasks).extracting(DeliveryTask::eventId).containsExactlyInAnyOrder("EVT-3", "EVT-4");
            assertThat(elapsedMs).isLessThan(2_000);

            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
        }

        assertThat(jdbcClient.sql("""
                        SELECT count(*) FROM notification_events
                        WHERE event_id IN (:eventIds) AND delivery_status = 'PENDING'
                        """).param("eventIds", locked).query(Long.class).single()).isEqualTo(3);
        assertThat(claim("worker-2", 10)).extracting(DeliveryTask::eventId)
                .containsExactlyInAnyOrderElementsOf(locked);
    }

    private List<DeliveryTask> claim(String workerId, int limit) {
        List<UUID> attemptIds = IntStream.range(0, limit).mapToObj(i -> UUID.randomUUID()).toList();
        return deliveries.claimDue(new ClaimRequest(workerId, attemptIds, NOW, NOW.plus(Duration.ofSeconds(60))));
    }

    private Set<UUID> insertDue(int count) {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            PlatformEvent event = new PlatformEvent("EVT-" + i, "credit_card_payment", "CLIENT001", NOW, "content", 1);
            NotificationEvent notification =
                    NotificationEvent.pendingFrom(UUID.randomUUID(), event, SUBSCRIPTION, NOW.minusMillis(count - i));
            notifications.saveIfAbsent(notification);
            ids.add(notification.id());
        }
        return ids;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Lock holder was never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
