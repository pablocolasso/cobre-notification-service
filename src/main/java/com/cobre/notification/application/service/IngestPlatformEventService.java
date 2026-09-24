package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.IngestPlatformEventUseCase;
import com.cobre.notification.application.port.in.IngestionResult;
import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.PlatformEvent;
import com.cobre.notification.domain.model.Subscription;

import java.time.Clock;
import java.util.Optional;

public class IngestPlatformEventService implements IngestPlatformEventUseCase {

    private final SubscriptionRepository subscriptions;
    private final NotificationEventRepository notifications;
    private final IdGenerator ids;
    private final Clock clock;

    public IngestPlatformEventService(SubscriptionRepository subscriptions,
                                      NotificationEventRepository notifications,
                                      IdGenerator ids,
                                      Clock clock) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.ids = ids;
        this.clock = clock;
    }

    @Override
    public IngestionResult ingest(PlatformEvent event) {
        Optional<Subscription> subscription = subscriptions.findActive(event.clientId(), event.eventType());
        if (subscription.isEmpty()) {
            return IngestionResult.NO_SUBSCRIPTION;
        }

        NotificationEvent notification =
                NotificationEvent.pendingFrom(ids.newId(), event, subscription.get(), clock.instant());

        return notifications.saveIfAbsent(notification) ? IngestionResult.ACCEPTED : IngestionResult.DUPLICATE;
    }
}
