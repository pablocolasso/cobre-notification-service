package com.cobre.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeliveryStatusTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PENDING, PROCESSING",
            "RETRYING, PROCESSING",
            "PROCESSING, COMPLETED",
            "PROCESSING, RETRYING",
            "PROCESSING, FAILED",
            "FAILED, PENDING"
    })
    void allowsLifecycleTransitions(DeliveryStatus from, DeliveryStatus to) {
        assertThat(from.canTransitionTo(to)).isTrue();
        assertThat(from.requireTransitionTo(to)).isEqualTo(to);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "PENDING, COMPLETED",
            "PENDING, FAILED",
            "PENDING, RETRYING",
            "RETRYING, COMPLETED",
            "RETRYING, FAILED",
            "PROCESSING, PENDING",
            "PROCESSING, PROCESSING",
            "COMPLETED, PENDING",
            "COMPLETED, PROCESSING",
            "COMPLETED, FAILED",
            "FAILED, PROCESSING",
            "FAILED, COMPLETED"
    })
    void rejectsInvalidTransitions(DeliveryStatus from, DeliveryStatus to) {
        assertThat(from.canTransitionTo(to)).isFalse();
        assertThatThrownBy(() -> from.requireTransitionTo(to))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid delivery status transition " + from + " -> " + to);
    }

    @Test
    void decisionCannotLeaveProcessingTowardsANonTerminalStartState() {
        assertThatThrownBy(() -> new DeliveryDecision(DeliveryStatus.PENDING, AttemptStatus.ABANDONED, null, null,
                null, NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decisionEnforcesTheSchemaInvariants() {
        assertThatThrownBy(() -> new DeliveryDecision(DeliveryStatus.COMPLETED, AttemptStatus.SUCCESS, null, null,
                null, null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deliveredAt");
        assertThatThrownBy(() -> new DeliveryDecision(DeliveryStatus.RETRYING, AttemptStatus.RETRYABLE_FAILURE,
                null, null, null, null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("nextAttemptAt");
        assertThatThrownBy(() -> new DeliveryDecision(DeliveryStatus.FAILED, AttemptStatus.PERMANENT_FAILURE,
                null, null, null, NOW)).isInstanceOf(IllegalArgumentException.class);
    }
}
