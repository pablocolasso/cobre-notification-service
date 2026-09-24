package com.cobre.notification.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;

/**
 * Exponential backoff with full jitter: the delay is uniform in {@code [0, min(cap, base * 2^(n-1))]}. A
 * {@code Retry-After} hint raises the delay to at least the hint, bounded by the cap.
 */
public class ExponentialBackoffRetryPolicy implements RetryPolicy {

    private final long baseMillis;
    private final long capMillis;
    private final int maxAttempts;
    private final RandomGenerator random;

    public ExponentialBackoffRetryPolicy(Duration base, Duration cap, int maxAttempts, RandomGenerator random) {
        if (base.isNegative() || base.isZero()) {
            throw new IllegalArgumentException("base must be positive");
        }
        if (cap.compareTo(base) < 0) {
            throw new IllegalArgumentException("cap must be >= base");
        }
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.baseMillis = base.toMillis();
        this.capMillis = cap.toMillis();
        this.maxAttempts = maxAttempts;
        this.random = Objects.requireNonNull(random, "random");
    }

    @Override
    public Optional<Instant> nextAttemptAt(int cycleAttemptNumber, Instant now, Duration retryAfter) {
        if (cycleAttemptNumber >= maxAttempts) {
            return Optional.empty();
        }
        long delay = random.nextLong(ceilingMillis(cycleAttemptNumber) + 1);
        if (retryAfter != null && !retryAfter.isNegative()) {
            delay = Math.max(delay, Math.min(retryAfter.toMillis(), capMillis));
        }
        return Optional.of(now.plusMillis(delay));
    }

    @Override
    public int maxAttempts() {
        return maxAttempts;
    }

    long ceilingMillis(int cycleAttemptNumber) {
        int exponent = Math.max(0, cycleAttemptNumber - 1);
        if (exponent >= Long.numberOfLeadingZeros(baseMillis) - 1) {
            return capMillis;
        }
        return Math.min(capMillis, baseMillis << exponent);
    }
}
