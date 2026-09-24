package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.adapter.out.persistence.entity.SubscriptionEntity;
import com.cobre.notification.adapter.out.persistence.jpa.SubscriptionJpaRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.Subscription;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
class SubscriptionPersistenceAdapter implements SubscriptionRepository {

    private final SubscriptionJpaRepository jpaRepository;
    private final JdbcClient jdbcClient;
    private final Clock clock;

    SubscriptionPersistenceAdapter(SubscriptionJpaRepository jpaRepository, JdbcClient jdbcClient, Clock clock) {
        this.jpaRepository = jpaRepository;
        this.jdbcClient = jdbcClient;
        this.clock = clock;
    }

    @Override
    public Optional<Subscription> findActive(String clientId, String eventType) {
        return jpaRepository.findByClientIdAndEventTypeAndActiveTrue(clientId, eventType)
                .map(SubscriptionPersistenceAdapter::toDomain);
    }

    @Override
    public void upsertActive(String clientId, String eventType, String webhookUrl) {
        Instant now = clock.instant();
        jdbcClient.sql("""
                        INSERT INTO subscriptions (id, client_id, event_type, webhook_url, active, created_at, updated_at)
                        VALUES (:id, :clientId, :eventType, :webhookUrl, TRUE, :now, :now)
                        ON CONFLICT (client_id, event_type) WHERE active
                        DO UPDATE SET webhook_url = EXCLUDED.webhook_url, updated_at = EXCLUDED.updated_at
                        """)
                .param("id", UUID.randomUUID())
                .param("clientId", clientId)
                .param("eventType", eventType)
                .param("webhookUrl", webhookUrl)
                .param("now", PersistenceTime.toTimestamp(now))
                .update();
    }

    private static Subscription toDomain(SubscriptionEntity entity) {
        return new Subscription(
                entity.getId(),
                entity.getClientId(),
                entity.getEventType(),
                entity.getWebhookUrl(),
                entity.isActive());
    }
}
