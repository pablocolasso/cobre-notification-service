package com.cobre.notification.domain.model;

public enum AttemptTrigger {
    INITIAL,
    RETRY,
    REPLAY;

    public static AttemptTrigger of(int cycleAttemptCount, int replayCount) {
        if (cycleAttemptCount > 1) {
            return RETRY;
        }
        return replayCount > 0 ? REPLAY : INITIAL;
    }
}
