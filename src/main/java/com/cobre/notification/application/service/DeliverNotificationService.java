package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.DeliverDueNotificationsUseCase;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.policy.DeliveryResultClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class DeliverNotificationService implements DeliverDueNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverNotificationService.class);

    private final DeliveryRepository deliveries;
    private final WebhookClient webhookClient;
    private final DeliveryResultClassifier classifier;
    private final Clock clock;
    private final String workerId;
    private final int batchSize;
    private final Duration leaseDuration;

    public DeliverNotificationService(DeliveryRepository deliveries,
                                      WebhookClient webhookClient,
                                      DeliveryResultClassifier classifier,
                                      Clock clock,
                                      String workerId,
                                      int batchSize,
                                      Duration leaseDuration) {
        this.deliveries = deliveries;
        this.webhookClient = webhookClient;
        this.classifier = classifier;
        this.clock = clock;
        this.workerId = workerId;
        this.batchSize = batchSize;
        this.leaseDuration = leaseDuration;
    }

    @Override
    public int deliverDueNotifications() {
        Instant now = clock.instant();
        List<DeliveryTask> tasks = deliveries.claimDue(workerId, batchSize, now, now.plus(leaseDuration));
        for (DeliveryTask task : tasks) {
            deliver(task);
        }
        return tasks.size();
    }

    private void deliver(DeliveryTask task) {
        DeliveryResult result = invokeWebhook(task);
        DeliveryOutcome outcome = classifier.classify(result);
        DeliveryCompletion completion = toCompletion(task, result, outcome, clock.instant());

        boolean recorded;
        try {
            recorded = deliveries.recordResult(completion);
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("notification_event_id", task.notificationEventId())
                    .addKeyValue("attempt_number", task.attemptNumber())
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Could not persist delivery result; the lease will expire and the attempt will be recovered");
            return;
        }

        var logEvent = recorded ? log.atInfo() : log.atWarn();
        logEvent.addKeyValue("notification_event_id", task.notificationEventId())
                .addKeyValue("event_id", task.eventId())
                .addKeyValue("client_id", task.clientId())
                .addKeyValue("attempt_number", task.attemptNumber())
                .addKeyValue("outcome", outcome.name().toLowerCase())
                .addKeyValue("http_status", completion.httpStatus())
                .addKeyValue("error_code", completion.error() == null ? null : completion.error().code())
                .addKeyValue("duration_ms", completion.durationMs())
                .log(recorded ? "Delivery attempt recorded" : "Lease lost before recording the delivery result");
    }

    private DeliveryResult invokeWebhook(DeliveryTask task) {
        try {
            return webhookClient.deliver(task);
        } catch (RuntimeException e) {
            return new TransportFailure(new DeliveryError("unexpected_error", e.getClass().getSimpleName()));
        }
    }

    private DeliveryCompletion toCompletion(DeliveryTask task, DeliveryResult result, DeliveryOutcome outcome,
                                            Instant completedAt) {
        DeliveryStatus notificationStatus = outcome == DeliveryOutcome.SUCCESS
                ? DeliveryStatus.COMPLETED
                : DeliveryStatus.FAILED;

        Integer httpStatus = result instanceof HttpResponseReceived response ? response.statusCode() : null;
        DeliveryError error = switch (result) {
            case HttpResponseReceived response when outcome == DeliveryOutcome.SUCCESS -> null;
            case HttpResponseReceived response -> DeliveryError.httpStatus(response.statusCode());
            case TransportFailure failure -> failure.error();
        };

        return new DeliveryCompletion(
                task.notificationEventId(),
                task.attemptId(),
                workerId,
                notificationStatus,
                toAttemptStatus(outcome),
                httpStatus,
                error,
                completedAt,
                notificationStatus == DeliveryStatus.COMPLETED ? completedAt : null,
                null,
                Duration.between(task.claimedAt(), completedAt).toMillis());
    }

    private static AttemptStatus toAttemptStatus(DeliveryOutcome outcome) {
        return switch (outcome) {
            case SUCCESS -> AttemptStatus.SUCCESS;
            case RETRYABLE_FAILURE -> AttemptStatus.RETRYABLE_FAILURE;
            case PERMANENT_FAILURE -> AttemptStatus.PERMANENT_FAILURE;
        };
    }
}
