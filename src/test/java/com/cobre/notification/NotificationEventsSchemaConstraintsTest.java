package com.cobre.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventsSchemaConstraintsTest extends AbstractIntegrationTest {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-09-23T12:00:00Z"));

    @Autowired
    JdbcTemplate jdbcTemplate;

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM notification_events");
    }

    @Test
    void pendingAndRetryingRequireNextAttemptAt() {
        assertViolates("PENDING", null, null, null, null, "ck_notification_events_due_scheduled");
        assertViolates("RETRYING", null, null, null, null, "ck_notification_events_due_scheduled");
        assertThatCode(() -> insert("PENDING", NOW, null, null, null)).doesNotThrowAnyException();
    }

    @Test
    void processingRequiresLease() {
        assertViolates("PROCESSING", null, null, null, null, "ck_notification_events_processing_lease");
        assertViolates("PROCESSING", null, "worker-1", null, null, "ck_notification_events_processing_lease");
        assertViolates("PROCESSING", null, null, NOW, null, "ck_notification_events_processing_lease");
        assertThatCode(() -> insert("PROCESSING", null, "worker-1", NOW, null)).doesNotThrowAnyException();
    }

    @Test
    void completedRequiresDeliveredAt() {
        assertViolates("COMPLETED", null, null, null, null, "ck_notification_events_completed_delivered");
        assertThatCode(() -> insert("COMPLETED", null, null, null, NOW)).doesNotThrowAnyException();
    }

    @Test
    void failedHasNoSchedulingRequirements() {
        assertThatCode(() -> insert("FAILED", null, null, null, null)).doesNotThrowAnyException();
    }

    private void assertViolates(String status, Timestamp nextAttemptAt, String lockedBy, Timestamp lockedUntil,
                                Timestamp deliveredAt, String constraint) {
        assertThatThrownBy(() -> insert(status, nextAttemptAt, lockedBy, lockedUntil, deliveredAt))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private void insert(String status, Timestamp nextAttemptAt, String lockedBy, Timestamp lockedUntil,
                        Timestamp deliveredAt) {
        jdbcTemplate.update("""
                        INSERT INTO notification_events
                            (id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                             delivery_status, next_attempt_at, locked_by, locked_until, delivered_at, origin)
                        VALUES (?, ?, 'CLIENT001', 'credit_card_payment', 'test content', ?,
                                'https://example.com/webhook', ?, ?, ?, ?, ?, 'KAFKA')
                        """,
                UUID.randomUUID(), "EVT-" + UUID.randomUUID(), NOW,
                status, nextAttemptAt, lockedBy, lockedUntil, deliveredAt);
    }

}
