package com.cobre.notification.domain.model;

public sealed interface DeliveryResult {

    record HttpResponseReceived(int statusCode) implements DeliveryResult {
    }

    record TransportFailure(DeliveryError error) implements DeliveryResult {
    }
}
