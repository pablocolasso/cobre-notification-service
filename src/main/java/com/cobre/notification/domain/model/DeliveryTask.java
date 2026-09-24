package com.cobre.notification.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * A claimed notification, leased to one worker, with its in-progress attempt already recorded.
 */
public record DeliveryTask(
        UUID notificationEventId,
        UUID attemptId,
        int attemptNumber,
        int cycleAttemptNumber,
        AttemptTrigger trigger,
        String eventId,
        String clientId,
        String eventType,
        String content,
        Instant eventCreatedAt,
        String webhookUrl,
        Instant claimedAt,
        String signingSecret) {

    public DeliveryTask(
            UUID notificationEventId,
            UUID attemptId,
            int attemptNumber,
            int cycleAttemptNumber,
            AttemptTrigger trigger,
            String eventId,
            String clientId,
            String eventType,
            String content,
            Instant eventCreatedAt,
            String webhookUrl,
            Instant claimedAt) {
        this(notificationEventId, attemptId, attemptNumber, cycleAttemptNumber, trigger, eventId, clientId, eventType,
                content, eventCreatedAt, webhookUrl, claimedAt, null);
    }

    @Override
    public String toString() {
        return "DeliveryTask[notificationEventId=%s, attemptNumber=%d, trigger=%s, eventId=%s, clientId=%s]"
                .formatted(notificationEventId, attemptNumber, trigger, eventId, clientId);
    }
}
