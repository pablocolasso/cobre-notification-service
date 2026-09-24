package com.cobre.notification.adapter.out.persistence;

import com.cobre.notification.adapter.out.persistence.entity.DeliveryAttemptEntity;
import com.cobre.notification.adapter.out.persistence.entity.NotificationEventEntity;
import com.cobre.notification.adapter.out.persistence.jpa.DeliveryAttemptJpaRepository;
import com.cobre.notification.adapter.out.persistence.jpa.NotificationEventJpaRepository;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.PageResult;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.NotificationOrigin;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@Transactional(readOnly = true)
class NotificationEventQueryAdapter implements NotificationEventQueryRepository {

    private static final Sort DETERMINISTIC_ORDER =
            Sort.by(Sort.Order.desc("eventCreatedAt"), Sort.Order.desc("id"));

    private final NotificationEventJpaRepository notifications;
    private final DeliveryAttemptJpaRepository attempts;

    NotificationEventQueryAdapter(NotificationEventJpaRepository notifications, DeliveryAttemptJpaRepository attempts) {
        this.notifications = notifications;
        this.attempts = attempts;
    }

    @Override
    public PageResult<NotificationEvent> findPage(NotificationEventQuery query) {
        PageRequest pageRequest = PageRequest.of(query.page(), query.size(), DETERMINISTIC_ORDER);
        Page<NotificationEventEntity> page = query.clientId() == null
                ? notifications.findAll(pageRequest)
                : notifications.findByClientId(query.clientId(), pageRequest);

        return new PageResult<>(
                page.getContent().stream().map(NotificationEventQueryAdapter::toDomain).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }

    @Override
    public Optional<NotificationEvent> findById(UUID id) {
        return notifications.findById(id).map(NotificationEventQueryAdapter::toDomain);
    }

    @Override
    public List<DeliveryAttempt> findAttempts(UUID notificationEventId) {
        return attempts.findByNotificationEventIdOrderByAttemptNumberAsc(notificationEventId).stream()
                .map(NotificationEventQueryAdapter::toDomain)
                .toList();
    }

    private static NotificationEvent toDomain(NotificationEventEntity entity) {
        return new NotificationEvent(
                entity.getId(),
                entity.getEventId(),
                entity.getSubscriptionId(),
                entity.getClientId(),
                entity.getEventType(),
                entity.getContent(),
                entity.getEventCreatedAt(),
                entity.getWebhookUrl(),
                DeliveryStatus.valueOf(entity.getDeliveryStatus()),
                entity.getAttemptCount(),
                entity.getCycleAttemptCount(),
                entity.getReplayCount(),
                entity.getNextAttemptAt(),
                entity.getLastAttemptAt(),
                entity.getDeliveredAt(),
                entity.getLastHttpStatus(),
                entity.getLastError(),
                NotificationOrigin.valueOf(entity.getOrigin()),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private static DeliveryAttempt toDomain(DeliveryAttemptEntity entity) {
        return new DeliveryAttempt(
                entity.getId(),
                entity.getNotificationEventId(),
                entity.getAttemptNumber(),
                AttemptTrigger.valueOf(entity.getAttemptTrigger()),
                entity.getWebhookUrl(),
                AttemptStatus.valueOf(entity.getStatus()),
                entity.getHttpStatus(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getStartedAt(),
                entity.getCompletedAt(),
                entity.getDurationMs());
    }
}
