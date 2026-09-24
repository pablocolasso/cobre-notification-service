package com.cobre.notification.adapter.in.kafka;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;

@Component
public class DeadLetterMetrics {

    public static final String PUBLISHED = "kafka.dlt.published";

    private final Map<DeadLetterReason, Counter> published = new EnumMap<>(DeadLetterReason.class);

    DeadLetterMetrics(MeterRegistry registry) {
        for (DeadLetterReason reason : DeadLetterReason.values()) {
            published.put(reason, Counter.builder(PUBLISHED)
                    .description("Platform events sent to the dead-letter topic")
                    .tag("reason", reason.tag())
                    .register(registry));
        }
    }

    public void published(DeadLetterReason reason) {
        published.get(reason).increment();
    }
}
