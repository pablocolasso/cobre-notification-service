package com.cobre.notification;

import com.cobre.notification.application.port.out.SubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class IngestionIntegrationTest extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "CLIENT001";
    private static final String EVENT_TYPE = "credit_card_payment";
    private static final String WEBHOOK_URL = "https://client.example/hook";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    JdbcClient jdbcClient;

    @Value("${app.kafka.topics.platform-events}")
    String topic;

    @BeforeEach
    void subscribe() {
        subscriptions.upsertActive(CLIENT_ID, EVENT_TYPE, WEBHOOK_URL);
    }

    @Test
    void persistsSubscribedEventAsPendingAndDue() {
        String eventId = uniqueEventId();

        publish(eventId, CLIENT_ID, EVENT_TYPE);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(eventId)).isEqualTo(1));
        Map<String, Object> row = jdbcClient.sql("""
                        SELECT delivery_status, next_attempt_at, webhook_url, attempt_count, origin
                        FROM notification_events WHERE event_id = :eventId
                        """)
                .param("eventId", eventId)
                .query()
                .singleRow();
        assertThat(row.get("delivery_status")).isEqualTo("PENDING");
        assertThat(row.get("next_attempt_at")).isNotNull();
        assertThat(row.get("webhook_url")).isEqualTo(WEBHOOK_URL);
        assertThat(row.get("attempt_count")).isEqualTo(0);
        assertThat(row.get("origin")).isEqualTo("KAFKA");
    }

    @Test
    void duplicateEventCreatesSingleNotification() {
        String eventId = uniqueEventId();
        String marker = uniqueEventId();

        publish(eventId, CLIENT_ID, EVENT_TYPE);
        publish(eventId, CLIENT_ID, EVENT_TYPE);
        publish(marker, CLIENT_ID, EVENT_TYPE);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));
        assertThat(countByEventId(eventId)).isEqualTo(1);
    }

    @Test
    void eventWithoutSubscriptionIsNotPersisted() {
        String unsubscribed = uniqueEventId();
        String marker = uniqueEventId();

        publish(unsubscribed, CLIENT_ID, "unsubscribed_type");
        publish(marker, CLIENT_ID, EVENT_TYPE);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));
        assertThat(countByEventId(unsubscribed)).isZero();
    }

    @Test
    void invalidMessageDoesNotBlockThePartition() {
        String marker = uniqueEventId();

        kafkaTemplate.send(topic, CLIENT_ID, "{not valid json");
        publish(marker, CLIENT_ID, EVENT_TYPE);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));
    }

    private void publish(String eventId, String clientId, String eventType) {
        kafkaTemplate.send(topic, clientId, """
                {"schema_version":1,"event_id":"%s","event_type":"%s","client_id":"%s",
                 "occurred_at":"2026-09-23T12:00:00Z","content":"Credit card payment received for $150.00"}
                """.formatted(eventId, eventType, clientId));
    }

    private long countByEventId(String eventId) {
        return jdbcClient.sql("SELECT count(*) FROM notification_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }

    private static String uniqueEventId() {
        return "EVT-" + UUID.randomUUID();
    }
}
