package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.Subscription;

import java.util.Optional;

public interface SubscriptionRepository {

    Optional<Subscription> findActive(String clientId, String eventType);

    void upsertActive(String clientId, String eventType, String webhookUrl);
}
