package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.InvalidDestination;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStatusDeliveryResultClassifierTest {

    private final HttpStatusDeliveryResultClassifier classifier = new HttpStatusDeliveryResultClassifier();

    @ParameterizedTest(name = "HTTP {0} -> {1}")
    @CsvSource({
            "200, SUCCESS",
            "201, SUCCESS",
            "202, SUCCESS",
            "204, SUCCESS",
            "299, SUCCESS",
            "408, RETRYABLE_FAILURE",
            "429, RETRYABLE_FAILURE",
            "500, RETRYABLE_FAILURE",
            "502, RETRYABLE_FAILURE",
            "503, RETRYABLE_FAILURE",
            "504, RETRYABLE_FAILURE",
            "599, RETRYABLE_FAILURE",
            "100, PERMANENT_FAILURE",
            "301, PERMANENT_FAILURE",
            "302, PERMANENT_FAILURE",
            "307, PERMANENT_FAILURE",
            "400, PERMANENT_FAILURE",
            "401, PERMANENT_FAILURE",
            "403, PERMANENT_FAILURE",
            "404, PERMANENT_FAILURE",
            "405, PERMANENT_FAILURE",
            "409, PERMANENT_FAILURE",
            "410, PERMANENT_FAILURE",
            "413, PERMANENT_FAILURE",
            "422, PERMANENT_FAILURE",
            "600, PERMANENT_FAILURE"
    })
    void classifiesHttpStatus(int statusCode, DeliveryOutcome expected) {
        assertThat(classifier.classify(new HttpResponseReceived(statusCode))).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"connect_timeout", "timeout", "connection_failed", "network_error", "interrupted",
            "unexpected_error"})
    void transportFailuresAreRetryable(String code) {
        assertThat(classifier.classify(new TransportFailure(new DeliveryError(code, "x"))))
                .isEqualTo(DeliveryOutcome.RETRYABLE_FAILURE);
    }

    @ParameterizedTest
    @ValueSource(strings = {DeliveryError.INVALID_DESTINATION})
    void invalidDestinationIsPermanent(String code) {
        assertThat(classifier.classify(new TransportFailure(new DeliveryError(code, "x"))))
                .isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }

    @Test
    void invalidDestinationResultIsPermanent() {
        assertThat(classifier.classify(new InvalidDestination(new DeliveryError(DeliveryError.INVALID_DESTINATION, "blocked_address"))))
                .isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }
}
