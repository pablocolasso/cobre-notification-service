package com.cobre.notification.application.port.out;

import java.time.Duration;

public final class NoOpNotificationMetrics implements NotificationMetrics {

    public static final NoOpNotificationMetrics INSTANCE = new NoOpNotificationMetrics();

    private NoOpNotificationMetrics() {
    }

    @Override
    public void eventReceived(String outcome) {
    }

    @Override
    public void notificationCreated() {
    }

    @Override
    public void deliveryAttempt(String outcome, Duration duration) {
    }

    @Override
    public void retryScheduled() {
    }

    @Override
    public void failed(String reason) {
    }

    @Override
    public void replay(String outcome) {
    }

    @Override
    public void processingLatency(Duration latency) {
    }
}
