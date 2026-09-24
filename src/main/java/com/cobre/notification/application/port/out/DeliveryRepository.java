package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryTask;

import java.time.Instant;
import java.util.List;

public interface DeliveryRepository {

    /**
     * Atomically leases up to {@code batchSize} due notifications to {@code workerId}, skipping rows locked by other
     * workers, and records an in-progress attempt for each one.
     */
    List<DeliveryTask> claimDue(String workerId, int batchSize, Instant now, Instant lockedUntil);

    /**
     * Persists an attempt result, fenced by the lease.
     *
     * @return {@code false} if the worker no longer holds the lease; nothing is written in that case.
     */
    boolean recordResult(DeliveryCompletion completion);
}
