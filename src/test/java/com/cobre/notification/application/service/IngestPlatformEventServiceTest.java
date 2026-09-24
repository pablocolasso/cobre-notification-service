package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.IngestionResult;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.NotificationOrigin;
import com.cobre.notification.domain.model.PlatformEvent;
import com.cobre.notification.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class IngestPlatformEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-23T12:00:00Z");

    private final InMemorySubscriptions subscriptions = new InMemorySubscriptions();
    private final InMemoryNotifications notifications = new InMemoryNotifications();
    private static final UUID GENERATED_ID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    private final IngestPlatformEventService service = new IngestPlatformEventService(
            subscriptions, notifications, () -> GENERATED_ID, Clock.fixed(NOW, ZoneOffset.UTC),
            com.cobre.notification.application.port.out.NoOpNotificationMetrics.INSTANCE);

    @Test
    void createsPendingNotificationWithWebhookSnapshotWhenSubscriptionIsActive() {
        Subscription subscription = subscriptions.add("CLIENT001", "credit_card_payment", "https://client.example/hook");

        IngestionResult result = service.ingest(event("EVT101", "CLIENT001", "credit_card_payment"));

        assertThat(result).isEqualTo(IngestionResult.ACCEPTED);
        assertThat(notifications.saved).singleElement().satisfies(notification -> {
            assertThat(notification.id()).isEqualTo(GENERATED_ID);
            assertThat(notification.status()).isEqualTo(DeliveryStatus.PENDING);
            assertThat(notification.nextAttemptAt()).isEqualTo(NOW);
            assertThat(notification.webhookUrl()).isEqualTo("https://client.example/hook");
            assertThat(notification.subscriptionId()).isEqualTo(subscription.id());
            assertThat(notification.clientId()).isEqualTo("CLIENT001");
            assertThat(notification.eventCreatedAt()).isEqualTo(Instant.parse("2026-09-23T11:59:00Z"));
            assertThat(notification.attemptCount()).isZero();
            assertThat(notification.origin()).isEqualTo(NotificationOrigin.KAFKA);
        });
    }

    @Test
    void skipsEventWithoutActiveSubscription() {
        subscriptions.add("CLIENT001", "credit_card_payment", "https://client.example/hook");

        IngestionResult result = service.ingest(event("EVT102", "CLIENT001", "debit_transfer"));

        assertThat(result).isEqualTo(IngestionResult.NO_SUBSCRIPTION);
        assertThat(notifications.saved).isEmpty();
    }

    @Test
    void doesNotMatchSubscriptionOfAnotherClient() {
        subscriptions.add("CLIENT002", "credit_card_payment", "https://other.example/hook");

        IngestionResult result = service.ingest(event("EVT103", "CLIENT001", "credit_card_payment"));

        assertThat(result).isEqualTo(IngestionResult.NO_SUBSCRIPTION);
        assertThat(notifications.saved).isEmpty();
    }

    @Test
    void reportsDuplicateEventWithoutCreatingSecondNotification() {
        subscriptions.add("CLIENT001", "credit_card_payment", "https://client.example/hook");

        service.ingest(event("EVT104", "CLIENT001", "credit_card_payment"));
        IngestionResult second = service.ingest(event("EVT104", "CLIENT001", "credit_card_payment"));

        assertThat(second).isEqualTo(IngestionResult.DUPLICATE);
        assertThat(notifications.saved).hasSize(1);
    }

    private static PlatformEvent event(String eventId, String clientId, String eventType) {
        return new PlatformEvent(eventId, eventType, clientId, Instant.parse("2026-09-23T11:59:00Z"), "content", 1);
    }

    private static final class InMemorySubscriptions implements SubscriptionRepository {

        private final Map<String, Subscription> byKey = new HashMap<>();

        Subscription add(String clientId, String eventType, String webhookUrl) {
            Subscription subscription = new Subscription(UUID.randomUUID(), clientId, eventType, webhookUrl, true);
            byKey.put(clientId + "|" + eventType, subscription);
            return subscription;
        }

        @Override
        public Optional<Subscription> findActive(String clientId, String eventType) {
            return Optional.ofNullable(byKey.get(clientId + "|" + eventType));
        }

        @Override
        public void upsertActive(UUID id, String clientId, String eventType, String webhookUrl, String signingSecret) {
            add(clientId, eventType, webhookUrl);
        }
    }

    private static final class InMemoryNotifications implements NotificationEventRepository {

        private final List<NotificationEvent> saved = new ArrayList<>();

        @Override
        public boolean saveIfAbsent(NotificationEvent notification) {
            if (saved.stream().anyMatch(existing -> existing.eventId().equals(notification.eventId()))) {
                return false;
            }
            return saved.add(notification);
        }

        @Override
        public boolean saveAttemptIfAbsent(com.cobre.notification.domain.model.DeliveryAttempt attempt) {
            return true;
        }

        @Override
        public boolean requestReplay(com.cobre.notification.application.port.out.ReplayCommand command) {
            return false;
        }
    }
}
