package com.cobre.notification.adapter.in.web.dto;

import com.cobre.notification.application.port.in.NotificationEventDetails;
import com.cobre.notification.domain.model.NotificationEvent;
import io.swagger.v3.oas.annotations.media.Schema;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * {@code webhook_url} is reduced to scheme, host and port: path and query may carry client secrets.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record NotificationEventDetailsResponse(
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
        Instant nextAttemptAt,
        String webhookUrl,
        int replayCount,
        String lastError,
        List<DeliveryAttemptResponse> deliveryAttempts) {

    public static NotificationEventDetailsResponse from(NotificationEventDetails details) {
        NotificationEvent notification = details.notification();
        return new NotificationEventDetailsResponse(
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
                notification.nextAttemptAt(),
                ApiValues.maskUrl(notification.webhookUrl()),
                notification.replayCount(),
                notification.lastError(),
                details.attempts().stream().map(DeliveryAttemptResponse::from).toList());
    }

    @Override
    public String toString() {
        return "NotificationEventDetailsResponse[notificationEventId=%s, eventId=%s, clientId=%s, eventType=%s, deliveryStatus=%s, eventCreatedAt=%s, lastAttemptAt=%s, deliveredAt=%s, attemptCount=%s, nextAttemptAt=%s, webhookUrl=%s, replayCount=%s, lastError=%s, deliveryAttempts=%s]".formatted(notificationEventId, eventId, clientId, eventType, deliveryStatus, eventCreatedAt, lastAttemptAt, deliveredAt, attemptCount, nextAttemptAt, webhookUrl, replayCount, lastError, deliveryAttempts);
    }
}
