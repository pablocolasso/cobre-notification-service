package com.cobre.notification.adapter.out.persistence.jpa;

import com.cobre.notification.adapter.out.persistence.entity.NotificationEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface NotificationEventJpaRepository
        extends JpaRepository<NotificationEventEntity, UUID>, JpaSpecificationExecutor<NotificationEventEntity> {

    Optional<NotificationEventEntity> findByIdAndClientId(UUID id, String clientId);
}
