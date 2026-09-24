package com.cobre.notification.adapter.out.metrics;

import com.cobre.notification.application.port.out.BacklogQuery;
import com.cobre.notification.application.port.out.BacklogSnapshot;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.concurrent.TimeUnit;

/**
 * Backlog gauges are refreshed on a timer, not on the delivery worker tick.
 */
@Component
public class BacklogMetricsBinder {

    static final String BACKLOG = "notification.backlog";
    static final String OLDEST_AGE = "notification.backlog.oldest.age.seconds";

    private final BacklogQuery backlogQuery;
    private final Clock clock;
    private volatile BacklogSnapshot snapshot = new BacklogSnapshot(0, 0, 0, 0);

    BacklogMetricsBinder(BacklogQuery backlogQuery, Clock clock, MeterRegistry registry) {
        this.backlogQuery = backlogQuery;
        this.clock = clock;
        Gauge.builder(BACKLOG, this, binder -> binder.snapshot.pending())
                .tag("status", "pending")
                .description("Notifications waiting for a first delivery attempt")
                .register(registry);
        Gauge.builder(BACKLOG, this, binder -> binder.snapshot.retrying())
                .tag("status", "retrying")
                .description("Notifications scheduled for another delivery attempt")
                .register(registry);
        Gauge.builder(BACKLOG, this, binder -> binder.snapshot.processing())
                .tag("status", "processing")
                .description("Notifications currently leased to a worker")
                .register(registry);
        Gauge.builder(OLDEST_AGE, this, binder -> binder.snapshot.oldestAgeSeconds())
                .description("Age in seconds of the oldest PENDING or RETRYING notification")
                .register(registry);
        refresh();
    }

    @Scheduled(fixedDelay = 15, timeUnit = TimeUnit.SECONDS)
    public void refresh() {
        snapshot = backlogQuery.snapshot(clock.instant());
    }

    BacklogSnapshot snapshot() {
        return snapshot;
    }
}
