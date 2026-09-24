package com.cobre.notification.application.port.out;

import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryTask;

public interface WebhookClient {

    /**
     * Performs a single delivery attempt. Never throws for HTTP or network problems; they are reported as results.
     */
    DeliveryResult deliver(DeliveryTask task);
}
