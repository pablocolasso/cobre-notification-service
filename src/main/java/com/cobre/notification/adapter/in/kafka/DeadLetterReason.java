package com.cobre.notification.adapter.in.kafka;

/**
 * Closed set of reasons a platform event is dead-lettered; safe as a metric tag.
 */
public enum DeadLetterReason {

    INVALID_EVENT,
    UNEXPECTED_ERROR;

    public static DeadLetterReason of(Throwable failure) {
        return findInvalidEvent(failure) != null ? INVALID_EVENT : UNEXPECTED_ERROR;
    }

    /**
     * Stable, payload-free description for the DLT header: the validation reason for invalid events, the
     * exception type otherwise.
     */
    public static String detail(Throwable failure) {
        InvalidPlatformEventException invalid = findInvalidEvent(failure);
        if (invalid != null) {
            return invalid.reason();
        }
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    public String tag() {
        return name().toLowerCase();
    }

    private static InvalidPlatformEventException findInvalidEvent(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof InvalidPlatformEventException invalid) {
                return invalid;
            }
            if (current.getCause() == current) {
                return null;
            }
        }
        return null;
    }
}
