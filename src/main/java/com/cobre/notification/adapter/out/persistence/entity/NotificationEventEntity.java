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
@Table(name = "notification_events")
public class NotificationEventEntity {

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, length = 100)
    private String eventId;

    @Column(name = "subscription_id")
    private UUID subscriptionId;

    @Column(name = "client_id", nullable = false, length = 64)
    private String clientId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "event_created_at", nullable = false)
    private Instant eventCreatedAt;

    @Column(name = "webhook_url", nullable = false, length = 2048)
    private String webhookUrl;

    @Column(name = "delivery_status", nullable = false, length = 20)
    private String deliveryStatus;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "cycle_attempt_count", nullable = false)
    private int cycleAttemptCount;

    @Column(name = "replay_count", nullable = false)
    private int replayCount;

    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_http_status")
    private Integer lastHttpStatus;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(nullable = false, length = 20)
    private String origin;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NotificationEventEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public UUID getSubscriptionId() {
        return subscriptionId;
    }

    public String getClientId() {
        return clientId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getContent() {
        return content;
    }

    public Instant getEventCreatedAt() {
        return eventCreatedAt;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getDeliveryStatus() {
        return deliveryStatus;
    }

    public int getAttemptCount() {
        return attemptCount;
    }

    public int getCycleAttemptCount() {
        return cycleAttemptCount;
    }

    public int getReplayCount() {
        return replayCount;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }

    public String getLastError() {
        return lastError;
    }

    public String getLockedBy() {
        return lockedBy;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public String getOrigin() {
        return origin;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
