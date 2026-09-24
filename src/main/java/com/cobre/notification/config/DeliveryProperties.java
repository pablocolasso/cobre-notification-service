package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param leaseDuration must be well above the total webhook timeout so a live worker never loses its lease.
 */
@ConfigurationProperties("app.delivery")
public record DeliveryProperties(
        @DefaultValue("20") int batchSize,
        @DefaultValue("60s") Duration leaseDuration) {
}
