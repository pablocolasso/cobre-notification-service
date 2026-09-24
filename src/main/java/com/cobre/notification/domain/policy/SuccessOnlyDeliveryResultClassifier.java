package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;

/**
 * 2xx is a success; anything else is a permanent failure. No outcome is ever retryable.
 */
public class SuccessOnlyDeliveryResultClassifier implements DeliveryResultClassifier {

    @Override
    public DeliveryOutcome classify(DeliveryResult result) {
        return switch (result) {
            case HttpResponseReceived response when isSuccessful(response.statusCode()) -> DeliveryOutcome.SUCCESS;
            case HttpResponseReceived ignored -> DeliveryOutcome.PERMANENT_FAILURE;
            case TransportFailure ignored -> DeliveryOutcome.PERMANENT_FAILURE;
        };
    }

    private static boolean isSuccessful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }
}
