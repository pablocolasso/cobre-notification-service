package com.cobre.notification.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param webhookUrl default destination for subscriptions that do not set their own.
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(String webhookUrl, @DefaultValue List<DemoSubscription> subscriptions) {

    public record DemoSubscription(String clientId, String eventType, String webhookUrl, String signingSecret) {
    }
}
