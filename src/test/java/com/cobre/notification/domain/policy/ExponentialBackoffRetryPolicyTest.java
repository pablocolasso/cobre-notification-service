package com.cobre.notification.domain.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.random.RandomGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExponentialBackoffRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(5);
    private static final Duration CAP = Duration.ofMinutes(10);

    /** Always draws the upper bound of the jitter range, exposing the exponential ceiling. */
    private static final RandomGenerator HIGHEST = new FixedRandom(Long.MAX_VALUE);
    private static final RandomGenerator LOWEST = new FixedRandom(0);

    @ParameterizedTest(name = "attempt {0} -> ceiling {1}ms")
    @CsvSource({
            "1, 5000",
            "2, 10000",
            "3, 20000",
            "4, 40000",
            "7, 320000",
            "8, 600000",
            "9, 600000",
            "60, 600000"
    })
    void ceilingGrowsExponentiallyUpToTheCap(int attempt, long expectedCeilingMillis) {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 100, HIGHEST);

        assertThat(policy.nextAttemptAt(attempt, NOW, null)).contains(NOW.plusMillis(expectedCeilingMillis));
    }

    @Test
    void fullJitterCanDrawZeroDelay() {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 5, LOWEST);

        assertThat(policy.nextAttemptAt(3, NOW, null)).contains(NOW);
    }

    @Test
    void jitterStaysWithinZeroAndTheCeiling() {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 5, RandomGenerator.of("L64X128MixRandom"));

        for (int i = 0; i < 1_000; i++) {
            Instant next = policy.nextAttemptAt(3, NOW, null).orElseThrow();
            assertThat(next).isBetween(NOW, NOW.plusSeconds(20));
        }
    }

    @Test
    void stopsWhenTheAttemptBudgetIsExhausted() {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 5, HIGHEST);

        assertThat(policy.nextAttemptAt(4, NOW, null)).isPresent();
        assertThat(policy.nextAttemptAt(5, NOW, null)).isEmpty();
        assertThat(policy.nextAttemptAt(6, NOW, null)).isEmpty();
        assertThat(policy.maxAttempts()).isEqualTo(5);
    }

    @Test
    void retryAfterRaisesTheDelayButIsBoundedByTheCap() {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 5, LOWEST);

        assertThat(policy.nextAttemptAt(1, NOW, Duration.ofSeconds(30))).contains(NOW.plusSeconds(30));
        assertThat(policy.nextAttemptAt(1, NOW, Duration.ofHours(2))).contains(NOW.plus(CAP));
    }

    @Test
    void retryAfterShorterThanTheJitteredDelayIsIgnored() {
        var policy = new ExponentialBackoffRetryPolicy(BASE, CAP, 5, HIGHEST);

        assertThat(policy.nextAttemptAt(2, NOW, Duration.ofSeconds(1))).contains(NOW.plusSeconds(10));
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(Duration.ZERO, CAP, 5, LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(CAP, BASE, 5, LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialBackoffRetryPolicy(BASE, CAP, 0, LOWEST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Returns {@code min(value, bound - 1)} for bounded draws.
     */
    private record FixedRandom(long value) implements RandomGenerator {

        @Override
        public long nextLong() {
            return value;
        }

        @Override
        public long nextLong(long bound) {
            return Math.min(value, bound - 1);
        }
    }
}
