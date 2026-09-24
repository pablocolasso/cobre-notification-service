package com.cobre.notification.adapter.in.web.dto;

import com.cobre.notification.domain.model.NotificationEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationEventResponse(
        UUID notificationEventId,
        @Schema(example = "EVT101") String eventId,
        @Schema(example = "CLIENT001") String clientId,
        @Schema(example = "credit_card_payment") String eventType,
        String content,
        @Schema(example = "completed") String deliveryStatus,
        Instant eventCreatedAt,
        Instant lastAttemptAt,
        Instant deliveredAt,
        int attemptCount,
        Instant nextAttemptAt) {

    public static NotificationEventResponse from(NotificationEvent notification) {
        return new NotificationEventResponse(
                notification.id(),
                notification.eventId(),
                notification.clientId(),
                notification.eventType(),
                notification.content(),
                ApiValues.lowercase(notification.status()),
                notification.eventCreatedAt(),
                notification.lastAttemptAt(),
                notification.deliveredAt(),
                notification.attemptCount(),
                notification.nextAttemptAt());
    }
}
