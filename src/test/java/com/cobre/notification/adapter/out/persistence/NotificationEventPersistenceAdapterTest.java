package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.AbstractIntegrationTest;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.PlatformEvent;
import com.cobre.notification.domain.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEventPersistenceAdapterTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final Instant LOCKED_UNTIL = NOW.plus(Duration.ofSeconds(60));
    private static final Subscription SUBSCRIPTION =
            new Subscription(null, "CLIENT001", "credit_card_payment", "https://client.example/hook", true);

    @Autowired
    NotificationEventRepository notifications;

    @Autowired
    DeliveryRepository deliveries;

    @BeforeEach
    void cleanDatabase() {
        deleteAllNotifications();
    }

    @Test
    void saveIfAbsentIgnoresDuplicateEventId() {
        NotificationEvent first = pending("EVT-1", NOW);
        NotificationEvent duplicate = pending("EVT-1", NOW);

        assertThat(notifications.saveIfAbsent(first)).isTrue();
        assertThat(notifications.saveIfAbsent(duplicate)).isFalse();

        assertThat(jdbcClient.sql("SELECT count(*) FROM notification_events").query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void claimLeasesDueRowAndRecordsInProgressAttempt() {
        NotificationEvent notification = pending("EVT-1", NOW);
        notifications.saveIfAbsent(notification);

        List<DeliveryTask> tasks = deliveries.claimDue("worker-1", 10, NOW, LOCKED_UNTIL);

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.notificationEventId()).isEqualTo(notification.id());
            assertThat(task.attemptNumber()).isEqualTo(1);
            assertThat(task.trigger()).isEqualTo(AttemptTrigger.INITIAL);
            assertThat(task.webhookUrl()).isEqualTo("https://client.example/hook");
            assertThat(task.content()).isEqualTo("content");
        });

        Map<String, Object> row = notificationRow(notification.id());
        assertThat(row.get("delivery_status")).isEqualTo("PROCESSING");
        assertThat(row.get("locked_by")).isEqualTo("worker-1");
        assertThat(toInstant(row.get("locked_until"))).isEqualTo(LOCKED_UNTIL);
        assertThat(toInstant(row.get("last_attempt_at"))).isEqualTo(NOW);
        assertThat(toInstant(row.get("updated_at"))).isEqualTo(NOW);
        assertThat(row.get("attempt_count")).isEqualTo(1);

        Map<String, Object> attempt = attemptRow(tasks.getFirst().attemptId());
        assertThat(attempt.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(attempt.get("attempt_number")).isEqualTo(1);
        assertThat(attempt.get("attempt_trigger")).isEqualTo("INITIAL");
    }

    @Test
    void claimSkipsRowsNotYetDueAndRowsAlreadyClaimed() {
        notifications.saveIfAbsent(pending("EVT-DUE", NOW));
        notifications.saveIfAbsent(pending("EVT-FUTURE", NOW.plusSeconds(30)));

        assertThat(deliveries.claimDue("worker-1", 10, NOW, LOCKED_UNTIL))
                .extracting(DeliveryTask::eventId).containsExactly("EVT-DUE");
        assertThat(deliveries.claimDue("worker-2", 10, NOW, LOCKED_UNTIL)).isEmpty();
    }

    @Test
    void claimRespectsBatchSize() {
        notifications.saveIfAbsent(pending("EVT-1", NOW.minusSeconds(2)));
        notifications.saveIfAbsent(pending("EVT-2", NOW.minusSeconds(1)));
        notifications.saveIfAbsent(pending("EVT-3", NOW));

        assertThat(deliveries.claimDue("worker-1", 2, NOW, LOCKED_UNTIL))
                .extracting(DeliveryTask::eventId).containsExactly("EVT-1", "EVT-2");
    }

    @Test
    void recordCompletedSetsDeliveredAtAndReleasesLease() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = deliveries.claimDue("worker-1", 10, NOW, LOCKED_UNTIL).getFirst();
        Instant completedAt = NOW.plusMillis(250);

        boolean recorded = deliveries.recordResult(new DeliveryCompletion(task.notificationEventId(), task.attemptId(),
                "worker-1", DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, 200, null, completedAt, completedAt,
                null, 250));

        assertThat(recorded).isTrue();
        Map<String, Object> row = notificationRow(task.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("COMPLETED");
        assertThat(toInstant(row.get("delivered_at"))).isEqualTo(completedAt);
        assertThat(toInstant(row.get("updated_at"))).isEqualTo(completedAt);
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_until")).isNull();
        assertThat(row.get("last_http_status")).isEqualTo(200);

        Map<String, Object> attempt = attemptRow(task.attemptId());
        assertThat(attempt.get("status")).isEqualTo("SUCCESS");
        assertThat(attempt.get("http_status")).isEqualTo(200);
        assertThat(attempt.get("duration_ms")).isEqualTo(250L);
        assertThat(toInstant(attempt.get("completed_at"))).isEqualTo(completedAt);
    }

    @Test
    void recordFailedStoresSanitizedError() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = deliveries.claimDue("worker-1", 10, NOW, LOCKED_UNTIL).getFirst();

        deliveries.recordResult(new DeliveryCompletion(task.notificationEventId(), task.attemptId(), "worker-1",
                DeliveryStatus.FAILED, AttemptStatus.PERMANENT_FAILURE, 500, DeliveryError.httpStatus(500),
                NOW.plusSeconds(1), null, null, 1000));

        Map<String, Object> row = notificationRow(task.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("http_status: HTTP 500");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(row.get("locked_by")).isNull();

        Map<String, Object> attempt = attemptRow(task.attemptId());
        assertThat(attempt.get("status")).isEqualTo("PERMANENT_FAILURE");
        assertThat(attempt.get("error_code")).isEqualTo("http_status");
        assertThat(attempt.get("error_message")).isEqualTo("HTTP 500");
    }

    @Test
    void resultFromWorkerWithoutLeaseIsRejected() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = deliveries.claimDue("worker-1", 10, NOW, LOCKED_UNTIL).getFirst();

        boolean recorded = deliveries.recordResult(new DeliveryCompletion(task.notificationEventId(), task.attemptId(),
                "worker-2", DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, 200, null, NOW, NOW, null, 0));

        assertThat(recorded).isFalse();
        assertThat(notificationRow(task.notificationEventId()).get("delivery_status")).isEqualTo("PROCESSING");
        assertThat(attemptRow(task.attemptId()).get("status")).isEqualTo("IN_PROGRESS");
    }

    private static NotificationEvent pending(String eventId, Instant nextAttemptAt) {
        PlatformEvent event = new PlatformEvent(eventId, "credit_card_payment", "CLIENT001", NOW, "content", 1);
        NotificationEvent pending = NotificationEvent.pendingFrom(UUID.randomUUID(), event, SUBSCRIPTION, NOW);
        return new NotificationEvent(pending.id(), pending.eventId(), pending.subscriptionId(), pending.clientId(),
                pending.eventType(), pending.content(), pending.eventCreatedAt(), pending.webhookUrl(),
                pending.status(), 0, 0, 0, nextAttemptAt, null, null, null, null, pending.origin(),
                pending.createdAt(), pending.updatedAt());
    }

    private Map<String, Object> notificationRow(UUID id) {
        return jdbcClient.sql("SELECT * FROM notification_events WHERE id = :id").param("id", id).query().singleRow();
    }

    private Map<String, Object> attemptRow(UUID id) {
        return jdbcClient.sql("SELECT * FROM delivery_attempts WHERE id = :id").param("id", id).query().singleRow();
    }

    private static Instant toInstant(Object timestamp) {
        return switch (timestamp) {
            case java.sql.Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            default -> throw new IllegalArgumentException("Unexpected timestamp type: " + timestamp.getClass());
        };
    }
}
