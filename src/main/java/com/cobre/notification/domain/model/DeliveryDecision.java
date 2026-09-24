package com.cobre.notification.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The state a PROCESSING notification moves to, and how its in-progress attempt is closed.
 *
 * @param attemptError   error recorded on the attempt ({@code error_code}, {@code error_message}).
 * @param lastError      error recorded on the notification ({@code last_error}); includes why retrying stopped.
 */
public record DeliveryDecision(
        DeliveryStatus status,
        AttemptStatus attemptStatus,
        DeliveryError attemptError,
        DeliveryError lastError,
        Instant deliveredAt,
        Instant nextAttemptAt) {

    public DeliveryDecision {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(attemptStatus, "attemptStatus");
        DeliveryStatus.PROCESSING.requireTransitionTo(status);
        if (status == DeliveryStatus.COMPLETED && deliveredAt == null) {
            throw new IllegalArgumentException("COMPLETED requires deliveredAt");
        }
        if (status == DeliveryStatus.RETRYING && nextAttemptAt == null) {
            throw new IllegalArgumentException("RETRYING requires nextAttemptAt");
        }
        if (status != DeliveryStatus.RETRYING && nextAttemptAt != null) {
            throw new IllegalArgumentException(status + " must not schedule another attempt");
        }
    }
}
