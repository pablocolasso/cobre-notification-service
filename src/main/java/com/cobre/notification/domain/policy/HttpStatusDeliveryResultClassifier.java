package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.InvalidDestination;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;

/**
 * 2xx succeeds. 408, 429 and 5xx are retryable, as are timeouts and network errors. Every other status (1xx, 3xx
 * with redirects disabled, remaining 4xx) and an invalid destination are permanent: retrying would not help.
 */
public class HttpStatusDeliveryResultClassifier implements DeliveryResultClassifier {

    @Override
    public DeliveryOutcome classify(DeliveryResult result) {
        return switch (result) {
            case HttpResponseReceived response -> classifyStatus(response.statusCode());
            case InvalidDestination ignored -> DeliveryOutcome.PERMANENT_FAILURE;
            case TransportFailure failure when DeliveryError.INVALID_DESTINATION.equals(failure.error().code()) ->
                    DeliveryOutcome.PERMANENT_FAILURE;
            case TransportFailure ignored -> DeliveryOutcome.RETRYABLE_FAILURE;
        };
    }

    private static DeliveryOutcome classifyStatus(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return DeliveryOutcome.SUCCESS;
        }
        if (statusCode == 408 || statusCode == 429 || (statusCode >= 500 && statusCode < 600)) {
            return DeliveryOutcome.RETRYABLE_FAILURE;
        }
        return DeliveryOutcome.PERMANENT_FAILURE;
    }
}
