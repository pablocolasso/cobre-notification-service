package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.application.port.out.LeaseRecovery;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryDecision;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.model.NotificationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.cobre.notification.adapter.out.persistence.PersistenceTime.toInstant;
import static com.cobre.notification.adapter.out.persistence.PersistenceTime.toTimestamp;

@Component
class NotificationEventPersistenceAdapter implements NotificationEventRepository, DeliveryRepository {

    private final JdbcClient jdbcClient;
    private final TransactionTemplate transactionTemplate;

    NotificationEventPersistenceAdapter(JdbcClient jdbcClient, TransactionTemplate transactionTemplate) {
        this.jdbcClient = jdbcClient;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public boolean saveIfAbsent(NotificationEvent notification) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO notification_events
                            (id, event_id, subscription_id, client_id, event_type, content, event_created_at,
                             webhook_url, delivery_status, attempt_count, cycle_attempt_count, replay_count,
                             next_attempt_at, origin, created_at, updated_at)
                        VALUES
                            (:id, :eventId, :subscriptionId, :clientId, :eventType, :content, :eventCreatedAt,
                             :webhookUrl, :status, :attemptCount, :cycleAttemptCount, :replayCount,
                             :nextAttemptAt, :origin, :createdAt, :updatedAt)
                        ON CONFLICT (event_id) DO NOTHING
                        """)
                .param("id", notification.id())
                .param("eventId", notification.eventId())
                .param("subscriptionId", notification.subscriptionId())
                .param("clientId", notification.clientId())
                .param("eventType", notification.eventType())
                .param("content", notification.content())
                .param("eventCreatedAt", toTimestamp(notification.eventCreatedAt()))
                .param("webhookUrl", notification.webhookUrl())
                .param("status", notification.status().name())
                .param("attemptCount", notification.attemptCount())
                .param("cycleAttemptCount", notification.cycleAttemptCount())
                .param("replayCount", notification.replayCount())
                .param("nextAttemptAt", toTimestamp(notification.nextAttemptAt()))
                .param("origin", notification.origin().name())
                .param("createdAt", toTimestamp(notification.createdAt()))
                .param("updatedAt", toTimestamp(notification.updatedAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public List<DeliveryTask> claimDue(ClaimRequest request) {
        if (request.limit() == 0) {
            return List.of();
        }
        Instant now = request.now();
        return transactionTemplate.execute(status -> {
            List<ClaimedRow> rows = jdbcClient.sql("""
                            UPDATE notification_events n
                            SET delivery_status     = 'PROCESSING',
                                locked_by           = :workerId,
                                locked_until        = :lockedUntil,
                                attempt_count       = n.attempt_count + 1,
                                cycle_attempt_count = n.cycle_attempt_count + 1,
                                next_attempt_at     = NULL,
                                last_attempt_at     = :now,
                                updated_at          = :now
                            WHERE n.id IN (SELECT id
                                           FROM notification_events
                                           WHERE delivery_status IN ('PENDING', 'RETRYING')
                                             AND next_attempt_at <= :now
                                           ORDER BY next_attempt_at, id
                                           LIMIT :limit
                                           FOR UPDATE SKIP LOCKED)
                            RETURNING n.id, n.event_id, n.client_id, n.event_type, n.content, n.event_created_at,
                                      n.webhook_url, n.attempt_count, n.cycle_attempt_count, n.replay_count
                            """)
                    .param("workerId", request.workerId())
                    .param("lockedUntil", toTimestamp(request.lockedUntil()))
                    .param("now", toTimestamp(now))
                    .param("limit", request.limit())
                    .query(NotificationEventPersistenceAdapter::toClaimedRow)
                    .list();

            List<DeliveryTask> tasks = new ArrayList<>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                DeliveryTask task = rows.get(i).toTask(request.attemptIds().get(i), now);
                insertInProgressAttempt(task, now);
                tasks.add(task);
            }
            return tasks;
        });
    }

    @Override
    public boolean recordResult(DeliveryCompletion completion) {
        DeliveryDecision decision = completion.decision();
        Boolean recorded = transactionTemplate.execute(status -> {
            int updated = jdbcClient.sql("""
                            UPDATE notification_events
                            SET delivery_status  = :status,
                                delivered_at     = :deliveredAt,
                                next_attempt_at  = :nextAttemptAt,
                                last_http_status = :httpStatus,
                                last_error       = :lastError,
                                locked_by        = NULL,
                                locked_until     = NULL,
                                updated_at       = :now
                            WHERE id = :id
                              AND delivery_status = 'PROCESSING'
                              AND locked_by = :workerId
                              AND attempt_count = :attemptNumber
                            """)
                    .param("status", decision.status().name())
                    .param("deliveredAt", toTimestamp(decision.deliveredAt()))
                    .param("nextAttemptAt", toTimestamp(decision.nextAttemptAt()))
                    .param("httpStatus", completion.httpStatus())
                    .param("lastError", summaryOf(decision.lastError()))
                    .param("now", toTimestamp(completion.completedAt()))
                    .param("id", completion.notificationEventId())
                    .param("workerId", completion.workerId())
                    .param("attemptNumber", completion.attemptNumber())
                    .update();
            if (updated == 0) {
                return false;
            }

            jdbcClient.sql("""
                            UPDATE delivery_attempts
                            SET status        = :status,
                                http_status   = :httpStatus,
                                error_code    = :errorCode,
                                error_message = :errorMessage,
                                completed_at  = :completedAt,
                                duration_ms   = :durationMs
                            WHERE id = :attemptId
                              AND status = 'IN_PROGRESS'
                            """)
                    .param("status", decision.attemptStatus().name())
                    .param("httpStatus", completion.httpStatus())
                    .param("errorCode", codeOf(decision.attemptError()))
                    .param("errorMessage", messageOf(decision.attemptError()))
                    .param("completedAt", toTimestamp(completion.completedAt()))
                    .param("durationMs", completion.durationMs())
                    .param("attemptId", completion.attemptId())
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(recorded);
    }

    @Override
    public List<ExpiredLease> findExpiredLeases(Instant now, int limit) {
        return jdbcClient.sql("""
                        SELECT id, locked_by, attempt_count, cycle_attempt_count
                        FROM notification_events
                        WHERE delivery_status = 'PROCESSING'
                          AND locked_until < :now
                        ORDER BY locked_until
                        LIMIT :limit
                        """)
                .param("now", toTimestamp(now))
                .param("limit", limit)
                .query((rs, rowNum) -> new ExpiredLease(
                        rs.getObject("id", UUID.class),
                        rs.getString("locked_by"),
                        rs.getInt("attempt_count"),
                        rs.getInt("cycle_attempt_count")))
                .list();
    }

    @Override
    public boolean recoverLease(LeaseRecovery recovery) {
        ExpiredLease lease = recovery.lease();
        DeliveryDecision decision = recovery.decision();
        Boolean recovered = transactionTemplate.execute(status -> {
            int updated = jdbcClient.sql("""
                            UPDATE notification_events
                            SET delivery_status = :status,
                                next_attempt_at = :nextAttemptAt,
                                last_error      = :lastError,
                                locked_by       = NULL,
                                locked_until    = NULL,
                                updated_at      = :now
                            WHERE id = :id
                              AND delivery_status = 'PROCESSING'
                              AND locked_by = :lockedBy
                              AND attempt_count = :attemptNumber
                              AND locked_until < :now
                            """)
                    .param("status", decision.status().name())
                    .param("nextAttemptAt", toTimestamp(decision.nextAttemptAt()))
                    .param("lastError", summaryOf(decision.lastError()))
                    .param("now", toTimestamp(recovery.now()))
                    .param("id", lease.notificationEventId())
                    .param("lockedBy", lease.lockedBy())
                    .param("attemptNumber", lease.attemptNumber())
                    .update();
            if (updated == 0) {
                return false;
            }

            jdbcClient.sql("""
                            UPDATE delivery_attempts
                            SET status        = :status,
                                error_code    = :errorCode,
                                error_message = :errorMessage,
                                completed_at  = :now
                            WHERE notification_event_id = :id
                              AND attempt_number = :attemptNumber
                              AND status = 'IN_PROGRESS'
                            """)
                    .param("status", decision.attemptStatus().name())
                    .param("errorCode", codeOf(decision.attemptError()))
                    .param("errorMessage", messageOf(decision.attemptError()))
                    .param("now", toTimestamp(recovery.now()))
                    .param("id", lease.notificationEventId())
                    .param("attemptNumber", lease.attemptNumber())
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(recovered);
    }

    private static String summaryOf(DeliveryError error) {
        return error == null ? null : error.summary();
    }

    private static String codeOf(DeliveryError error) {
        return error == null ? null : error.code();
    }

    private static String messageOf(DeliveryError error) {
        return error == null ? null : error.message();
    }

    private void insertInProgressAttempt(DeliveryTask task, Instant now) {
        jdbcClient.sql("""
                        INSERT INTO delivery_attempts
                            (id, notification_event_id, attempt_number, attempt_trigger, webhook_url, status, started_at)
                        VALUES (:id, :notificationEventId, :attemptNumber, :trigger, :webhookUrl, 'IN_PROGRESS', :now)
                        """)
                .param("id", task.attemptId())
                .param("notificationEventId", task.notificationEventId())
                .param("attemptNumber", task.attemptNumber())
                .param("trigger", task.trigger().name())
                .param("webhookUrl", task.webhookUrl())
                .param("now", toTimestamp(now))
                .update();
    }

    private static ClaimedRow toClaimedRow(ResultSet rs, int rowNum) throws SQLException {
        return new ClaimedRow(
                rs.getObject("id", UUID.class),
                rs.getInt("attempt_count"),
                rs.getInt("cycle_attempt_count"),
                rs.getInt("replay_count"),
                rs.getString("event_id"),
                rs.getString("client_id"),
                rs.getString("event_type"),
                rs.getString("content"),
                toInstant(rs.getTimestamp("event_created_at")),
                rs.getString("webhook_url"));
    }

    private record ClaimedRow(UUID id, int attemptCount, int cycleAttemptCount, int replayCount, String eventId,
                              String clientId, String eventType, String content, Instant eventCreatedAt,
                              String webhookUrl) {

        DeliveryTask toTask(UUID attemptId, Instant claimedAt) {
            return new DeliveryTask(id, attemptId, attemptCount, cycleAttemptCount,
                    AttemptTrigger.of(cycleAttemptCount, replayCount), eventId, clientId, eventType, content,
                    eventCreatedAt, webhookUrl, claimedAt);
        }

        @Override
        public String toString() {
            return "ClaimedRow[id=%s, eventId=%s, attemptCount=%d]".formatted(id, eventId, attemptCount);
        }
    }
}
