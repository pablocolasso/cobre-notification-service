package com.cobre.notification.adapter.out.persistence.jpa;

import com.cobre.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DeliveryAttemptJpaRepository extends JpaRepository<DeliveryAttemptEntity, UUID> {

    List<DeliveryAttemptEntity> findByNotificationEventIdOrderByAttemptNumberAsc(UUID notificationEventId);
}
