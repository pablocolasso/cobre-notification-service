package com.cobre.notification.adapter.in.web.dto;

import com.cobre.notification.domain.model.NotificationEvent;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.UUID;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationEventResponse(
        UUID notificationEventId,
        String eventId,
        String clientId,
        String eventType,
        String content,
        String deliveryStatus,
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
