package com.cobre.notification.application.port.in;

/**
 * Identity derived from the API key. Tenant scoping uses this, never a client-supplied id as authority.
 */
public sealed interface Requester {

    String keyName();

    record Client(String keyName, String clientId) implements Requester {

        public Client {
            if (keyName == null || keyName.isBlank()) {
                throw new IllegalArgumentException("keyName is required");
            }
            if (clientId == null || clientId.isBlank()) {
                throw new IllegalArgumentException("clientId is required for a client requester");
            }
        }
    }

    record Operator(String keyName) implements Requester {

        public Operator {
            if (keyName == null || keyName.isBlank()) {
                throw new IllegalArgumentException("keyName is required");
            }
        }
    }
}
