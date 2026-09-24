package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Backoff for transient consumer failures. Retries are unlimited: the partition pauses instead of losing events.
 */
@ConfigurationProperties("app.kafka.consumer.retry")
public record KafkaRetryProperties(
        @DefaultValue("1s") Duration initialInterval,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("30s") Duration maxInterval) {
}
