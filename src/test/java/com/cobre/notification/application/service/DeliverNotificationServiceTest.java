package com.cobre.notification.application.service;

import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.policy.SuccessOnlyDeliveryResultClassifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DeliverNotificationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");
    private static final String WORKER_ID = "worker-1";

    private final FakeDeliveries deliveries = new FakeDeliveries();

    @Test
    void claimsWithLeaseAndMarksCompletedOn2xx() {
        DeliveryTask task = deliveries.addDueTask();

        int claimed = serviceReturning(new HttpResponseReceived(204)).deliverDueNotifications();

        assertThat(claimed).isEqualTo(1);
        assertThat(deliveries.claimLockedUntil).isEqualTo(NOW.plus(Duration.ofSeconds(60)));
        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.notificationEventId()).isEqualTo(task.notificationEventId());
            assertThat(completion.attemptId()).isEqualTo(task.attemptId());
            assertThat(completion.workerId()).isEqualTo(WORKER_ID);
            assertThat(completion.notificationStatus()).isEqualTo(DeliveryStatus.COMPLETED);
            assertThat(completion.attemptStatus()).isEqualTo(AttemptStatus.SUCCESS);
            assertThat(completion.httpStatus()).isEqualTo(204);
            assertThat(completion.deliveredAt()).isEqualTo(NOW);
            assertThat(completion.error()).isNull();
        });
    }

    @Test
    void marksFailedOnNon2xxWithSanitizedError() {
        deliveries.addDueTask();

        serviceReturning(new HttpResponseReceived(500)).deliverDueNotifications();

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.notificationStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(completion.attemptStatus()).isEqualTo(AttemptStatus.PERMANENT_FAILURE);
            assertThat(completion.httpStatus()).isEqualTo(500);
            assertThat(completion.deliveredAt()).isNull();
            assertThat(completion.error()).isEqualTo(new DeliveryError("http_status", "HTTP 500"));
        });
    }

    @Test
    void marksFailedOnTransportFailure() {
        deliveries.addDueTask();
        DeliveryError timeout = new DeliveryError("timeout", "No response within 5000 ms");

        serviceReturning(new TransportFailure(timeout)).deliverDueNotifications();

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.notificationStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(completion.httpStatus()).isNull();
            assertThat(completion.error()).isEqualTo(timeout);
        });
    }

    @Test
    void unexpectedClientExceptionBecomesFailureWithoutItsMessage() {
        deliveries.addDueTask();
        var service = new DeliverNotificationService(deliveries,
                task -> {
                    throw new IllegalStateException("secret payload details");
                },
                new SuccessOnlyDeliveryResultClassifier(), fixedClock(), WORKER_ID, 10, Duration.ofSeconds(60));

        service.deliverDueNotifications();

        assertThat(deliveries.completions).singleElement().satisfies(completion -> {
            assertThat(completion.notificationStatus()).isEqualTo(DeliveryStatus.FAILED);
            assertThat(completion.error().code()).isEqualTo("unexpected_error");
            assertThat(completion.error().summary()).doesNotContain("secret payload details");
        });
    }

    @Test
    void lostLeaseIsToleratedAndOtherTasksContinue() {
        deliveries.addDueTask();
        deliveries.addDueTask();
        deliveries.leaseLost = true;

        int claimed = serviceReturning(new HttpResponseReceived(200)).deliverDueNotifications();

        assertThat(claimed).isEqualTo(2);
        assertThat(deliveries.completions).hasSize(2);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        int claimed = serviceReturning(new HttpResponseReceived(200)).deliverDueNotifications();

        assertThat(claimed).isZero();
        assertThat(deliveries.completions).isEmpty();
    }

    private DeliverNotificationService serviceReturning(DeliveryResult result) {
        return new DeliverNotificationService(deliveries, task -> result, new SuccessOnlyDeliveryResultClassifier(),
                fixedClock(), WORKER_ID, 10, Duration.ofSeconds(60));
    }

    private static Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    private static final class FakeDeliveries implements DeliveryRepository {

        private final List<DeliveryTask> due = new ArrayList<>();
        private final List<DeliveryCompletion> completions = new ArrayList<>();
        private Instant claimLockedUntil;
        private boolean leaseLost;

        DeliveryTask addDueTask() {
            DeliveryTask task = new DeliveryTask(UUID.randomUUID(), UUID.randomUUID(), 1, AttemptTrigger.INITIAL,
                    "EVT-" + UUID.randomUUID(), "CLIENT001", "credit_card_payment", "content", NOW,
                    "https://client.example/hook", NOW);
            due.add(task);
            return task;
        }

        @Override
        public List<DeliveryTask> claimDue(String workerId, int batchSize, Instant now, Instant lockedUntil) {
            claimLockedUntil = lockedUntil;
            List<DeliveryTask> claimed = List.copyOf(due.subList(0, Math.min(batchSize, due.size())));
            due.removeAll(claimed);
            return claimed;
        }

        @Override
        public boolean recordResult(DeliveryCompletion completion) {
            completions.add(completion);
            return !leaseLost;
        }
    }
}
