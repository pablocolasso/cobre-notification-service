package com.cobre.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

public record NotificationEvent(
        UUID id,
        String eventId,
        UUID subscriptionId,
        String clientId,
        String eventType,
        String content,
        Instant eventCreatedAt,
        String webhookUrl,
        DeliveryStatus status,
        int attemptCount,
        int cycleAttemptCount,
        int replayCount,
        Instant nextAttemptAt,
        Instant lastAttemptAt,
        Instant deliveredAt,
        Integer lastHttpStatus,
        String lastError,
        NotificationOrigin origin,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * A new notification is due immediately; the first delivery attempt is made by the worker, never by the consumer.
     */
    public static NotificationEvent pendingFrom(UUID id, PlatformEvent event, Subscription subscription, Instant now) {
        return new NotificationEvent(
                id,
                event.eventId(),
                subscription.id(),
                event.clientId(),
                event.eventType(),
                event.content(),
                event.occurredAt(),
                subscription.webhookUrl(),
                DeliveryStatus.PENDING,
                0,
                0,
                0,
                now,
                null,
                null,
                null,
                null,
                NotificationOrigin.KAFKA,
                now,
                now);
    }

    /**
     * Starts a new delivery cycle. {@code attemptCount} stays monotonic; the next claim opens {@code cycleAttemptCount}
     * from zero. The webhook snapshot is replaced with the subscription that is active now.
     */
    public NotificationEvent replay(String currentWebhookUrl, Instant now) {
        status.requireTransitionTo(DeliveryStatus.PENDING);
        return new NotificationEvent(
                id,
                eventId,
                subscriptionId,
                clientId,
                eventType,
                content,
                eventCreatedAt,
                currentWebhookUrl,
                DeliveryStatus.PENDING,
                attemptCount,
                0,
                replayCount + 1,
                now,
                lastAttemptAt,
                deliveredAt,
                lastHttpStatus,
                lastError,
                origin,
                createdAt,
                now);
    }

    @Override
    public String toString() {
        return "NotificationEvent[id=%s, eventId=%s, clientId=%s, eventType=%s, status=%s, attemptCount=%d]"
                .formatted(id, eventId, clientId, eventType, status, attemptCount);
    }
}
