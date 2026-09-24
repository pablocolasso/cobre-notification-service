package com.cobre.notification.adapter.out.persistence.jpa;

import com.cobre.notification.adapter.out.persistence.entity.SubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, UUID> {

    Optional<SubscriptionEntity> findByClientIdAndEventTypeAndActiveTrue(String clientId, String eventType);
}
