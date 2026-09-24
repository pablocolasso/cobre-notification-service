package com.cobre.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        UUID notificationEventId,
        int attemptNumber,
        AttemptTrigger trigger,
        String webhookUrl,
        AttemptStatus status,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {
}
