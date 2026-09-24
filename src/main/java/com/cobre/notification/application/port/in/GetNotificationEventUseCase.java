package com.cobre.notification.application.port.in;

import java.util.Optional;
import java.util.UUID;

public interface GetNotificationEventUseCase {

    Optional<NotificationEventDetails> get(UUID notificationEventId);
}
