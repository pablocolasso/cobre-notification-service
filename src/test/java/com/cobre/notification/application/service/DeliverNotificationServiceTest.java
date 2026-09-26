package com.cobre.notification.application.service;

import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import com.cobre.notification.domain.policy.ExponentialBackoffRetryPolicy;
import com.cobre.notification.domain.policy.HttpStatusDeliveryResultClassifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static com.cobre.notification.application.service.InMemoryDeliveries.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliverNotificationServiceTest {

    private static final String WORKER_ID = "worker-1";
    private static final Duration LEASE = Duration.ofSeconds(60);
    private static final int MAX_CONCURRENCY = 3;

    private final InMemoryDeliveries deliveries = new InMemoryDeliveries();
    private final QueuedExecutor executor = new QueuedExecutor();
    private final DeliverNotificationService service = service(executor);

    @Test
    void claimsAsManyAsThereAreFreeSlotsWithPreGeneratedAttemptIds() {
        for (int i = 0; i < 5; i++) {
            deliveries.addDueTask();
        }

        int claimed = service.deliverDueNotifications();

        assertThat(claimed).isEqualTo(MAX_CONCURRENCY);
        assertThat(deliveries.claims).singleElement().satisfies(request -> {
            assertThat(request.workerId()).isEqualTo(WORKER_ID);
            assertThat(request.limit()).isEqualTo(MAX_CONCURRENCY);
            assertThat(request.attemptIds()).doesNotHaveDuplicates();
            assertThat(request.now()).isEqualTo(NOW);
            assertThat(request.lockedUntil()).isEqualTo(NOW.plus(LEASE));
        });
        assertThat(service.availableSlots()).isZero();
    }

    @Test
    void doesNotClaimWhileAllSlotsAreBusy() {
        for (int i = 0; i < 5; i++) {
            deliveries.addDueTask();
        }
        service.deliverDueNotifications();

        assertThat(service.deliverDueNotifications()).isZero();
        assertThat(deliveries.claims).hasSize(1);
    }

    @Test
    void finishedDeliveriesFreeTheirSlotsForTheNextClaim() {
        for (int i = 0; i < 5; i++) {
            deliveries.addDueTask();
        }
        service.deliverDueNotifications();

        executor.runNext();
        executor.runNext();

        assertThat(service.availableSlots()).isEqualTo(2);
        assertThat(service.deliverDueNotifications()).isEqualTo(2);
        assertThat(deliveries.claims.getLast().limit()).isEqualTo(2);
    }

    @Test
    void unusedSlotsAreReturnedImmediately() {
        deliveries.addDueTask();

        assertThat(service.deliverDueNotifications()).isEqualTo(1);
        assertThat(service.availableSlots()).isEqualTo(MAX_CONCURRENCY - 1);
    }

    @Test
    void slotsAreReturnedWhenTheClaimFails() {
        deliveries.claimFailure = new IllegalStateException("database down");

        assertThatThrownBy(service::deliverDueNotifications).isInstanceOf(IllegalStateException.class);
        assertThat(service.availableSlots()).isEqualTo(MAX_CONCURRENCY);
    }

    @Test
    void rejectedTaskReleasesItsSlot() {
        deliveries.addDueTask();
        var rejecting = service(command -> {
            throw new RejectedExecutionException("shutting down");
        });

        assertThat(rejecting.deliverDueNotifications()).isEqualTo(1);
        assertThat(rejecting.availableSlots()).isEqualTo(MAX_CONCURRENCY);
        assertThat(deliveries.completions).isEmpty();
        assertThat(deliveries.reverts).singleElement().satisfies(revert -> {
            assertThat(revert.workerId()).isEqualTo(WORKER_ID);
            assertThat(revert.now()).isEqualTo(NOW);
            assertThat(revert.task().attemptId()).isNotNull();
        });
    }

    @Test
    void rejectedTaskReleasesItsSlotWhenTheRevertFails() {
        deliveries.addDueTask();
        deliveries.revertFailure = new IllegalStateException("database down");
        var rejecting = service(command -> {
            throw new RejectedExecutionException("shutting down");
        });

        assertThat(rejecting.deliverDueNotifications()).isEqualTo(1);
        assertThat(rejecting.availableSlots()).isEqualTo(MAX_CONCURRENCY);
    }

    @Test
    void doesNothingWhenNothingIsDue() {
        assertThat(service.deliverDueNotifications()).isZero();
        assertThat(service.availableSlots()).isEqualTo(MAX_CONCURRENCY);
    }

    private DeliverNotificationService service(Executor executor) {
        return new DeliverNotificationService(deliveries, processor(), executor, MAX_CONCURRENCY, UUID::randomUUID,
                clock(), WORKER_ID, LEASE);
    }

    private DeliveryAttemptProcessor processor() {
        var lifecycle = new DeliveryLifecycle(new HttpStatusDeliveryResultClassifier(),
                new ExponentialBackoffRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10), 5, new Random(1)));
        return new DeliveryAttemptProcessor(deliveries, task -> new HttpResponseReceived(200), lifecycle, clock(),
                System::nanoTime, WORKER_ID, com.cobre.notification.application.port.out.NoOpNotificationMetrics.INSTANCE);
    }

    private static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** Holds submitted deliveries until the test runs them, to observe slots while deliveries are in flight. */
    private static final class QueuedExecutor implements Executor {

        private final List<Runnable> queued = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            queued.add(command);
        }

        void runNext() {
            queued.removeFirst().run();
        }
    }
}
