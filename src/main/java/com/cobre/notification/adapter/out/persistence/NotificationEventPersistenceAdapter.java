package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.domain.model.NotificationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import static com.cobre.notification.adapter.out.persistence.PersistenceTime.toTimestamp;

@Component
class NotificationEventPersistenceAdapter implements NotificationEventRepository {

    private final JdbcClient jdbcClient;

    NotificationEventPersistenceAdapter(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
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
}
