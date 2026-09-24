package com.cobre.notification.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * @param attemptIds one pre-generated id per notification that may be claimed; its size is the claim limit.
 */
public record ClaimRequest(String workerId, List<UUID> attemptIds, Instant now, Instant lockedUntil) {

    public ClaimRequest {
        attemptIds = List.copyOf(attemptIds);
    }

    public int limit() {
        return attemptIds.size();
    }
}
