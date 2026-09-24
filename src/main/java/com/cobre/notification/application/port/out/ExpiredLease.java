package com.cobre.notification.application.port.out;

import java.util.UUID;

/**
 * A PROCESSING notification whose lease ended without a recorded result.
 */
public record ExpiredLease(UUID notificationEventId, String lockedBy, int attemptNumber, int cycleAttemptNumber,
                          String eventType) {

    public ExpiredLease(UUID notificationEventId, String lockedBy, int attemptNumber, int cycleAttemptNumber) {
        this(notificationEventId, lockedBy, attemptNumber, cycleAttemptNumber, null);
    }
}
