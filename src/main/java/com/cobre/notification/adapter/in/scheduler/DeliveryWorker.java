package com.cobre.notification.adapter.in.scheduler;

import com.cobre.notification.application.port.in.DeliverDueNotificationsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The only delivery path: every attempt, including the first one, starts from a claim made here.
 */
@Component
@ConditionalOnProperty(name = "app.delivery.worker.enabled", havingValue = "true", matchIfMissing = true)
class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final DeliverDueNotificationsUseCase deliverDueNotifications;

    DeliveryWorker(DeliverDueNotificationsUseCase deliverDueNotifications) {
        this.deliverDueNotifications = deliverDueNotifications;
    }

    @Scheduled(fixedDelayString = "${app.delivery.worker.poll-interval:1s}")
    void tick() {
        try {
            deliverDueNotifications.deliverDueNotifications();
        } catch (RuntimeException e) {
            log.atError()
                    .addKeyValue("error_type", e.getClass().getSimpleName())
                    .log("Delivery worker tick failed");
        }
    }
}
