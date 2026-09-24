package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param leaseDuration must be well above the total webhook timeout (plus clock skew, see ADR-003) so a live worker
 *                      never loses its lease.
 */
@ConfigurationProperties("app.delivery")
public record DeliveryProperties(
        @DefaultValue("60s") Duration leaseDuration,
        @DefaultValue Worker worker,
        @DefaultValue Retry retry) {

    /**
     * @param id                 unique per process; generated from the hostname when blank.
     * @param maxConcurrency     in-flight deliveries per instance; also the maximum claim size.
     * @param recoveryBatchSize  expired leases recovered per tick.
     */
    public record Worker(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("20") int maxConcurrency,
            @DefaultValue("100") int recoveryBatchSize,
            String id) {
    }

    public record Retry(
            @DefaultValue("5s") Duration baseDelay,
            @DefaultValue("10m") Duration maxDelay,
            @DefaultValue("5") int maxAttempts) {
    }
}
