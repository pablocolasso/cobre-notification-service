package com.cobre.notification.adapter.out.webhook;

import com.cobre.notification.domain.model.DeliveryTask;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.UUID;

/**
 * Body sent to client webhooks. Field names are part of the public contract.
 */
record WebhookPayload(
        @JsonProperty("notification_event_id") UUID notificationEventId,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("occurred_at") Instant occurredAt,
        @JsonProperty("content") String content) {

    static WebhookPayload from(DeliveryTask task) {
        return new WebhookPayload(
                task.notificationEventId(),
                task.eventId(),
                task.eventType(),
                task.clientId(),
                task.eventCreatedAt(),
                task.content());
    }

    @Override
    public String toString() {
        return "WebhookPayload[notificationEventId=%s, eventId=%s, eventType=%s, clientId=%s, occurredAt=%s]".formatted(notificationEventId, eventId, eventType, clientId, occurredAt);
    }
}
