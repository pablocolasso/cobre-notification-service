package com.cobre.notification;

import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.application.service.DeliverNotificationService;
import com.cobre.notification.application.service.DeliveryAttemptProcessor;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.PlatformEvent;
import com.cobre.notification.domain.model.Subscription;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import com.cobre.notification.support.RecordingWebhookServer;
import com.cobre.notification.support.RecordingWebhookServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two complete delivery workers (claim, virtual-thread executor, webhook, fenced result) competing for the same
 * rows, as two instances would.
 */
class ConcurrentWorkersTest extends AbstractIntegrationTest {

    private static final int NOTIFICATIONS = 40;
    private static final int MAX_CONCURRENCY = 4;

    private static RecordingWebhookServer webhookServer;

    @Autowired
    NotificationEventRepository notifications;

    @Autowired
    DeliveryRepository deliveries;

    @Autowired
    WebhookClient webhookClient;

    @Autowired
    DeliveryLifecycle lifecycle;

    @BeforeAll
    static void startWebhookServer() {
        webhookServer = RecordingWebhookServer.start();
    }

    @AfterAll
    static void stopWebhookServer() {
        webhookServer.close();
    }

    @BeforeEach
    void cleanDatabase() {
        deleteAllNotifications();
    }

    @Test
    void twoWorkersDeliverEachNotificationExactlyOnce() throws Exception {
        String path = "/concurrent-" + UUID.randomUUID();
        String url = webhookServer.script(path, 200);
        insertDue(url);

        var claimsByWorker = new ConcurrentHashMap<String, AtomicInteger>();
        var done = new AtomicBoolean();
        var barrier = new CyclicBarrier(2);

        try (ExecutorService deliveryThreads = Executors.newVirtualThreadPerTaskExecutor();
             ExecutorService tickers = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> ticking = List.of("worker-a", "worker-b").stream()
                    .map(workerId -> {
                        DeliverNotificationService worker = worker(workerId, deliveryThreads);
                        return tickers.submit(() -> {
                            while (!done.get()) {
                                barrier.await(10, TimeUnit.SECONDS);
                                int claimed = worker.deliverDueNotifications();
                                claimsByWorker.computeIfAbsent(workerId, id -> new AtomicInteger()).addAndGet(claimed);
                            }
                            return null;
                        });
                    })
                    .toList();

            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> assertThat(jdbcClient.sql(
                            "SELECT count(*) FROM notification_events WHERE delivery_status = 'COMPLETED'")
                    .query(Long.class).single()).isEqualTo(NOTIFICATIONS));
            done.set(true);
            barrier.reset();
            for (Future<Object> future : ticking) {
                try {
                    future.get(10, TimeUnit.SECONDS);
                } catch (ExecutionException expected) {
                    // resetting the barrier breaks the ticker still waiting on it
                }
            }
        }

        List<ReceivedRequest> received = webhookServer.receivedOn(path);
        assertThat(received).hasSize(NOTIFICATIONS);
        assertThat(received).extracting(request -> request.headers().getFirst("Idempotency-Key"))
                .doesNotHaveDuplicates();
        assertThat(claimsByWorker.values()).allSatisfy(count -> assertThat(count.get()).isPositive());
        assertThat(claimsByWorker.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(NOTIFICATIONS);

        List<Map<String, Object>> rows = jdbcClient.sql("""
                        SELECT n.attempt_count, count(a.id) AS attempts
                        FROM notification_events n JOIN delivery_attempts a ON a.notification_event_id = n.id
                        GROUP BY n.id, n.attempt_count
                        """).query().listOfRows();
        assertThat(rows).hasSize(NOTIFICATIONS).allSatisfy(row -> {
            assertThat(row.get("attempt_count")).isEqualTo(1);
            assertThat(row.get("attempts")).isEqualTo(1L);
        });
    }

    private DeliverNotificationService worker(String workerId, ExecutorService deliveryThreads) {
        Clock clock = Clock.systemUTC();
        var processor = new DeliveryAttemptProcessor(deliveries, webhookClient, lifecycle, clock, System::nanoTime,
                workerId, com.cobre.notification.application.port.out.NoOpNotificationMetrics.INSTANCE);
        return new DeliverNotificationService(deliveries, processor, deliveryThreads, MAX_CONCURRENCY,
                UUID::randomUUID, clock, workerId, Duration.ofSeconds(60));
    }

    private void insertDue(String webhookUrl) {
        var subscription = new Subscription(null, "CLIENT001", "credit_card_payment", webhookUrl, true);
        var now = Clock.systemUTC().instant().minusSeconds(1);
        for (int i = 0; i < NOTIFICATIONS; i++) {
            var event = new PlatformEvent("EVT-" + UUID.randomUUID(), "credit_card_payment", "CLIENT001", now,
                    "content", 1);
            notifications.saveIfAbsent(NotificationEvent.pendingFrom(UUID.randomUUID(), event, subscription, now));
        }
    }
}
