package com.cobre.notification.application.port.in;

import java.util.UUID;

public interface ReplayNotificationEventUseCase {

    NotificationEventDetails replay(Requester requester, UUID notificationEventId, String correlationId);
}
