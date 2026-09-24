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
@Table(name = "delivery_attempts")
public class DeliveryAttemptEntity {

    @Id
    private UUID id;

    @Column(name = "notification_event_id", nullable = false)
    private UUID notificationEventId;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "attempt_trigger", nullable = false, length = 10)
    private String attemptTrigger;

    @Column(name = "webhook_url", nullable = false, length = 2048)
    private String webhookUrl;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    protected DeliveryAttemptEntity() {
    }

    public UUID getId() {
        return id;
    }

    public UUID getNotificationEventId() {
        return notificationEventId;
    }

    public int getAttemptNumber() {
        return attemptNumber;
    }

    public String getAttemptTrigger() {
        return attemptTrigger;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    public String getStatus() {
        return status;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }
}
