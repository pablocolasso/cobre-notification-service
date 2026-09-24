package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@ConfigurationProperties("app.webhook")
public record WebhookProperties(
        @DefaultValue("2s") Duration connectTimeout,
        @DefaultValue("5s") Duration requestTimeout,
        @DefaultValue Ssrf ssrf) {

    /**
     * @param extraAllowedPorts added to the default set (443 and any port &gt; 1023).
     * @param extraAllowedHosts comma-separated hosts from {@code WEBHOOK_SSRF_ALLOWED_HOSTS}, merged into
     *                          {@code allowedHosts} so a presentation URL can be allowlisted without replacing
     *                          the local/demo list.
     */
    public record Ssrf(
            @DefaultValue("true") boolean strict,
            @DefaultValue List<String> allowedHosts,
            @DefaultValue List<Integer> extraAllowedPorts,
            @DefaultValue List<String> extraAllowedHosts) {

        public Ssrf {
            extraAllowedPorts = extraAllowedPorts == null ? List.of() : List.copyOf(extraAllowedPorts);
            allowedHosts = mergeHosts(allowedHosts, extraAllowedHosts);
        }

        private static List<String> mergeHosts(List<String> allowed, List<String> extra) {
            List<String> merged = new ArrayList<>();
            appendHosts(merged, allowed);
            appendHosts(merged, extra);
            return List.copyOf(merged);
        }

        private static void appendHosts(List<String> into, List<String> raw) {
            if (raw == null) {
                return;
            }
            for (String value : raw) {
                if (value == null) {
                    continue;
                }
                Arrays.stream(value.split(","))
                        .map(String::trim)
                        .filter(host -> !host.isEmpty())
                        .forEach(into::add);
            }
        }
    }
}
