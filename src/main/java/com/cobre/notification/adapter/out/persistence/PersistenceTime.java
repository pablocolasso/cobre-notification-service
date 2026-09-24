package com.cobre.notification.adapter.out.persistence;

import java.sql.Timestamp;
import java.time.Instant;

final class PersistenceTime {

    private PersistenceTime() {
    }

    static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
