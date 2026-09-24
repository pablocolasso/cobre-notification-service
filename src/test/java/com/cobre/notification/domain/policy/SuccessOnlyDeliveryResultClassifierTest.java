package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class SuccessOnlyDeliveryResultClassifierTest {

    private final SuccessOnlyDeliveryResultClassifier classifier = new SuccessOnlyDeliveryResultClassifier();

    @ParameterizedTest
    @ValueSource(ints = {200, 201, 202, 204, 299})
    void twoHundredsAreSuccess(int status) {
        assertThat(classifier.classify(new HttpResponseReceived(status))).isEqualTo(DeliveryOutcome.SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(ints = {199, 302, 400, 404, 408, 429, 500, 503})
    void everyOtherStatusIsPermanentFailure(int status) {
        assertThat(classifier.classify(new HttpResponseReceived(status))).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }

    @Test
    void transportFailureIsPermanentFailure() {
        var failure = new TransportFailure(new DeliveryError("timeout", "No response within 5000 ms"));

        assertThat(classifier.classify(failure)).isEqualTo(DeliveryOutcome.PERMANENT_FAILURE);
    }
}
