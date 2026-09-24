package com.cobre.notification.config;

import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.application.port.out.WebhookClient;
import com.cobre.notification.application.service.DeliverNotificationService;
import com.cobre.notification.application.service.IngestPlatformEventService;
import com.cobre.notification.application.service.NotificationQueryService;
import com.cobre.notification.domain.policy.DeliveryResultClassifier;
import com.cobre.notification.domain.policy.SuccessOnlyDeliveryResultClassifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.UUID;

/**
 * Wires the framework-free application services.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({DeliveryProperties.class, WebhookProperties.class})
class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    IdGenerator idGenerator() {
        return UUID::randomUUID;
    }

    @Bean
    IngestPlatformEventService ingestPlatformEventService(SubscriptionRepository subscriptions,
                                                          NotificationEventRepository notifications,
                                                          IdGenerator ids,
                                                          Clock clock) {
        return new IngestPlatformEventService(subscriptions, notifications, ids, clock);
    }

    @Bean
    NotificationQueryService notificationQueryService(NotificationEventQueryRepository queries) {
        return new NotificationQueryService(queries);
    }

    @Bean
    DeliveryResultClassifier deliveryResultClassifier() {
        return new SuccessOnlyDeliveryResultClassifier();
    }

    @Bean
    DeliverNotificationService deliverNotificationService(DeliveryRepository deliveries,
                                                          WebhookClient webhookClient,
                                                          DeliveryResultClassifier classifier,
                                                          Clock clock,
                                                          DeliveryProperties properties,
                                                          @Value("${app.delivery.worker-id:}") String workerId) {
        return new DeliverNotificationService(
                deliveries,
                webhookClient,
                classifier,
                clock,
                workerId.isBlank() ? defaultWorkerId() : workerId,
                properties.batchSize(),
                properties.leaseDuration());
    }

    /**
     * Unique per process, so a restarted instance never inherits the leases of its previous run.
     */
    private static String defaultWorkerId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            host = "unknown-host";
        }
        return host + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
