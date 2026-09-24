package com.cobre.notification.application.port.in;

/**
 * @param clientId optional tenant filter; {@code null} means all clients.
 */
public record NotificationEventQuery(String clientId, int page, int size) {

    public NotificationEventQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
    }
}
