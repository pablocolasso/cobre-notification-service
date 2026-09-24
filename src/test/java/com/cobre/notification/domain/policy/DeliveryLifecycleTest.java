package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryDecision;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class DeliveryLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final int MAX_ATTEMPTS = 3;

    /** Retries exactly one second later (or at the Retry-After hint) until the budget runs out. */
    private static final RetryPolicy ONE_SECOND = new RetryPolicy() {
        @Override
        public Optional<Instant> nextAttemptAt(int cycleAttemptNumber, Instant now, Duration retryAfter) {
            if (cycleAttemptNumber >= MAX_ATTEMPTS) {
                return Optional.empty();
            }
            return Optional.of(now.plus(retryAfter != null ? retryAfter : Duration.ofSeconds(1)));
        }

        @Override
        public int maxAttempts() {
            return MAX_ATTEMPTS;
        }
    };

    private final DeliveryLifecycle lifecycle =
            new DeliveryLifecycle(new HttpStatusDeliveryResultClassifier(), ONE_SECOND);

    @Test
    void successCompletesAndRecordsDeliveryTime() {
        DeliveryDecision decision = lifecycle.onResult(1, new HttpResponseReceived(200), NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.COMPLETED);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.SUCCESS);
        assertThat(decision.deliveredAt()).isEqualTo(NOW);
        assertThat(decision.nextAttemptAt()).isNull();
        assertThat(decision.lastError()).isNull();
    }

    @Test
    void permanentFailureFailsImmediatelyEvenWithBudgetLeft() {
        DeliveryDecision decision = lifecycle.onResult(1, new HttpResponseReceived(404), NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
        assertThat(decision.lastError().summary()).isEqualTo("http_status: HTTP 404");
        assertThat(decision.nextAttemptAt()).isNull();
    }

    @Test
    void retryableFailureSchedulesTheNextAttempt() {
        DeliveryDecision decision = lifecycle.onResult(1, new HttpResponseReceived(500), NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.RETRYABLE_FAILURE);
        assertThat(decision.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(decision.lastError().summary()).isEqualTo("http_status: HTTP 500");
    }

    @Test
    void retryAfterHintIsPassedToThePolicy() {
        DeliveryDecision decision = lifecycle.onResult(1, new HttpResponseReceived(429, Duration.ofSeconds(7)), NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(decision.nextAttemptAt()).isEqualTo(NOW.plusSeconds(7));
    }

    @Test
    void exhaustedBudgetFailsWithTheReasonOnTheNotificationOnly() {
        DeliveryError timeout = new DeliveryError("timeout", "Webhook did not respond within 5s");

        DeliveryDecision decision = lifecycle.onResult(MAX_ATTEMPTS, new TransportFailure(timeout), NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.RETRYABLE_FAILURE);
        assertThat(decision.attemptError()).isEqualTo(timeout);
        assertThat(decision.lastError().summary())
                .isEqualTo("timeout: Webhook did not respond within 5s; retries exhausted after 3 attempts");
    }

    @Test
    void expiredLeaseAbandonsTheAttemptAndRetries() {
        DeliveryDecision decision = lifecycle.onLeaseExpired(1, NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.RETRYING);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(decision.attemptError().code()).isEqualTo(DeliveryError.LEASE_EXPIRED);
        assertThat(decision.nextAttemptAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void expiredLeaseOnTheLastAttemptFails() {
        DeliveryDecision decision = lifecycle.onLeaseExpired(MAX_ATTEMPTS, NOW);

        assertThat(decision.status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(decision.attemptStatus()).isEqualTo(AttemptStatus.ABANDONED);
        assertThat(decision.lastError().summary()).endsWith("retries exhausted after 3 attempts");
    }
}
