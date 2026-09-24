package com.cobre.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

@ConfigurationProperties("app.security")
public record SecurityProperties(@DefaultValue List<ApiKey> apiKeys) {

    public enum Role {
        CLIENT,
        OPERATOR
    }

    /**
     * @param clientId required for {@link Role#CLIENT}; ignored for {@link Role#OPERATOR}.
     */
    public record ApiKey(String name, String key, Role role, String clientId) {

        public ApiKey {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("API key name is required");
            }
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("API key value is required for " + name);
            }
            if (role == null) {
                throw new IllegalArgumentException("API key role is required for " + name);
            }
            if (role == Role.CLIENT && (clientId == null || clientId.isBlank())) {
                throw new IllegalArgumentException("client_id is required for CLIENT key " + name);
            }
            if (role == Role.OPERATOR) {
                clientId = null;
            }
        }
    }
}
