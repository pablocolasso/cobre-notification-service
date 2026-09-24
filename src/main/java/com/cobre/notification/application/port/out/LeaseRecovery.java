package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryDecision;

import java.time.Instant;

/**
 * Applied only if the lease is still the expired one that was read (same holder, same attempt, still expired).
 */
public record LeaseRecovery(ExpiredLease lease, DeliveryDecision decision, Instant now) {
}
