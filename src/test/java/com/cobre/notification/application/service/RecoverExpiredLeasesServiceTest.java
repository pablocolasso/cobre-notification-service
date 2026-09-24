package com.cobre.notification.application.service;

import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import com.cobre.notification.domain.policy.ExponentialBackoffRetryPolicy;
import com.cobre.notification.domain.policy.HttpStatusDeliveryResultClassifier;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.UUID;

import static com.cobre.notification.application.service.InMemoryDeliveries.NOW;
import static org.assertj.core.api.Assertions.assertThat;

class RecoverExpiredLeasesServiceTest {

    private static final int MAX_ATTEMPTS = 3;

    private final InMemoryDeliveries deliveries = new InMemoryDeliveries();
    private final RecoverExpiredLeasesService service = new RecoverExpiredLeasesService(deliveries,
            new DeliveryLifecycle(new HttpStatusDeliveryResultClassifier(),
                    new ExponentialBackoffRetryPolicy(Duration.ofSeconds(5), Duration.ofMinutes(10), MAX_ATTEMPTS,
                            new Random(7))),
            Clock.fixed(NOW, ZoneOffset.UTC), 10,
            com.cobre.notification.application.port.out.NoOpNotificationMetrics.INSTANCE);

    @Test
    void expiredLeaseWithBudgetLeftIsRescheduledAndItsAttemptAbandoned() {
        ExpiredLease lease = new ExpiredLease(UUID.randomUUID(), "worker-dead", 1, 1);
        deliveries.expired.add(lease);

        assertThat(service.recoverExpiredLeases()).isEqualTo(1);

        assertThat(deliveries.recoveries).singleElement().satisfies(recovery -> {
            assertThat(recovery.lease()).isEqualTo(lease);
            assertThat(recovery.now()).isEqualTo(NOW);
            assertThat(recovery.decision().status()).isEqualTo(DeliveryStatus.RETRYING);
            assertThat(recovery.decision().attemptStatus()).isEqualTo(AttemptStatus.ABANDONED);
            assertThat(recovery.decision().nextAttemptAt()).isAfterOrEqualTo(NOW);
        });
    }

    @Test
    void expiredLeaseOnTheLastAttemptFails() {
        deliveries.expired.add(new ExpiredLease(UUID.randomUUID(), "worker-dead", MAX_ATTEMPTS, MAX_ATTEMPTS));

        service.recoverExpiredLeases();

        assertThat(deliveries.recoveries.getFirst().decision().status()).isEqualTo(DeliveryStatus.FAILED);
        assertThat(deliveries.recoveries.getFirst().decision().lastError().summary())
                .startsWith("lease_expired: ").endsWith("retries exhausted after 3 attempts");
    }

    @Test
    void leaseRecoveredByAnotherInstanceIsNotCounted() {
        deliveries.expired.add(new ExpiredLease(UUID.randomUUID(), "worker-dead", 1, 1));
        deliveries.recoveryLost = true;

        assertThat(service.recoverExpiredLeases()).isZero();
    }
}
