package com.cobre.notification.domain.model;

import java.util.UUID;

public record Subscription(
        UUID id,
        String clientId,
        String eventType,
        String webhookUrl,
        boolean active) {
}
