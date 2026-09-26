package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.application.port.out.BacklogQuery;
import com.cobre.notification.application.port.out.BacklogSnapshot;
import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.application.port.out.LeaseRecovery;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.ReplayCommand;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryAttempt;
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
class NotificationEventPersistenceAdapter implements NotificationEventRepository, DeliveryRepository, BacklogQuery {

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
                             next_attempt_at, last_attempt_at, delivered_at, last_http_status, last_error,
                             origin, created_at, updated_at)
                        VALUES
                            (:id, :eventId, :subscriptionId, :clientId, :eventType, :content, :eventCreatedAt,
                             :webhookUrl, :status, :attemptCount, :cycleAttemptCount, :replayCount,
                             :nextAttemptAt, :lastAttemptAt, :deliveredAt, :lastHttpStatus, :lastError,
                             :origin, :createdAt, :updatedAt)
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
                .param("lastAttemptAt", toTimestamp(notification.lastAttemptAt()))
                .param("deliveredAt", toTimestamp(notification.deliveredAt()))
                .param("lastHttpStatus", notification.lastHttpStatus())
                .param("lastError", notification.lastError())
                .param("origin", notification.origin().name())
                .param("createdAt", toTimestamp(notification.createdAt()))
                .param("updatedAt", toTimestamp(notification.updatedAt()))
                .update();
        return inserted == 1;
    }

    @Override
    public boolean saveAttemptIfAbsent(DeliveryAttempt attempt) {
        int inserted = jdbcClient.sql("""
                        INSERT INTO delivery_attempts
                            (id, notification_event_id, attempt_number, attempt_trigger, webhook_url, status,
                             http_status, error_code, error_message, started_at, completed_at, duration_ms)
                        VALUES
                            (:id, :notificationEventId, :attemptNumber, :trigger, :webhookUrl, :status,
                             :httpStatus, :errorCode, :errorMessage, :startedAt, :completedAt, :durationMs)
                        ON CONFLICT (notification_event_id, attempt_number) DO NOTHING
                        """)
                .param("id", attempt.id())
                .param("notificationEventId", attempt.notificationEventId())
                .param("attemptNumber", attempt.attemptNumber())
                .param("trigger", attempt.trigger().name())
                .param("webhookUrl", attempt.webhookUrl())
                .param("status", attempt.status().name())
                .param("httpStatus", attempt.httpStatus())
                .param("errorCode", attempt.errorCode())
                .param("errorMessage", attempt.errorMessage())
                .param("startedAt", toTimestamp(attempt.startedAt()))
                .param("completedAt", toTimestamp(attempt.completedAt()))
                .param("durationMs", attempt.durationMs())
                .update();
        return inserted == 1;
    }

    @Override
    public boolean requestReplay(ReplayCommand command) {
        var spec = command.clientId() == null
                ? jdbcClient.sql("""
                        UPDATE notification_events
                        SET delivery_status     = 'PENDING',
                            cycle_attempt_count = 0,
                            replay_count        = replay_count + 1,
                            next_attempt_at     = :now,
                            webhook_url         = :webhookUrl,
                            updated_at          = :now
                        WHERE id = :id
                          AND delivery_status = 'FAILED'
                        """)
                : jdbcClient.sql("""
                        UPDATE notification_events
                        SET delivery_status     = 'PENDING',
                            cycle_attempt_count = 0,
                            replay_count        = replay_count + 1,
                            next_attempt_at     = :now,
                            webhook_url         = :webhookUrl,
                            updated_at          = :now
                        WHERE id = :id
                          AND delivery_status = 'FAILED'
                          AND client_id = :clientId
                        """).param("clientId", command.clientId());
        return spec.param("now", toTimestamp(command.now()))
                .param("webhookUrl", command.webhookUrl())
                .param("id", command.notificationEventId())
                .update() == 1;
    }

    @Override
    public List<DeliveryTask> claimDue(ClaimRequest request) {
        if (request.limit() == 0) {
            return List.of();
        }
        Instant now = request.now();
        return transactionTemplate.execute(status -> {
            List<ClaimedRow> rows = jdbcClient.sql("""
                            WITH due AS (
                                SELECT id, next_attempt_at, last_attempt_at
                                FROM notification_events
                                WHERE delivery_status IN ('PENDING', 'RETRYING')
                                  AND next_attempt_at <= :now
                                ORDER BY next_attempt_at, id
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED
                            )
                            UPDATE notification_events n
                            SET delivery_status     = 'PROCESSING',
                                locked_by           = :workerId,
                                locked_until        = :lockedUntil,
                                attempt_count       = n.attempt_count + 1,
                                cycle_attempt_count = n.cycle_attempt_count + 1,
                                next_attempt_at     = NULL,
                                last_attempt_at     = :now,
                                updated_at          = :now
                            FROM due
                            WHERE n.id = due.id
                            RETURNING n.id, n.event_id, n.client_id, n.event_type, n.content, n.event_created_at,
                                      n.webhook_url, n.attempt_count, n.cycle_attempt_count, n.replay_count,
                                      due.next_attempt_at AS previous_next_attempt_at,
                                      due.last_attempt_at AS previous_last_attempt_at,
                                      (SELECT s.signing_secret FROM subscriptions s
                                       WHERE s.client_id = n.client_id AND s.event_type = n.event_type AND s.active
                                       LIMIT 1) AS signing_secret
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
    public boolean revertClaim(DeliveryTask task, String workerId, Instant now) {
        Boolean reverted = transactionTemplate.execute(status -> {
            int updated = jdbcClient.sql("""
                            UPDATE notification_events
                            SET delivery_status     = :status,
                                attempt_count       = attempt_count - 1,
                                cycle_attempt_count = cycle_attempt_count - 1,
                                next_attempt_at     = :nextAttemptAt,
                                last_attempt_at     = :lastAttemptAt,
                                locked_by           = NULL,
                                locked_until        = NULL,
                                updated_at          = :now
                            WHERE id = :id
                              AND delivery_status = 'PROCESSING'
                              AND locked_by = :workerId
                              AND attempt_count = :attemptNumber
                              AND cycle_attempt_count = :cycleAttemptNumber
                            """)
                    .param("status", statusBeforeClaim(task))
                    .param("nextAttemptAt", toTimestamp(task.nextAttemptAtBeforeClaim()))
                    .param("lastAttemptAt", toTimestamp(task.lastAttemptAtBeforeClaim()))
                    .param("now", toTimestamp(now))
                    .param("id", task.notificationEventId())
                    .param("workerId", workerId)
                    .param("attemptNumber", task.attemptNumber())
                    .param("cycleAttemptNumber", task.cycleAttemptNumber())
                    .update();
            if (updated == 0) {
                return false;
            }
            int deleted = jdbcClient.sql("""
                            DELETE FROM delivery_attempts
                            WHERE id = :attemptId
                              AND notification_event_id = :notificationEventId
                              AND attempt_number = :attemptNumber
                              AND status = 'IN_PROGRESS'
                            """)
                    .param("attemptId", task.attemptId())
                    .param("notificationEventId", task.notificationEventId())
                    .param("attemptNumber", task.attemptNumber())
                    .update();
            if (deleted == 0) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        return Boolean.TRUE.equals(reverted);
    }

    /**
     * A claim moves PENDING (cycle still 0, including a replay) or RETRYING (cycle already started) to PROCESSING
     * and increments the cycle counter. The value on the task is the count after that increment.
     */
    private static String statusBeforeClaim(DeliveryTask task) {
        return task.cycleAttemptNumber() == 1 ? "PENDING" : "RETRYING";
    }

    @Override
    public List<ExpiredLease> findExpiredLeases(Instant now, int limit) {
        return jdbcClient.sql("""
                        SELECT id, locked_by, attempt_count, cycle_attempt_count, event_type
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
                        rs.getInt("cycle_attempt_count"),
                        rs.getString("event_type")))
                .list();
    }

    @Override
    public BacklogSnapshot snapshot(Instant now) {
        var counts = jdbcClient.sql("""
                        SELECT delivery_status, count(*) AS count
                        FROM notification_events
                        WHERE delivery_status IN ('PENDING', 'RETRYING', 'PROCESSING')
                        GROUP BY delivery_status
                        """)
                .query()
                .listOfRows();
        long pending = 0;
        long retrying = 0;
        long processing = 0;
        for (var row : counts) {
            long count = ((Number) row.get("count")).longValue();
            switch (String.valueOf(row.get("delivery_status"))) {
                case "PENDING" -> pending = count;
                case "RETRYING" -> retrying = count;
                case "PROCESSING" -> processing = count;
                default -> {
                }
            }
        }
        Instant oldest = jdbcClient.sql("""
                        SELECT min(created_at) AS oldest
                        FROM notification_events
                        WHERE delivery_status IN ('PENDING', 'RETRYING')
                        """)
                .query((rs, rowNum) -> {
                    var timestamp = rs.getTimestamp("oldest");
                    return timestamp == null ? null : toInstant(timestamp);
                })
                .optional()
                .orElse(null);
        long age = oldest == null ? 0 : Math.max(0, now.getEpochSecond() - oldest.getEpochSecond());
        return new BacklogSnapshot(pending, retrying, processing, age);
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
                rs.getString("webhook_url"),
                rs.getString("signing_secret"),
                toInstant(rs.getTimestamp("previous_next_attempt_at")),
                toInstant(rs.getTimestamp("previous_last_attempt_at")));
    }

    private record ClaimedRow(UUID id, int attemptCount, int cycleAttemptCount, int replayCount, String eventId,
                              String clientId, String eventType, String content, Instant eventCreatedAt,
                              String webhookUrl, String signingSecret, Instant previousNextAttemptAt,
                              Instant previousLastAttemptAt) {

        DeliveryTask toTask(UUID attemptId, Instant claimedAt) {
            return new DeliveryTask(id, attemptId, attemptCount, cycleAttemptCount,
                    AttemptTrigger.of(cycleAttemptCount, replayCount), eventId, clientId, eventType, content,
                    eventCreatedAt, webhookUrl, claimedAt, signingSecret, previousNextAttemptAt,
                    previousLastAttemptAt);
        }

        @Override
        public String toString() {
            return "ClaimedRow[id=%s, eventId=%s, attemptCount=%d]".formatted(id, eventId, attemptCount);
        }
    }
}
