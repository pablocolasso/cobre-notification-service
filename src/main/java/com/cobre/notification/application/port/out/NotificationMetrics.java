package com.cobre.notification.application.port.out;

import java.time.Duration;

/**
 * Delivery and API counters. Tag values are closed sets; never {@code client_id} or {@code content}.
 */
public interface NotificationMetrics {

    void eventReceived(String outcome);

    void notificationCreated(String eventType);

    void deliveryAttempt(String outcome, String eventType, Duration duration);

    void retryScheduled();

    void failed(String reason);

    void replay(String outcome);

    void processingLatency(Duration latency);
}
