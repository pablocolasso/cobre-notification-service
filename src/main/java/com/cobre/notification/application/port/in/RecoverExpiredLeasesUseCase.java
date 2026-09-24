package com.cobre.notification.application.port.in;

public interface RecoverExpiredLeasesUseCase {

    /**
     * Moves PROCESSING notifications whose lease expired to RETRYING (or FAILED when the attempt budget is spent)
     * and abandons their in-progress attempt.
     *
     * @return number of notifications recovered by this call.
     */
    int recoverExpiredLeases();
}
