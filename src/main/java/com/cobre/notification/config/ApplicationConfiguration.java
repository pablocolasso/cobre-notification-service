package com.cobre.notification.config;

import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.application.service.IngestPlatformEventService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the framework-free application services.
 */
@Configuration(proxyBeanMethods = false)
class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IngestPlatformEventService ingestPlatformEventService(SubscriptionRepository subscriptions,
                                                          NotificationEventRepository notifications,
                                                          Clock clock) {
        return new IngestPlatformEventService(subscriptions, notifications, clock);
    }
}
