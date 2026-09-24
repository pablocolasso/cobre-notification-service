package com.cobre.notification.adapter.in.scheduler;

import com.cobre.notification.application.port.in.DeliverDueNotificationsUseCase;
import com.cobre.notification.application.port.in.RecoverExpiredLeasesUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The only delivery path: every attempt, including the first one, starts from a claim made here. Expired leases
 * are recovered first so their notifications become due again in the same tick.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.worker.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final RecoverExpiredLeasesUseCase recoverExpiredLeases;
    private final DeliverDueNotificationsUseCase deliverDueNotifications;

    DeliveryWorker(RecoverExpiredLeasesUseCase recoverExpiredLeases,
                   DeliverDueNotificationsUseCase deliverDueNotifications) {
        this.recoverExpiredLeases = recoverExpiredLeases;
        this.deliverDueNotifications = deliverDueNotifications;
    }

    @Scheduled(fixedDelayString = "${app.delivery.worker.poll-interval:500ms}")
    void tick() {
        try {
            recoverExpiredLeases.recoverExpiredLeases();
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Lease recovery failed");
        }
        try {
            deliverDueNotifications.deliverDueNotifications();
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Delivery worker tick failed");
        }
    }
}
