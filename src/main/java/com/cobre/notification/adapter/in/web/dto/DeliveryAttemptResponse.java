package com.cobre.notification.adapter.in.web.dto;

import com.cobre.notification.domain.model.DeliveryAttempt;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record DeliveryAttemptResponse(
        int attemptNumber,
        String trigger,
        String status,
        Integer httpStatus,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant completedAt,
        Long durationMs) {

    public static DeliveryAttemptResponse from(DeliveryAttempt attempt) {
        return new DeliveryAttemptResponse(
                attempt.attemptNumber(),
                ApiValues.lowercase(attempt.trigger()),
                ApiValues.lowercase(attempt.status()),
                attempt.httpStatus(),
                attempt.errorCode(),
                attempt.errorMessage(),
                attempt.startedAt(),
                attempt.completedAt(),
                attempt.durationMs());
    }
}
