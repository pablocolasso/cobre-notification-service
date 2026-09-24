package com.cobre.notification.adapter.out.persistence.jpa;

import com.cobre.notification.adapter.out.persistence.entity.NotificationEventEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface NotificationEventJpaRepository extends JpaRepository<NotificationEventEntity, UUID> {

    Page<NotificationEventEntity> findByClientId(String clientId, Pageable pageable);
}
