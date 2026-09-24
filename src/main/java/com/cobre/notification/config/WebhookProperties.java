package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties("app.webhook")
public record WebhookProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue Ssrf ssrf) {

    /**
     * @param extraAllowedPorts added to the default set (443 and any port &gt; 1023).
     */
    public record Ssrf(
            @DefaultValue("true") boolean strict,
            @DefaultValue List<String> allowedHosts,
            @DefaultValue List<Integer> extraAllowedPorts) {

        public Ssrf {
            allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
            extraAllowedPorts = extraAllowedPorts == null ? List.of() : List.copyOf(extraAllowedPorts);
        }
    }
}
