package com.cobre.notification.domain.policy;

import com.cobre.notification.domain.model.DeliveryOutcome;
import com.cobre.notification.domain.model.DeliveryResult;

public interface DeliveryResultClassifier {

    DeliveryOutcome classify(DeliveryResult result);
}
