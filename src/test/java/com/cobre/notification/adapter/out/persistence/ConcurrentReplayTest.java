package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.AbstractIntegrationTest;
import com.cobre.notification.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.NotReplayableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentReplayTest extends AbstractIntegrationTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @Autowired
    ReplayNotificationEventUseCase replay;

    @BeforeEach
    void seedFailed() {
        deleteAllNotifications();
        jdbcClient.sql("DELETE FROM subscriptions WHERE client_id = 'CLIENT002' AND event_type = 'credit_transfer'")
                .update();
        jdbcClient.sql("""
                        INSERT INTO subscriptions (id, client_id, event_type, webhook_url, active)
                        VALUES (:id, 'CLIENT002', 'credit_transfer', 'https://hooks.example.com/current', TRUE)
                        """)
                .param("id", UUID.randomUUID())
                .update();
        Timestamp createdAt = Timestamp.from(NOW);
        jdbcClient.sql("""
                        INSERT INTO notification_events (
                            id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                            delivery_status, attempt_count, cycle_attempt_count, last_attempt_at, last_error, origin)
                        VALUES (:id, 'EVT-CONCURRENT-REPLAY', 'CLIENT002', 'credit_transfer', 'secret', :createdAt,
                                'https://hooks.example.com/old', 'FAILED', 2, 2, :createdAt, 'http_status: HTTP 500',
                                'KAFKA')
                        """)
                .param("id", ID)
                .param("createdAt", createdAt)
                .update();
    }

    @Test
    void concurrentReplayAcceptsExactlyOnce() throws Exception {
        int workers = 8;
        var accepted = new AtomicInteger();
        var conflicts = new AtomicInteger();
        var barrier = new CyclicBarrier(workers);
        Requester.Operator operator = new Requester.Operator("ops");

        try (ExecutorService pool = Executors.newFixedThreadPool(workers)) {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < workers; i++) {
                tasks.add(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    try {
                        replay.replay(operator, ID, "corr-concurrent");
                        accepted.incrementAndGet();
                    } catch (NotReplayableException ignored) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = pool.invokeAll(tasks);
            for (Future<Void> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        }

        assertThat(accepted.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(workers - 1);
        assertThat(jdbcClient.sql("SELECT replay_count, cycle_attempt_count, delivery_status FROM notification_events WHERE id = :id")
                .param("id", ID)
                .query()
                .singleRow()).satisfies(row -> {
            assertThat(row.get("replay_count")).isEqualTo(1);
            assertThat(row.get("cycle_attempt_count")).isEqualTo(0);
            assertThat(row.get("delivery_status")).isEqualTo("PENDING");
        });
    }
}
