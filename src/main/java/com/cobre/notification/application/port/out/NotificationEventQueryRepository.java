package com.cobre.notification.application.port.out;

import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.PageResult;
import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.NotificationEvent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationEventQueryRepository {

    PageResult<NotificationEvent> findPage(NotificationEventQuery query);

    /**
     * @param clientId {@code null} to skip the tenant predicate (operator, no filter).
     */
    Optional<NotificationEvent> findById(UUID id, String clientId);

    List<DeliveryAttempt> findAttempts(UUID notificationEventId);
}
