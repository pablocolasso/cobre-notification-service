package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.AbstractIntegrationTest;
import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.application.port.out.LeaseRecovery;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryDecision;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationEventPersistenceAdapterTest extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(60);
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
    void claimLeasesDueRowAndRecordsInProgressAttemptWithTheGivenId() {
        NotificationEvent notification = pending("EVT-1", NOW);
        notifications.saveIfAbsent(notification);
        UUID attemptId = UUID.randomUUID();

        List<DeliveryTask> tasks = deliveries.claimDue(new ClaimRequest("worker-1", List.of(attemptId), NOW,
                NOW.plus(LEASE)));

        assertThat(tasks).singleElement().satisfies(task -> {
            assertThat(task.notificationEventId()).isEqualTo(notification.id());
            assertThat(task.attemptId()).isEqualTo(attemptId);
            assertThat(task.attemptNumber()).isEqualTo(1);
            assertThat(task.cycleAttemptNumber()).isEqualTo(1);
            assertThat(task.trigger()).isEqualTo(AttemptTrigger.INITIAL);
            assertThat(task.webhookUrl()).isEqualTo("https://client.example/hook");
            assertThat(task.content()).isEqualTo("content");
        });

        Map<String, Object> row = notificationRow(notification.id());
        assertThat(row.get("delivery_status")).isEqualTo("PROCESSING");
        assertThat(row.get("locked_by")).isEqualTo("worker-1");
        assertThat(toInstant(row.get("locked_until"))).isEqualTo(NOW.plus(LEASE));
        assertThat(toInstant(row.get("last_attempt_at"))).isEqualTo(NOW);
        assertThat(toInstant(row.get("updated_at"))).isEqualTo(NOW);
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("attempt_count")).isEqualTo(1);

        Map<String, Object> attempt = attemptRow(attemptId);
        assertThat(attempt.get("status")).isEqualTo("IN_PROGRESS");
        assertThat(attempt.get("attempt_number")).isEqualTo(1);
        assertThat(attempt.get("attempt_trigger")).isEqualTo("INITIAL");
    }

    @Test
    void claimSkipsRowsNotYetDueAndRowsAlreadyClaimed() {
        notifications.saveIfAbsent(pending("EVT-DUE", NOW));
        notifications.saveIfAbsent(pending("EVT-FUTURE", NOW.plusSeconds(30)));

        assertThat(claim("worker-1", 10, NOW)).extracting(DeliveryTask::eventId).containsExactly("EVT-DUE");
        assertThat(claim("worker-2", 10, NOW)).isEmpty();
    }

    @Test
    void claimRespectsTheLimitAndDueOrder() {
        notifications.saveIfAbsent(pending("EVT-1", NOW.minusSeconds(2)));
        notifications.saveIfAbsent(pending("EVT-2", NOW.minusSeconds(1)));
        notifications.saveIfAbsent(pending("EVT-3", NOW));

        assertThat(claim("worker-1", 2, NOW)).extracting(DeliveryTask::eventId)
                .containsExactlyInAnyOrder("EVT-1", "EVT-2");
    }

    @Test
    void recordCompletedSetsDeliveredAtAndReleasesLease() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();
        Instant completedAt = NOW.plusMillis(250);

        boolean recorded = deliveries.recordResult(completion(task, "worker-1", new DeliveryDecision(
                DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, null, null, completedAt, null), 200, completedAt));

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
    void recordRetryingSchedulesTheNextAttemptAndTheRowIsClaimedAgainAsRetry() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask first = claim("worker-1", 10, NOW).getFirst();
        Instant retryAt = NOW.plusSeconds(5);

        deliveries.recordResult(completion(first, "worker-1", new DeliveryDecision(DeliveryStatus.RETRYING,
                AttemptStatus.RETRYABLE_FAILURE, DeliveryError.httpStatus(503), DeliveryError.httpStatus(503), null,
                retryAt), 503, NOW.plusSeconds(1)));

        Map<String, Object> row = notificationRow(first.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("RETRYING");
        assertThat(toInstant(row.get("next_attempt_at"))).isEqualTo(retryAt);
        assertThat(row.get("last_error")).isEqualTo("http_status: HTTP 503");
        assertThat(claim("worker-1", 10, retryAt.minusMillis(1))).isEmpty();

        DeliveryTask second = claim("worker-1", 10, retryAt).getFirst();
        assertThat(second.attemptNumber()).isEqualTo(2);
        assertThat(second.cycleAttemptNumber()).isEqualTo(2);
        assertThat(second.trigger()).isEqualTo(AttemptTrigger.RETRY);
        assertThat(attemptRow(second.attemptId()).get("attempt_trigger")).isEqualTo("RETRY");
    }

    @Test
    void recordFailedStoresSanitizedErrors() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();
        DeliveryError attemptError = DeliveryError.httpStatus(500);

        deliveries.recordResult(completion(task, "worker-1", new DeliveryDecision(DeliveryStatus.FAILED,
                AttemptStatus.RETRYABLE_FAILURE, attemptError, attemptError.retriesExhausted(5), null, null), 500,
                NOW.plusSeconds(1)));

        Map<String, Object> row = notificationRow(task.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("FAILED");
        assertThat(row.get("last_error")).isEqualTo("http_status: HTTP 500; retries exhausted after 5 attempts");
        assertThat(row.get("delivered_at")).isNull();
        assertThat(row.get("next_attempt_at")).isNull();
        assertThat(row.get("locked_by")).isNull();

        Map<String, Object> attempt = attemptRow(task.attemptId());
        assertThat(attempt.get("status")).isEqualTo("RETRYABLE_FAILURE");
        assertThat(attempt.get("error_code")).isEqualTo("http_status");
        assertThat(attempt.get("error_message")).isEqualTo("HTTP 500");
    }

    @Test
    void resultFromWorkerWithoutLeaseIsRejected() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();

        boolean recorded = deliveries.recordResult(completion(task, "worker-2", completed(NOW), 200, NOW));

        assertThat(recorded).isFalse();
        assertThat(notificationRow(task.notificationEventId()).get("delivery_status")).isEqualTo("PROCESSING");
        assertThat(attemptRow(task.attemptId()).get("status")).isEqualTo("IN_PROGRESS");
    }

    @Test
    void onlyLeasesPastTheirDeadlineAreReportedAsExpired() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();

        assertThat(deliveries.findExpiredLeases(NOW.plus(LEASE), 10)).isEmpty();
        assertThat(deliveries.findExpiredLeases(NOW.plus(LEASE).plusMillis(1), 10))
                .containsExactly(new ExpiredLease(task.notificationEventId(), "worker-1", 1, 1));
    }

    @Test
    void expiredLeaseIsRecoveredToRetryingAndTheAttemptAbandoned() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();
        Instant later = NOW.plus(LEASE).plusSeconds(1);
        ExpiredLease lease = deliveries.findExpiredLeases(later, 10).getFirst();

        boolean recovered = deliveries.recoverLease(new LeaseRecovery(lease, abandoned(later.plusSeconds(5)), later));

        assertThat(recovered).isTrue();
        Map<String, Object> row = notificationRow(task.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("RETRYING");
        assertThat(toInstant(row.get("next_attempt_at"))).isEqualTo(later.plusSeconds(5));
        assertThat(row.get("locked_by")).isNull();
        assertThat(row.get("locked_until")).isNull();
        assertThat(toInstant(row.get("updated_at"))).isEqualTo(later);
        assertThat((String) row.get("last_error")).startsWith("lease_expired: ");

        Map<String, Object> attempt = attemptRow(task.attemptId());
        assertThat(attempt.get("status")).isEqualTo("ABANDONED");
        assertThat(attempt.get("error_code")).isEqualTo("lease_expired");
        assertThat(toInstant(attempt.get("completed_at"))).isEqualTo(later);
    }

    @Test
    void aLeaseIsRecoveredOnlyOnce() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        claim("worker-1", 10, NOW);
        Instant later = NOW.plus(LEASE).plusSeconds(1);
        ExpiredLease lease = deliveries.findExpiredLeases(later, 10).getFirst();

        assertThat(deliveries.recoverLease(new LeaseRecovery(lease, abandoned(later), later))).isTrue();
        assertThat(deliveries.recoverLease(new LeaseRecovery(lease, abandoned(later), later))).isFalse();
    }

    @Test
    void leaseThatIsNotExpiredYetIsNotRecovered() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask task = claim("worker-1", 10, NOW).getFirst();
        var lease = new ExpiredLease(task.notificationEventId(), "worker-1", 1, 1);

        assertThat(deliveries.recoverLease(new LeaseRecovery(lease, abandoned(NOW), NOW.plusSeconds(30)))).isFalse();
        assertThat(notificationRow(task.notificationEventId()).get("delivery_status")).isEqualTo("PROCESSING");
    }

    @Test
    void lateResultFromAStaleLeaseDoesNotOverwriteTheRetryEvenFromTheSameWorker() {
        notifications.saveIfAbsent(pending("EVT-1", NOW));
        DeliveryTask stale = claim("worker-1", 10, NOW).getFirst();
        Instant expiredAt = NOW.plus(LEASE).plusSeconds(1);
        ExpiredLease lease = deliveries.findExpiredLeases(expiredAt, 10).getFirst();
        deliveries.recoverLease(new LeaseRecovery(lease, abandoned(expiredAt), expiredAt));
        DeliveryTask retry = claim("worker-1", 10, expiredAt).getFirst();
        assertThat(retry.attemptNumber()).isEqualTo(2);

        boolean staleRecorded = deliveries.recordResult(
                completion(stale, "worker-1", completed(expiredAt), 200, expiredAt));

        assertThat(staleRecorded).isFalse();
        Map<String, Object> row = notificationRow(retry.notificationEventId());
        assertThat(row.get("delivery_status")).isEqualTo("PROCESSING");
        assertThat(row.get("attempt_count")).isEqualTo(2);
        assertThat(row.get("delivered_at")).isNull();
        assertThat(attemptRow(stale.attemptId()).get("status")).isEqualTo("ABANDONED");
        assertThat(attemptRow(retry.attemptId()).get("status")).isEqualTo("IN_PROGRESS");

        Instant done = expiredAt.plusSeconds(1);
        assertThat(deliveries.recordResult(completion(retry, "worker-1", completed(done), 200, done))).isTrue();
        assertThat(notificationRow(retry.notificationEventId()).get("delivery_status")).isEqualTo("COMPLETED");
    }

    private List<DeliveryTask> claim(String workerId, int limit, Instant now) {
        List<UUID> attemptIds = IntStream.range(0, limit).mapToObj(i -> UUID.randomUUID()).toList();
        return deliveries.claimDue(new ClaimRequest(workerId, attemptIds, now, now.plus(LEASE)));
    }

    private static DeliveryCompletion completion(DeliveryTask task, String workerId, DeliveryDecision decision,
                                                 Integer httpStatus, Instant completedAt) {
        return new DeliveryCompletion(task.notificationEventId(), task.attemptId(), task.attemptNumber(), workerId,
                decision, httpStatus, completedAt, Duration.between(task.claimedAt(), completedAt).toMillis());
    }

    private static DeliveryDecision completed(Instant deliveredAt) {
        return new DeliveryDecision(DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, null, null, deliveredAt, null);
    }

    private static DeliveryDecision abandoned(Instant nextAttemptAt) {
        return new DeliveryDecision(DeliveryStatus.RETRYING, AttemptStatus.ABANDONED, DeliveryError.leaseExpired(),
                DeliveryError.leaseExpired(), null, nextAttemptAt);
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
