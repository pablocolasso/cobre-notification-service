package com.cobre.notification.domain.model;

import java.util.UUID;

public record Subscription(
        UUID id,
        String clientId,
        String eventType,
        String webhookUrl,
        boolean active,
        String signingSecret) {

    public Subscription(UUID id, String clientId, String eventType, String webhookUrl, boolean active) {
        this(id, clientId, eventType, webhookUrl, active, null);
    }

    @Override
    public String toString() {
        return "Subscription[id=%s, clientId=%s, eventType=%s, active=%s]".formatted(id, clientId, eventType, active);
    }
}
