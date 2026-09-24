package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties("app.webhook")
public record WebhookProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout) {
}
