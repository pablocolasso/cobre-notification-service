package com.cobre.notification.adapter.in.web;

import java.util.UUID;

class NotificationEventNotFoundException extends RuntimeException {

    NotificationEventNotFoundException(UUID notificationEventId) {
        super("Notification event " + notificationEventId + " not found");
    }
}
