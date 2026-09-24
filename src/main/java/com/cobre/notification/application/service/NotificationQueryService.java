package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.GetNotificationEventUseCase;
import com.cobre.notification.application.port.in.ListNotificationEventsUseCase;
import com.cobre.notification.application.port.in.NotificationEventDetails;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.PageResult;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.domain.model.NotificationEvent;

import java.util.Optional;
import java.util.UUID;

public class NotificationQueryService implements ListNotificationEventsUseCase, GetNotificationEventUseCase {

    private final NotificationEventQueryRepository queries;

    public NotificationQueryService(NotificationEventQueryRepository queries) {
        this.queries = queries;
    }

    @Override
    public PageResult<NotificationEvent> list(NotificationEventQuery query) {
        return queries.findPage(query);
    }

    @Override
    public Optional<NotificationEventDetails> get(UUID notificationEventId) {
        return queries.findById(notificationEventId)
                .map(notification -> new NotificationEventDetails(notification,
                        queries.findAttempts(notificationEventId)));
    }
}
