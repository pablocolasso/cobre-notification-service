package com.cobre.notification.application.port.in;

import com.cobre.notification.domain.model.DeliveryStatus;

import java.time.Instant;

/**
 * @param clientId requested filter from the HTTP query. For a {@link Requester.Client} the application ignores it
 *                 and uses {@code requester.clientId} instead.
 */
public record NotificationEventQuery(
        String clientId,
        DeliveryStatus deliveryStatus,
        Instant createdFrom,
        Instant createdTo,
        int page,
        int size) {

    public static final int MAX_PAGE_SIZE = 100;

    public NotificationEventQuery {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new IllegalArgumentException("created_from must be before created_to");
        }
    }

    public NotificationEventQuery withClientId(String scopedClientId) {
        return new NotificationEventQuery(scopedClientId, deliveryStatus, createdFrom, createdTo, page, size);
    }
}
