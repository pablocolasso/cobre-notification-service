package com.cobre.notification.adapter.out.metrics;

import com.cobre.notification.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BacklogMetricsIntegrationTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-24T18:00:00Z");

    @Autowired
    BacklogMetricsBinder binder;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void clean() {
        deleteAllNotifications();
    }

    @Test
    void gaugesCountRowsByStatusAndOldestPendingAge() {
        insert("PENDING", NOW.minusSeconds(90), NOW.minusSeconds(90));
        insert("PENDING", NOW.minusSeconds(30), NOW.minusSeconds(30));
        insert("RETRYING", NOW.minusSeconds(20), NOW.minusSeconds(10));
        insertProcessing();
        insertCompleted();

        binder.refresh();

        assertThat(meterRegistry.get(BacklogMetricsBinder.BACKLOG).tag("status", "pending").gauge().value())
                .isEqualTo(2);
        assertThat(meterRegistry.get(BacklogMetricsBinder.BACKLOG).tag("status", "retrying").gauge().value())
                .isEqualTo(1);
        assertThat(meterRegistry.get(BacklogMetricsBinder.BACKLOG).tag("status", "processing").gauge().value())
                .isEqualTo(1);
        assertThat(meterRegistry.get(BacklogMetricsBinder.OLDEST_AGE).gauge().value()).isGreaterThanOrEqualTo(90);
    }

    private void insert(String status, Instant createdAt, Instant nextAttemptAt) {
        jdbcClient.sql("""
                        INSERT INTO notification_events
                            (id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                             delivery_status, attempt_count, cycle_attempt_count, replay_count, next_attempt_at,
                             origin, created_at, updated_at)
                        VALUES (:id, :eventId, 'CLIENT001', 'credit_card_payment', 'secret-content', :createdAt,
                                'https://hooks.example.com', :status, 0, 0, 0, :nextAttemptAt, 'KAFKA', :createdAt,
                                :createdAt)
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", "EVT-" + UUID.randomUUID())
                .param("status", status)
                .param("createdAt", Timestamp.from(createdAt))
                .param("nextAttemptAt", Timestamp.from(nextAttemptAt))
                .update();
    }

    private void insertProcessing() {
        Instant now = NOW;
        jdbcClient.sql("""
                        INSERT INTO notification_events
                            (id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                             delivery_status, attempt_count, cycle_attempt_count, replay_count, locked_by,
                             locked_until, origin, created_at, updated_at)
                        VALUES (:id, :eventId, 'CLIENT001', 'credit_card_payment', 'secret-content', :now,
                                'https://hooks.example.com', 'PROCESSING', 1, 1, 0, 'worker-1', :lockedUntil,
                                'KAFKA', :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", "EVT-" + UUID.randomUUID())
                .param("now", Timestamp.from(now))
                .param("lockedUntil", Timestamp.from(now.plusSeconds(60)))
                .update();
    }

    private void insertCompleted() {
        Instant now = NOW;
        jdbcClient.sql("""
                        INSERT INTO notification_events
                            (id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                             delivery_status, attempt_count, cycle_attempt_count, replay_count, delivered_at,
                             origin, created_at, updated_at)
                        VALUES (:id, :eventId, 'CLIENT001', 'credit_card_payment', 'secret-content', :now,
                                'https://hooks.example.com', 'COMPLETED', 1, 1, 0, :now, 'KAFKA', :now, :now)
                        """)
                .param("id", UUID.randomUUID())
                .param("eventId", "EVT-" + UUID.randomUUID())
                .param("now", Timestamp.from(now))
                .update();
    }
}
