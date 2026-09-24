package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.Subscription;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionRepository {

    Optional<Subscription> findActive(String clientId, String eventType);

    /**
     * Creates the active subscription with {@code id}, or updates the webhook URL of the existing one (whose id is
     * kept).
     */
    void upsertActive(UUID id, String clientId, String eventType, String webhookUrl);
}
