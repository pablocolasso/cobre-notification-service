package com.cobre.notification.application.port.in;

import com.cobre.notification.domain.model.NotificationEvent;

public interface ListNotificationEventsUseCase {

    /**
     * Ordered by event creation date descending, then id descending, so pages are stable.
     */
    PageResult<NotificationEvent> list(NotificationEventQuery query);
}
