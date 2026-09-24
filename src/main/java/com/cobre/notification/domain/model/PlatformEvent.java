package com.cobre.notification.domain.model;

import java.time.Instant;
import java.util.Objects;

public record PlatformEvent(
        String eventId,
        String eventType,
        String clientId,
        Instant occurredAt,
        String content,
        int schemaVersion) {

    public PlatformEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(content, "content");
    }

    @Override
    public String toString() {
        return "PlatformEvent[eventId=%s, eventType=%s, clientId=%s, occurredAt=%s, schemaVersion=%d]"
                .formatted(eventId, eventType, clientId, occurredAt, schemaVersion);
    }
}
