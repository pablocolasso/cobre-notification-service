package com.cobre.notification.domain.model;

import java.time.Duration;

public sealed interface DeliveryResult {

    /**
     * @param retryAfter the receiver's {@code Retry-After} hint, or {@code null} when absent or unparseable.
     */
    record HttpResponseReceived(int statusCode, Duration retryAfter) implements DeliveryResult {

        public HttpResponseReceived(int statusCode) {
            this(statusCode, null);
        }
    }

    record TransportFailure(DeliveryError error) implements DeliveryResult {
    }

    /**
     * The destination was rejected before any HTTP call (SSRF, bad scheme, unresolvable host).
     */
    record InvalidDestination(DeliveryError error) implements DeliveryResult {
    }
}
