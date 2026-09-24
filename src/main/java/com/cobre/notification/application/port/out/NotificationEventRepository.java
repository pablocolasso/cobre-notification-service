package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.NotificationEvent;

public interface NotificationEventRepository {

    /**
     * @return {@code false} if a notification for the same {@code eventId} already exists.
     */
    boolean saveIfAbsent(NotificationEvent notification);
}
