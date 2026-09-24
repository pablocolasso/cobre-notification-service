package com.cobre.notification.application.port.in;

import java.util.UUID;

public interface GetNotificationEventUseCase {

    NotificationEventDetails get(Requester requester, UUID notificationEventId, String correlationId);
}
