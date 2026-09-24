package com.cobre.notification.adapter.in.kafka;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wire format of {@code platform.events.v1}. Unknown fields are ignored for forward compatibility.
 */
record PlatformEventMessage(
        @JsonProperty("schema_version") Integer schemaVersion,
        @JsonProperty("event_id") String eventId,
        @JsonProperty("event_type") String eventType,
        @JsonProperty("client_id") String clientId,
        @JsonProperty("occurred_at") String occurredAt,
        @JsonProperty("content") String content) {
}
