package com.cobre.notification.application.port.in;

import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.NotificationEvent;

import java.util.List;

public record NotificationEventDetails(NotificationEvent notification, List<DeliveryAttempt> attempts) {
}
