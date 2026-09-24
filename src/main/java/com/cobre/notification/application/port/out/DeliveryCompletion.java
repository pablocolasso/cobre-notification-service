package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of one attempt, to be persisted only if {@code workerId} still holds the lease.
 */
public record DeliveryCompletion(
        UUID notificationEventId,
        UUID attemptId,
        String workerId,
        DeliveryStatus notificationStatus,
        AttemptStatus attemptStatus,
        Integer httpStatus,
        DeliveryError error,
        Instant completedAt,
        Instant deliveredAt,
        Instant nextAttemptAt,
        long durationMs) {
}
