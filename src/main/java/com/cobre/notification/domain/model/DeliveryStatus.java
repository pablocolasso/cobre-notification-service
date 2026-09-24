package com.cobre.notification.domain.model;

import java.util.Set;

public enum DeliveryStatus {
    PENDING,
    PROCESSING,
    RETRYING,
    COMPLETED,
    FAILED;

    /**
     * FAILED -> PENDING is the operator/client replay. COMPLETED is terminal.
     */
    public Set<DeliveryStatus> allowedTransitions() {
        return switch (this) {
            case PENDING, RETRYING -> Set.of(PROCESSING);
            case PROCESSING -> Set.of(COMPLETED, RETRYING, FAILED);
            case FAILED -> Set.of(PENDING);
            case COMPLETED -> Set.of();
        };
    }

    public boolean canTransitionTo(DeliveryStatus target) {
        return allowedTransitions().contains(target);
    }

    public DeliveryStatus requireTransitionTo(DeliveryStatus target) {
        if (!canTransitionTo(target)) {
            throw new IllegalStateException("Invalid delivery status transition " + this + " -> " + target);
        }
        return target;
    }
}
