package com.cobre.notification.adapter.out.metrics;

import com.cobre.notification.application.port.out.NotificationMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class MicrometerNotificationMetrics implements NotificationMetrics {

    static final String EVENTS_RECEIVED = "notification.events.received";
    static final String CREATED = "notification.created";
    static final String DELIVERY_ATTEMPTS = "notification.delivery.attempts";
    static final String DELIVERY_DURATION = "notification.delivery.duration";
    static final String RETRIES_SCHEDULED = "notification.retries.scheduled";
    static final String FAILED = "notification.failed";
    static final String REPLAYS = "notification.replays";
    static final String PROCESSING_LATENCY = "notification.processing.latency";

    private final MeterRegistry registry;

    MicrometerNotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void eventReceived(String outcome) {
        registry.counter(EVENTS_RECEIVED, "outcome", outcome).increment();
    }

    @Override
    public void notificationCreated(String eventType) {
        registry.counter(CREATED, "event_type", eventType).increment();
    }

    @Override
    public void deliveryAttempt(String outcome, String eventType, Duration duration) {
        registry.counter(DELIVERY_ATTEMPTS, "outcome", outcome, "event_type", eventType).increment();
        registry.timer(DELIVERY_DURATION, "outcome", outcome).record(duration);
    }

    @Override
    public void retryScheduled() {
        registry.counter(RETRIES_SCHEDULED).increment();
    }

    @Override
    public void failed(String reason) {
        registry.counter(FAILED, "reason", reason).increment();
    }

    @Override
    public void replay(String outcome) {
        registry.counter(REPLAYS, "outcome", outcome).increment();
    }

    @Override
    public void processingLatency(Duration latency) {
        registry.timer(PROCESSING_LATENCY).record(latency);
    }
}
