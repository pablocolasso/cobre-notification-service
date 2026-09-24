package com.cobre.notification.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Read model only; writes go through JDBC in the persistence adapters.
 */
@Entity
@Immutable
@Table(name = "subscriptions")
public class SubscriptionEntity {

    @Id
    private UUID id;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "webhook_url", nullable = false, length = 2048)
    private String webhookUrl;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected SubscriptionEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
