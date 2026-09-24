package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.RecoverExpiredLeasesUseCase;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.application.port.out.LeaseRecovery;
import com.cobre.notification.domain.model.DeliveryDecision;
import com.cobre.notification.domain.policy.DeliveryLifecycle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

public class RecoverExpiredLeasesService implements RecoverExpiredLeasesUseCase {

    private static final Logger log = LoggerFactory.getLogger(RecoverExpiredLeasesService.class);

    private final DeliveryRepository deliveries;
    private final DeliveryLifecycle lifecycle;
    private final Clock clock;
    private final int batchSize;

    public RecoverExpiredLeasesService(DeliveryRepository deliveries, DeliveryLifecycle lifecycle, Clock clock,
                                       int batchSize) {
        this.deliveries = deliveries;
        this.lifecycle = lifecycle;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Override
    public int recoverExpiredLeases() {
        Instant now = clock.instant();
        int recovered = 0;
        for (ExpiredLease lease : deliveries.findExpiredLeases(now, batchSize)) {
            DeliveryDecision decision = lifecycle.onLeaseExpired(lease.cycleAttemptNumber(), now);
            if (deliveries.recoverLease(new LeaseRecovery(lease, decision, now))) {
                recovered++;
                log.atWarn()
                        .setMessage("Expired lease recovered; in-progress attempt abandoned")
                        .addKeyValue("notification_event_id", lease.notificationEventId())
                        .addKeyValue("attempt_number", lease.attemptNumber())
                        .addKeyValue("locked_by", lease.lockedBy())
                        .addKeyValue("status", decision.status().name().toLowerCase(Locale.ROOT))
                        .log();
            }
        }
        return recovered;
    }
}
