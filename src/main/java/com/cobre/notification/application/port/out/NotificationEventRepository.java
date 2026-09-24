package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.NotificationEvent;

public interface NotificationEventRepository {

    /**
     * @return {@code false} if a notification for the same {@code eventId} already exists.
     */
    boolean saveIfAbsent(NotificationEvent notification);

    /**
     * Inserts the attempt when missing ({@code ON CONFLICT (notification_event_id, attempt_number) DO NOTHING}).
     *
     * @return {@code false} if that attempt number already exists.
     */
    boolean saveAttemptIfAbsent(DeliveryAttempt attempt);

    /**
     * Conditional {@code FAILED → PENDING}. {@code 0} rows means a race lost the row or it is no longer {@code FAILED}.
     */
    boolean requestReplay(ReplayCommand command);
}
