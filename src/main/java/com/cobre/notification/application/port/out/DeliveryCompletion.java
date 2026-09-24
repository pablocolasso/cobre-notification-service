package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryDecision;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of one attempt, to be persisted only if {@code workerId} still holds the lease for {@code attemptNumber}.
 */
public record DeliveryCompletion(
        UUID notificationEventId,
        UUID attemptId,
        int attemptNumber,
        String workerId,
        DeliveryDecision decision,
        Integer httpStatus,
        Instant completedAt,
        long durationMs) {
}
