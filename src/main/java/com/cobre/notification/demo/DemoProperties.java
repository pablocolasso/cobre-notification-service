package com.cobre.notification.demo;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * @param webhookUrl     default destination for subscriptions that do not set their own.
 * @param signingSecret  default HMAC secret ({@code WEBHOOK_SIGNING_SECRET}); a per-subscription value wins.
 */
@ConfigurationProperties("app.demo")
public record DemoProperties(String webhookUrl, String signingSecret, @DefaultValue List<DemoSubscription> subscriptions) {

    public record DemoSubscription(String clientId, String eventType, String webhookUrl, String signingSecret) {
    }
}
