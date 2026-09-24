package com.cobre.notification.config;

import com.cobre.notification.adapter.in.kafka.InvalidPlatformEventException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    /**
     * Transient failures (e.g. PostgreSQL down) are retried without limit so no event is lost; the partition stalls
     * and consumer lag grows. Invalid events are skipped immediately (dead-lettering arrives in Phase 2).
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(backOff);
        errorHandler.addNotRetryableExceptions(InvalidPlatformEventException.class);
        return errorHandler;
    }
}
