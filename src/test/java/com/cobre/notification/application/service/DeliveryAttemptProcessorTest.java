package com.cobre.notification.application.service;

import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import com.cobre.notification.domain.policy.ExponentialBackoffRetryPolicy;
import com.cobre.notification.domain.policy.HttpStatusDeliveryResultClassifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

import static com.cobre.notification.application.service.InMemoryDeliveries.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class DeliveryAttemptProcessorTest {

    private static final String WORKER_ID = "worker-1";
    private static final int MAX_ATTEMPTS = 3;

    private final InMemoryDeliveries deliveries = new InMemoryDeliveries();

    @Test
    void successIsRecordedWithTheLeaseCoordinatesAndMonotonicDuration() {
        DeliveryTask task = claimed(1);
        var ticks = new AtomicLong(TimeUnit.MILLISECONDS.toNanos(1_000));
        LongSupplier nanoTime = () -> ticks.getAndAdd(TimeUnit.MILLISECONDS.toNanos(250));

        processor(t -> new HttpResponseReceived(204), nanoTime).process(task);

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.notificationEventId()).isEqualTo(task.notificationEventId());
            assertThat(completion.attemptId()).isEqualTo(task.attemptId());
            assertThat(completion.attemptNumber()).isEqualTo(task.attemptNumber());
            assertThat(completion.workerId()).isEqualTo(WORKER_ID);
            assertThat(completion.httpStatus()).isEqualTo(204);
            assertThat(completion.completedAt()).isEqualTo(NOW);
            assertThat(completion.durationMs()).isEqualTo(250);
            assertThat(completion.decision().status()).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(completion.decision().deliveredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void retryableFailureWithBudgetLeftSchedulesARetry() {
        processor(t -> new HttpResponseReceived(503)).process(claimed(1));

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.decision().status()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(completion.decision().attemptStatus()).isEqualTo(AttemptStatus.RETRYABLE_FAILURE);
            assertThat(completion.decision().nextAttemptAt()).isAfterOrEqualTo(NOW);
        });
    }

    @Test
    void retryableFailureOnTheLastAttemptFails() {
        processor(t -> new HttpResponseReceived(503)).process(claimed(MAX_ATTEMPTS));

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.decision().status()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(completion.decision().lastError().summary())
                    .isEqualTo("http_status: HTTP 503; retries exhausted after 3 attempts");
        });
    }

    @Test
    void unexpectedClientExceptionBecomesARetryableFailureWithoutItsMessage() {
        processor(t -> {
            throw new IllegalStateException("secret payload details");
        }).process(claimed(1));

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.httpStatus()).isNull();
            assertThat(completion.decision().status()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(completion.decision().attemptError())
                    .isEqualTo(new DeliveryError("unexpected_error", "IllegalStateException"));
        });
    }

    @Test
    void transportFailureKeepsItsSanitizedError() {
        DeliveryError timeout = new DeliveryError("timeout", "No response within 5000 ms");

        processor(t -> new TransportFailure(timeout)).process(claimed(1));

        assertThat(deliveries.completions.getFirst().decision().attemptError()).isEqualTo(timeout);
    }

    @Test
    void lostLeaseAndPersistenceErrorsNeverEscape() {
        deliveries.leaseLost = true;
        assertThatCode(() -> processor(t -> new HttpResponseReceived(200)).process(claimed(1)))
                .doesNotThrowAnyException();

        deliveries.recordFailure = new IllegalStateException("database down");
        assertThatCode(() -> processor(t -> new HttpResponseReceived(200)).process(claimed(1)))
                .doesNotThrowAnyException();
    }

    private DeliveryTask claimed(int cycleAttemptNumber) {
        DeliveryTask due = deliveries.addDueTask(cycleAttemptNumber);
        deliveries.due.clear();
        return new DeliveryTask(due.notificationEventId(), UUID.randomUUID(), due.attemptNumber(),
                due.cycleAttemptNumber(), due.trigger(), due.eventId(), due.clientId(), due.eventType(),
                due.content(), due.eventCreatedAt(), due.webhookUrl(), NOW);
    }

    private DeliveryAttemptProcessor processor(WebhookClient client) {
        return processor(client, System::nanoTime);
    }

    private DeliveryAttemptProcessor processor(WebhookClient client, LongSupplier nanoTime) {
        var lifecycle = new DeliveryLifecycle(new HttpStatusDeliveryResultClassifier(),
                new ExponentialBackoffRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10), MAX_ATTEMPTS,
                        RandomGenerator.of("L64X128MixRandom")));
        return new DeliveryAttemptProcessor(deliveries, client, lifecycle, Clock.fixed(NOW, ZoneOffset.UTC),
                nanoTime, WORKER_ID, com.cobre.notification.application.port.out.NoOpNotificationMetrics.INSTANCE);
    }
}
