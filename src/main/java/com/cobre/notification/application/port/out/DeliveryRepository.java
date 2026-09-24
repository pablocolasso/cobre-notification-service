package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryTask;

import java.time.Instant;
import java.util.List;

public interface DeliveryRepository {

    /**
     * Leases up to {@code request.limit()} due notifications to the worker and records an in-progress attempt for
     * each, in one short transaction. Rows locked by a concurrent claim are skipped, never waited for.
     */
    List<DeliveryTask> claimDue(ClaimRequest request);

    /**
     * @return {@code false} if the lease was lost (expired and recovered, or taken by another attempt); nothing is
     * written in that case.
     */
    boolean recordResult(DeliveryCompletion completion);

    List<ExpiredLease> findExpiredLeases(Instant now, int limit);

    /**
     * @return {@code false} if the lease changed since it was read (another recoverer won, or the result arrived).
     */
    boolean recoverLease(LeaseRecovery recovery);
}
