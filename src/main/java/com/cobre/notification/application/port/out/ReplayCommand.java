package com.cobre.notification.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * @param clientId {@code null} for an operator (no tenant predicate); otherwise the row must belong to this client.
 */
public record ReplayCommand(UUID notificationEventId, String clientId, String webhookUrl, Instant now) {
}
