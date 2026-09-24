package com.cobre.notification.adapter.out.webhook;

import java.time.Duration;

/**
 * Parses the delay-seconds form of {@code Retry-After}. The HTTP-date form is ignored (treated as absent), as is
 * anything malformed: the retry policy then falls back to its own backoff.
 */
final class RetryAfter {

    private static final int MAX_DIGITS = 9;

    private RetryAfter() {
    }

    static Duration parse(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (value.isEmpty() || value.length() > MAX_DIGITS || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return Duration.ofSeconds(Long.parseLong(value));
    }
}
