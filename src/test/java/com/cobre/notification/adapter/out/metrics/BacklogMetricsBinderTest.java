package com.cobre.notification.adapter.out.metrics;

import com.cobre.notification.application.port.out.BacklogQuery;
import com.cobre.notification.application.port.out.BacklogSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class BacklogMetricsBinderTest {

    private static final Instant NOW = Instant.parse("2026-09-24T18:00:00Z");

    @Test
    void gaugesReadTheCachedQueryNotAWorkerTick() {
        AtomicInteger queries = new AtomicInteger();
        BacklogQuery backlogQuery = now -> {
            queries.incrementAndGet();
            return new BacklogSnapshot(2, 3, 1, 42);
        };
        MeterRegistry registry = new SimpleMeterRegistry();

        var binder = new BacklogMetricsBinder(backlogQuery, Clock.fixed(NOW, ZoneOffset.UTC), registry);

        assertThat(queries.get()).isEqualTo(1);
        assertThat(registry.get(BacklogMetricsBinder.BACKLOG).tag("status", "pending").gauge().value()).isEqualTo(2);
        assertThat(registry.get(BacklogMetricsBinder.BACKLOG).tag("status", "retrying").gauge().value()).isEqualTo(3);
        assertThat(registry.get(BacklogMetricsBinder.BACKLOG).tag("status", "processing").gauge().value()).isEqualTo(1);
        assertThat(registry.get(BacklogMetricsBinder.OLDEST_AGE).gauge().value()).isEqualTo(42);

        assertThat(queries.get()).isEqualTo(1);

        binder.refresh();
        assertThat(queries.get()).isEqualTo(2);
    }
}
