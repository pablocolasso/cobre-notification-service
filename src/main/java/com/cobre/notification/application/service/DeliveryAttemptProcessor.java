package com.cobre.notification.application.service;

import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.NotificationMetrics;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryDecision;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Delivers one claimed task and records its result. Runs outside any transaction and never throws.
 */
public class DeliveryAttemptProcessor {

    private static final Logger log = LoggerFactory.getLogger(DeliveryAttemptProcessor.class);

    private final DeliveryRepository deliveries;
    private final WebhookClient webhookClient;
    private final DeliveryLifecycle lifecycle;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final String workerId;
    private final NotificationMetrics metrics;

    public DeliveryAttemptProcessor(DeliveryRepository deliveries,
                                    WebhookClient webhookClient,
                                    DeliveryLifecycle lifecycle,
                                    Clock clock,
                                    LongSupplier nanoTime,
                                    String workerId,
                                    NotificationMetrics metrics) {
        this.deliveries = deliveries;
        this.webhookClient = webhookClient;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.workerId = workerId;
        this.metrics = metrics;
    }

    public void process(DeliveryTask task) {
        MDC.put("notification_event_id", task.notificationEventId().toString());
        MDC.put("event_id", task.eventId());
        MDC.put("client_id", task.clientId());
        MDC.put("attempt_number", Integer.toString(task.attemptNumber()));
        try {
            long started = nanoTime.getAsLong();
            DeliveryResult result = invokeWebhook(task);
            long durationMs = TimeUnit.NANOSECONDS.toMillis(nanoTime.getAsLong() - started);
            Instant completedAt = clock.instant();

            DeliveryDecision decision = lifecycle.onResult(task.cycleAttemptNumber(), result, completedAt);
            MDC.put("status", lowercase(decision.status()));
            Integer httpStatus = result instanceof HttpResponseReceived response ? response.statusCode() : null;
            boolean recorded = deliveries.recordResult(new DeliveryCompletion(task.notificationEventId(),
                    task.attemptId(), task.attemptNumber(), workerId, decision, httpStatus, completedAt, durationMs));
            if (recorded) {
                recordMetrics(task, decision, Duration.ofMillis(durationMs), completedAt);
            }

            var event = recorded ? log.atInfo() : log.atWarn();
            event.setMessage(recorded
                            ? "Delivery attempt recorded"
                            : "Lease lost before recording the result; the attempt was already abandoned")
                    .addKeyValue("notification_event_id", task.notificationEventId())
                    .addKeyValue("event_id", task.eventId())
                    .addKeyValue("client_id", task.clientId())
                    .addKeyValue("attempt_number", task.attemptNumber())
                    .addKeyValue("trigger", lowercase(task.trigger()))
                    .addKeyValue("status", lowercase(decision.status()))
                    .addKeyValue("attempt_status", lowercase(decision.attemptStatus()))
                    .addKeyValue("http_status", httpStatus)
                    .addKeyValue("error_code", decision.attemptError() == null ? null : decision.attemptError().code())
                    .addKeyValue("duration_ms", durationMs)
                    .log();
        } catch (RuntimeException e) {
            log.atError()
                    .setMessage("Could not record the delivery result; the lease will expire and lease recovery "
                            + "will retry or fail the notification")
                    .addKeyValue("notification_event_id", task.notificationEventId())
                    .addKeyValue("event_id", task.eventId())
                    .addKeyValue("client_id", task.clientId())
                    .addKeyValue("attempt_number", task.attemptNumber())
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log();
        } finally {
            MDC.remove("notification_event_id");
            MDC.remove("event_id");
            MDC.remove("client_id");
            MDC.remove("attempt_number");
            MDC.remove("status");
        }
    }

    private void recordMetrics(DeliveryTask task, DeliveryDecision decision, Duration duration, Instant completedAt) {
        String outcome = switch (decision.attemptStatus()) {
            case SUCCESS -> "success";
            case RETRYABLE_FAILURE -> "retryable_failure";
            case PERMANENT_FAILURE -> "permanent_failure";
            case ABANDONED -> "abandoned";
            case IN_PROGRESS -> "retryable_failure";
        };
        metrics.deliveryAttempt(outcome, task.eventType(), duration);
        if (decision.status() == DeliveryStatus.RETRYING) {
            metrics.retryScheduled();
        }
        if (decision.status() == DeliveryStatus.FAILED) {
            metrics.failed(failureReason(decision));
        }
        if (decision.status() == DeliveryStatus.COMPLETED) {
            metrics.processingLatency(Duration.between(task.eventCreatedAt(), completedAt));
        }
    }

    private static String failureReason(DeliveryDecision decision) {
        String code = decision.attemptError() == null ? null : decision.attemptError().code();
        if (DeliveryError.INVALID_DESTINATION.equals(code)) {
            return "invalid_destination";
        }
        if (decision.attemptStatus() == AttemptStatus.PERMANENT_FAILURE) {
            return "permanent_error";
        }
        return "max_attempts_reached";
    }

    private DeliveryResult invokeWebhook(DeliveryTask task) {
        try {
            return webhookClient.deliver(task);
        } catch (RuntimeException e) {
            return new TransportFailure(new DeliveryError("unexpected_error", e.getClass().getSimpleName()));
        }
    }

    private static String lowercase(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
