package com.cobre.notification.demo;

import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.demo.DemoProperties.DemoSubscription;
import com.cobre.notification.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoSubscriptionSeederTest {

    private final RecordingSubscriptions subscriptions = new RecordingSubscriptions();

    @Test
    void upsertsEverySubscriptionUsingTheDefaultUrlUnlessOverridden() {
        var properties = new DemoProperties("https://default.example/hook", List.of(
                new DemoSubscription("CLIENT001", "credit_card_payment", null, null),
                new DemoSubscription("CLIENT003", "credit_cashback", "https://failing.example/hook", "s3cret")));

        new DemoSubscriptionSeeder(properties, subscriptions, UUID::randomUUID).run(null);

        assertThat(subscriptions.upserts).containsExactly(
                "CLIENT001|credit_card_payment|https://default.example/hook|null",
                "CLIENT003|credit_cashback|https://failing.example/hook|s3cret");
    }

    @Test
    void failsFastWhenNoUrlIsConfigured() {
        var properties = new DemoProperties(" ", List.of(new DemoSubscription("CLIENT001", "credit_card_payment", null, null)));

        assertThatThrownBy(() -> new DemoSubscriptionSeeder(properties, subscriptions, UUID::randomUUID).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLIENT001/credit_card_payment");
        assertThat(subscriptions.upserts).isEmpty();
    }

    private static final class RecordingSubscriptions implements SubscriptionRepository {

        final List<String> upserts = new ArrayList<>();

        @Override
        public Optional<Subscription> findActive(String clientId, String eventType) {
            return Optional.empty();
        }

        @Override
        public void upsertActive(UUID id, String clientId, String eventType, String webhookUrl, String signingSecret) {
            upserts.add(clientId + "|" + eventType + "|" + webhookUrl + "|" + signingSecret);
        }
    }
}
