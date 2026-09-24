package com.cobre.notification.domain.policy;

import java.util.random.RandomGenerator;

/**
 * Deterministic jitter: {@code nextLong(bound)} returns {@code fraction * (bound - 1)} and remembers the bound.
 */
final class FixedRandom implements RandomGenerator {

    private final double fraction;
    long lastBound = -1;

    private FixedRandom(double fraction) {
        this.fraction = fraction;
    }

    static FixedRandom lowest() {
        return new FixedRandom(0.0);
    }

    static FixedRandom highest() {
        return new FixedRandom(1.0);
    }

    static FixedRandom fraction(double fraction) {
        return new FixedRandom(fraction);
    }

    @Override
    public long nextLong() {
        throw new UnsupportedOperationException("only bounded values are used");
    }

    @Override
    public long nextLong(long bound) {
        lastBound = bound;
        return (long) (fraction * (bound - 1));
    }
}
