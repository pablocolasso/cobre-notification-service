package com.cobre.notification.domain.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

public interface RetryPolicy {

    /**
     * @param cycleAttemptNumber attempts made in the current delivery cycle, including the one that just failed.
     * @param retryAfter         receiver hint, or {@code null}.
     * @return when to try again, or empty when the attempt budget is exhausted.
     */
    Optional<Instant> nextAttemptAt(int cycleAttemptNumber, Instant now, Duration retryAfter);

    int maxAttempts();
}
