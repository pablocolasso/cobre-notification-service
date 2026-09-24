package com.cobre.notification;

import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.support.RecordingWebhookServer;
import com.cobre.notification.support.RecordingWebhookServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Walking skeleton: platform event on Kafka -> subscription check -> PostgreSQL -> worker -> webhook.
 */
@TestPropertySource(properties = {
        "app.delivery.worker.enabled=true",
        "app.delivery.worker.poll-interval=200ms"
})
class DeliveryEndToEndTest extends AbstractIntegrationTest {

    private static RecordingWebhookServer webhookServer;

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    MockMvc mockMvc;

    @Value("${app.kafka.topics.platform-events}")
    String topic;

    @BeforeAll
    static void startWebhookServer() {
        webhookServer = RecordingWebhookServer.start();
    }

    @AfterAll
    static void stopWebhookServer() {
        webhookServer.close();
    }

    @Test
    void subscribedEventIsDeliveredAndCompleted() throws Exception {
        subscriptions.upsertActive("CLIENT001", "credit_card_payment", webhookServer.url("/ok"));
        String eventId = "EVT-" + UUID.randomUUID();

        publish(eventId, "CLIENT001", "credit_card_payment");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(notification(eventId)).containsEntry("delivery_status", "COMPLETED"));

        Map<String, Object> notification = notification(eventId);
        assertThat(notification.get("delivered_at")).isNotNull();
        assertThat(notification.get("attempt_count")).isEqualTo(1);
        assertThat(notification.get("locked_by")).isNull();

        ReceivedRequest request = webhookServer.receivedOn("/ok").stream()
                .filter(received -> eventId.equals(received.headers().getFirst("X-Cobre-Event-Id")))
                .findFirst()
                .orElseThrow();
        assertThat(request.headers().getFirst("Idempotency-Key")).isEqualTo(notification.get("id").toString());

        assertThat(attempts(notification.get("id")))
                .singleElement()
                .satisfies(attempt -> {
                    assertThat(attempt.get("status")).isEqualTo("SUCCESS");
                    assertThat(attempt.get("http_status")).isEqualTo(200);
                    assertThat(attempt.get("attempt_trigger")).isEqualTo("INITIAL");
                });

        mockMvc.perform(get("/notification_events/{id}", notification.get("id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value(eventId))
                .andExpect(jsonPath("$.delivery_status").value("completed"))
                .andExpect(jsonPath("$.delivery_attempts[0].status").value("success"));
        mockMvc.perform(get("/notification_events").param("client_id", "CLIENT001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.event_id == '%s')].delivery_status", eventId).value("completed"));
    }

    @Test
    void failingWebhookMarksNotificationFailed() {
        subscriptions.upsertActive("CLIENT002", "credit_transfer", webhookServer.url("/error"));
        String eventId = "EVT-" + UUID.randomUUID();

        publish(eventId, "CLIENT002", "credit_transfer");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(notification(eventId)).containsEntry("delivery_status", "FAILED"));

        Map<String, Object> notification = notification(eventId);
        assertThat(notification.get("last_http_status")).isEqualTo(500);
        assertThat(notification.get("last_error")).isEqualTo("http_status: HTTP 500");
        assertThat(attempts(notification.get("id")))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.get("status")).isEqualTo("PERMANENT_FAILURE"));
    }

    private void publish(String eventId, String clientId, String eventType) {
        kafkaTemplate.send(topic, clientId, """
                {"schema_version":1,"event_id":"%s","event_type":"%s","client_id":"%s",
                 "occurred_at":"2026-09-23T12:00:00Z","content":"Bank transfer received for $1,500.00"}
                """.formatted(eventId, eventType, clientId));
    }

    private Map<String, Object> notification(String eventId) {
        return jdbcClient.sql("SELECT * FROM notification_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query()
                .listOfRows()
                .stream()
                .findFirst()
                .orElse(Map.of());
    }

    private java.util.List<Map<String, Object>> attempts(Object notificationId) {
        return jdbcClient.sql("SELECT * FROM delivery_attempts WHERE notification_event_id = :id")
                .param("id", notificationId)
                .query()
                .listOfRows();
    }
}
