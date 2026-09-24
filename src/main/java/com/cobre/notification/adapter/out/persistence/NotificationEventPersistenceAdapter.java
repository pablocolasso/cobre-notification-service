package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.model.NotificationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
    public List<DeliveryTask> claimDue(String workerId, int batchSize, Instant now, Instant lockedUntil) {
        return transactionTemplate.execute(status -> {
            List<DeliveryTask> tasks = jdbcClient.sql("""
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
                                           LIMIT :batchSize
                                           FOR UPDATE SKIP LOCKED)
                            RETURNING n.id, n.event_id, n.client_id, n.event_type, n.content, n.event_created_at,
                                      n.webhook_url, n.attempt_count, n.cycle_attempt_count, n.replay_count
                            """)
                    .param("workerId", workerId)
                    .param("lockedUntil", toTimestamp(lockedUntil))
                    .param("now", toTimestamp(now))
                    .param("batchSize", batchSize)
                    .query((rs, rowNum) -> toTask(rs, now))
                    .list();

            tasks.forEach(task -> insertInProgressAttempt(task, now));
            return tasks;
        });
    }

    @Override
    public boolean recordResult(DeliveryCompletion completion) {
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
                              AND locked_by = :workerId
                              AND delivery_status = 'PROCESSING'
                            """)
                    .param("status", completion.notificationStatus().name())
                    .param("deliveredAt", toTimestamp(completion.deliveredAt()))
                    .param("nextAttemptAt", toTimestamp(completion.nextAttemptAt()))
                    .param("httpStatus", completion.httpStatus())
                    .param("lastError", completion.error() == null ? null : completion.error().summary())
                    .param("now", toTimestamp(completion.completedAt()))
                    .param("id", completion.notificationEventId())
                    .param("workerId", completion.workerId())
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
                    .param("status", completion.attemptStatus().name())
                    .param("httpStatus", completion.httpStatus())
                    .param("errorCode", completion.error() == null ? null : completion.error().code())
                    .param("errorMessage", completion.error() == null ? null : completion.error().message())
                    .param("completedAt", toTimestamp(completion.completedAt()))
                    .param("durationMs", completion.durationMs())
                    .param("attemptId", completion.attemptId())
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(recorded);
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

    private static DeliveryTask toTask(ResultSet rs, Instant claimedAt) throws SQLException {
        return new DeliveryTask(
                rs.getObject("id", UUID.class),
                UUID.randomUUID(),
                rs.getInt("attempt_count"),
                AttemptTrigger.of(rs.getInt("cycle_attempt_count"), rs.getInt("replay_count")),
                rs.getString("event_id"),
                rs.getString("client_id"),
                rs.getString("event_type"),
                rs.getString("content"),
                toInstant(rs.getTimestamp("event_created_at")),
                rs.getString("webhook_url"),
                claimedAt);
    }
}
