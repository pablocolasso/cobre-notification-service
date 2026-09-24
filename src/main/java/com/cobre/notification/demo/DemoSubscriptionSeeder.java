package com.cobre.notification.demo;

import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.demo.DemoProperties.DemoSubscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.URI;

/**
 * Upserts the demo subscriptions on startup so the destination can be changed with an env var and a restart.
 */
@Component
@Profile("demo")
@EnableConfigurationProperties(DemoProperties.class)
class DemoSubscriptionSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoSubscriptionSeeder.class);

    private final DemoProperties properties;
    private final SubscriptionRepository subscriptions;

    DemoSubscriptionSeeder(DemoProperties properties, SubscriptionRepository subscriptions) {
        this.properties = properties;
        this.subscriptions = subscriptions;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (DemoSubscription subscription : properties.subscriptions()) {
            String webhookUrl = subscription.webhookUrl() != null ? subscription.webhookUrl() : properties.webhookUrl();
            if (webhookUrl == null || webhookUrl.isBlank()) {
                throw new IllegalStateException("No webhook URL for demo subscription "
                        + subscription.clientId() + "/" + subscription.eventType());
            }
            subscriptions.upsertActive(subscription.clientId(), subscription.eventType(), webhookUrl);
            log.atInfo()
                    .setMessage("Demo subscription upserted")
                    .addKeyValue("client_id", subscription.clientId())
                    .addKeyValue("event_type", subscription.eventType())
                    .addKeyValue("webhook_host", URI.create(webhookUrl).getHost())
                    .log();
        }
    }
}
