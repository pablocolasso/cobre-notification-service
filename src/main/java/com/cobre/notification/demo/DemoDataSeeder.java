package com.cobre.notification.demo;

import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.AttemptStatus;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.DeliveryError;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.NotificationOrigin;
import com.cobre.notification.domain.model.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Seeds {@code notification_events.json} as already-delivered history (A2), not as platform events.
 */
@Component
@Profile("demo")
@Order(2)
class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String FIXTURE_RESOURCE = "demo/notification_events.json";

    private final SubscriptionRepository subscriptions;
    private final NotificationEventRepository notifications;
    private final IdGenerator ids;
    private final ObjectMapper objectMapper;

    DemoDataSeeder(SubscriptionRepository subscriptions, NotificationEventRepository notifications, IdGenerator ids,
                   ObjectMapper objectMapper) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.ids = ids;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        int inserted = 0;
        for (FixtureEvent fixture : load().events()) {
            if (seed(fixture)) {
                inserted++;
            }
        }
        log.atInfo()
                .setMessage("Demo fixture notifications seeded")
                .addKeyValue("inserted", inserted)
                .addKeyValue("source", FIXTURE_RESOURCE)
                .log();
    }

    boolean seed(FixtureEvent fixture) {
        Subscription subscription = subscriptions.findActive(fixture.clientId(), fixture.eventType())
                .orElseThrow(() -> new IllegalStateException(
                        "No active subscription for fixture " + fixture.eventId() + " "
                                + fixture.clientId() + "/" + fixture.eventType()));
        Instant at = fixture.deliveryDate();
        DeliveryStatus status = DeliveryStatus.valueOf(fixture.deliveryStatus().toUpperCase(Locale.ROOT));
        boolean failed = status == DeliveryStatus.FAILED;
        UUID notificationId = ids.newId();
        NotificationEvent notification = new NotificationEvent(
                notificationId,
                fixture.eventId(),
                subscription.id(),
                fixture.clientId(),
                fixture.eventType(),
                fixture.content(),
                at,
                subscription.webhookUrl(),
                status,
                1,
                1,
                0,
                null,
                at,
                status == DeliveryStatus.COMPLETED ? at : null,
                null,
                failed ? new DeliveryError(DeliveryError.FIXTURE_SYNTHETIC, null).summary() : null,
                NotificationOrigin.FIXTURE,
                at,
                at);
        if (!notifications.saveIfAbsent(notification)) {
            return false;
        }
        notifications.saveAttemptIfAbsent(new DeliveryAttempt(
                ids.newId(),
                notificationId,
                1,
                AttemptTrigger.INITIAL,
                subscription.webhookUrl(),
                failed ? AttemptStatus.PERMANENT_FAILURE : AttemptStatus.SUCCESS,
                null,
                DeliveryError.FIXTURE_SYNTHETIC,
                null,
                at,
                at,
                null));
        log.atInfo()
                .setMessage("Fixture notification seeded")
                .addKeyValue("event_id", fixture.eventId())
                .addKeyValue("client_id", fixture.clientId())
                .addKeyValue("event_type", fixture.eventType())
                .addKeyValue("delivery_status", status.name().toLowerCase(Locale.ROOT))
                .log();
        return true;
    }

    private FixtureFile load() throws IOException {
        ClassPathResource resource = new ClassPathResource(FIXTURE_RESOURCE);
        try (InputStream in = resource.getInputStream()) {
            return objectMapper.readValue(in, FixtureFile.class);
        }
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record FixtureFile(List<FixtureEvent> events) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    record FixtureEvent(
            String eventId,
            String eventType,
            String content,
            Instant deliveryDate,
            String deliveryStatus,
            String clientId) {

        @Override
        public String toString() {
            return "FixtureEvent[eventId=%s, eventType=%s, deliveryDate=%s, deliveryStatus=%s, clientId=%s]".formatted(eventId, eventType, deliveryDate, deliveryStatus, clientId);
        }
    }
}
