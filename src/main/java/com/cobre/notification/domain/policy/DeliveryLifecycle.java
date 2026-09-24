package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryDecision;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.InvalidDestination;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Decides how a PROCESSING notification leaves that state: after a delivery result, or after its lease expired.
 */
public class DeliveryLifecycle {

    private final DeliveryResultClassifier classifier;
    private final RetryPolicy retryPolicy;

    public DeliveryLifecycle(DeliveryResultClassifier classifier, RetryPolicy retryPolicy) {
        this.classifier = classifier;
        this.retryPolicy = retryPolicy;
    }

    public DeliveryDecision onResult(int cycleAttemptNumber, DeliveryResult result, Instant completedAt) {
        DeliveryOutcome outcome = classifier.classify(result);
        DeliveryError error = errorOf(result, outcome);
        return switch (outcome) {
            case SUCCESS -> new DeliveryDecision(DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, null, null,
                    completedAt, null);
            case PERMANENT_FAILURE -> new DeliveryDecision(DeliveryStatus.FAILED, AttemptStatus.PERMANENT_FAILURE,
                    error, error, null, null);
            case RETRYABLE_FAILURE -> retryOrFail(cycleAttemptNumber, AttemptStatus.RETRYABLE_FAILURE, error,
                    completedAt, retryAfterOf(result));
        };
    }

    public DeliveryDecision onLeaseExpired(int cycleAttemptNumber, Instant now) {
        return retryOrFail(cycleAttemptNumber, AttemptStatus.ABANDONED, DeliveryError.leaseExpired(), now, null);
    }

    private DeliveryDecision retryOrFail(int cycleAttemptNumber, AttemptStatus attemptStatus, DeliveryError error,
                                         Instant now, Duration retryAfter) {
        Optional<Instant> nextAttemptAt = retryPolicy.nextAttemptAt(cycleAttemptNumber, now, retryAfter);
        if (nextAttemptAt.isPresent()) {
            return new DeliveryDecision(DeliveryStatus.RETRYING, attemptStatus, error, error, null,
                    nextAttemptAt.get());
        }
        return new DeliveryDecision(DeliveryStatus.FAILED, attemptStatus, error,
                error.retriesExhausted(cycleAttemptNumber), null, null);
    }

    private static DeliveryError errorOf(DeliveryResult result, DeliveryOutcome outcome) {
        if (outcome == DeliveryOutcome.SUCCESS) {
            return null;
        }
        return switch (result) {
            case HttpResponseReceived response -> DeliveryError.httpStatus(response.statusCode());
            case TransportFailure failure -> failure.error();
            case InvalidDestination destination -> destination.error();
        };
    }

    private static Duration retryAfterOf(DeliveryResult result) {
        return result instanceof HttpResponseReceived response ? response.retryAfter() : null;
    }
}
