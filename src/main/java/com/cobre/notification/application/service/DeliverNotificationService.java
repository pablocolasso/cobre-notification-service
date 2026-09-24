package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.DeliverDueNotificationsUseCase;
import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.domain.model.DeliveryTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * Claims as many due notifications as there are free delivery slots and hands each one to the executor. The
 * semaphore bounds in-flight deliveries per instance; the claim never takes more than can start right away, so
 * nothing sits leased while waiting for a slot.
 */
public class DeliverNotificationService implements DeliverDueNotificationsUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliverNotificationService.class);

    private final DeliveryRepository deliveries;
    private final DeliveryAttemptProcessor processor;
    private final Executor executor;
    private final Semaphore slots;
    private final IdGenerator ids;
    private final Clock clock;
    private final String workerId;
    private final Duration leaseDuration;

    public DeliverNotificationService(DeliveryRepository deliveries,
                                      DeliveryAttemptProcessor processor,
                                      Executor executor,
                                      int maxConcurrency,
                                      IdGenerator ids,
                                      Clock clock,
                                      String workerId,
                                      Duration leaseDuration) {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("maxConcurrency must be >= 1");
        }
        this.deliveries = deliveries;
        this.processor = processor;
        this.executor = executor;
        this.slots = new Semaphore(maxConcurrency);
        this.ids = ids;
        this.clock = clock;
        this.workerId = workerId;
        this.leaseDuration = leaseDuration;
    }

    @Override
    public int deliverDueNotifications() {
        int reserved = slots.drainPermits();
        if (reserved == 0) {
            return 0;
        }

        List<DeliveryTask> tasks;
        try {
            Instant now = clock.instant();
            tasks = deliveries.claimDue(new ClaimRequest(workerId, newIds(reserved), now, now.plus(leaseDuration)));
        } catch (RuntimeException e) {
            slots.release(reserved);
            throw e;
        }
        slots.release(reserved - tasks.size());

        for (DeliveryTask task : tasks) {
            dispatch(task);
        }
        return tasks.size();
    }

    public int availableSlots() {
        return slots.availablePermits();
    }

    private void dispatch(DeliveryTask task) {
        try {
            executor.execute(() -> {
                try {
                    processor.process(task);
                } finally {
                    slots.release();
                }
            });
        } catch (RejectedExecutionException e) {
            slots.release();
            log.atWarn()
                    .setMessage("Delivery rejected by the executor; lease recovery will retry it")
                    .addKeyValue("notification_event_id", task.notificationEventId())
                    .addKeyValue("event_id", task.eventId())
                    .addKeyValue("client_id", task.clientId())
                    .addKeyValue("attempt_number", task.attemptNumber())
                    .log();
        }
    }

    private List<UUID> newIds(int count) {
        List<UUID> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            result.add(ids.newId());
        }
        return result;
    }
}
