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

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform event on Kafka -> subscription check -> PostgreSQL -> worker (claim, retries, lease recovery) ->
 * webhook. Backoff runs in milliseconds; everything else is the production wiring.
 */
@TestPropertySource(properties = {
        "app.delivery.worker.enabled=true",
        "app.delivery.worker.poll-interval=100ms",
        "app.delivery.retry.base-delay=50ms",
        "app.delivery.retry.max-delay=200ms",
        "app.delivery.retry.max-attempts=3",
        "app.webhook.request-timeout=1s"
})
class DeliveryEndToEndTest extends AbstractIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

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
        String eventId = publishTo(webhookServer.url("/ok"));

        Map<String, Object> notification = awaitStatus(eventId, "COMPLETED");
        assertThat(notification.get("delivered_at")).isNotNull();
        assertThat(notification.get("attempt_count")).isEqualTo(1);
        assertThat(notification.get("locked_by")).isNull();

        ReceivedRequest request = webhookServer.receivedOn("/ok").stream()
                .filter(received -> eventId.equals(received.headers().getFirst("X-Cobre-Event-Id")))
                .findFirst()
                .orElseThrow();
        assertThat(request.headers().getFirst("Idempotency-Key")).isEqualTo(notification.get("id").toString());
        assertThat(request.protocol()).isEqualTo("HTTP/1.1");

        assertThat(attempts(notification.get("id"))).singleElement().satisfies(attempt -> {
            assertThat(attempt.get("status")).isEqualTo("SUCCESS");
            assertThat(attempt.get("http_status")).isEqualTo(200);
            assertThat(attempt.get("attempt_trigger")).isEqualTo("INITIAL");
        });

        mockMvc.perform(get("/notification_events/{id}", notification.get("id")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.event_id").value(eventId))
                .andExpect(jsonPath("$.delivery_status").value("completed"))
                .andExpect(jsonPath("$.delivery_attempts[0].status").value("success"));
    }

    @Test
    void flakyWebhookIsRetriedUntilItSucceeds() throws Exception {
        String path = "/flaky-" + UUID.randomUUID();
        String eventId = publishTo(webhookServer.script(path, 500, 500, 200));

        Map<String, Object> notification = awaitStatus(eventId, "COMPLETED");

        assertThat(notification.get("attempt_count")).isEqualTo(3);
        assertThat(notification.get("delivered_at")).isNotNull();
        assertThat(notification.get("last_http_status")).isEqualTo(200);
        assertThat(attempts(notification.get("id")))
                .extracting(a -> a.get("attempt_number") + " " + a.get("attempt_trigger") + " " + a.get("status")
                        + " " + a.get("http_status"))
                .containsExactly(
                        "1 INITIAL RETRYABLE_FAILURE 500",
                        "2 RETRY RETRYABLE_FAILURE 500",
                        "3 RETRY SUCCESS 200");
        assertThat(webhookServer.receivedOn(path))
                .extracting(request -> request.headers().getFirst("X-Cobre-Delivery-Attempt"))
                .containsExactly("1", "2", "3");
        assertThat(webhookServer.receivedOn(path))
                .extracting(request -> request.headers().getFirst("Idempotency-Key"))
                .containsOnly(notification.get("id").toString());

        mockMvc.perform(get("/notification_events/{id}", notification.get("id")))
                .andExpect(jsonPath("$.delivery_status").value("completed"))
                .andExpect(jsonPath("$.delivery_attempts.length()").value(3));
    }

    @Test
    void permanentFailureFailsImmediately() {
        String eventId = publishTo(webhookServer.url("/gone"));

        Map<String, Object> notification = awaitStatus(eventId, "FAILED");

        assertThat(notification.get("attempt_count")).isEqualTo(1);
        assertThat(notification.get("next_attempt_at")).isNull();
        assertThat(notification.get("last_error")).isEqualTo("http_status: HTTP 404");
        assertThat(attempts(notification.get("id"))).singleElement()
                .satisfies(attempt -> assertThat(attempt.get("status")).isEqualTo("PERMANENT_FAILURE"));
    }

    @Test
    void rateLimitedWebhookHonoursRetryAfterBoundedByTheCapAndFailsWhenExhausted() {
        String eventId = publishTo(webhookServer.url("/rate-limited"));

        Map<String, Object> notification = awaitStatus(eventId, "FAILED");

        assertThat(notification.get("attempt_count")).isEqualTo(3);
        assertThat(notification.get("last_error")).isEqualTo("http_status: HTTP 429; retries exhausted after 3 attempts");
        List<Map<String, Object>> attempts = attempts(notification.get("id"));
        assertThat(attempts).extracting(a -> a.get("status") + " " + a.get("http_status"))
                .containsOnly("RETRYABLE_FAILURE 429");
        for (int i = 1; i < attempts.size(); i++) {
            Duration gap = Duration.between(instant(attempts.get(i - 1).get("completed_at")),
                    instant(attempts.get(i).get("started_at")));
            assertThat(gap).isGreaterThanOrEqualTo(Duration.ofMillis(200));
        }
    }

    @Test
    void timeoutsAreRetriedAndFailWithTheReasonWhenExhausted() {
        String eventId = publishTo(webhookServer.url("/slow"));

        Map<String, Object> notification = awaitStatus(eventId, "FAILED");

        assertThat(notification.get("attempt_count")).isEqualTo(3);
        assertThat(notification.get("last_http_status")).isNull();
        assertThat(notification.get("last_error"))
                .isEqualTo("timeout: No response within 1000 ms; retries exhausted after 3 attempts");
        assertThat(attempts(notification.get("id"))).hasSize(3).allSatisfy(attempt -> {
            assertThat(attempt.get("status")).isEqualTo("RETRYABLE_FAILURE");
            assertThat(attempt.get("error_code")).isEqualTo("timeout");
        });
    }

    @Test
    void serverErrorsExhaustTheAttemptBudget() {
        String eventId = publishTo(webhookServer.url("/error"));

        Map<String, Object> notification = awaitStatus(eventId, "FAILED");

        assertThat(notification.get("attempt_count")).isEqualTo(3);
        assertThat(notification.get("last_error")).isEqualTo("http_status: HTTP 500; retries exhausted after 3 attempts");
        assertThat(attempts(notification.get("id"))).extracting(a -> (String) a.get("attempt_trigger"))
                .containsExactly("INITIAL", "RETRY", "RETRY");
    }

    @Test
    void expiredLeaseOfACrashedWorkerIsRecoveredAndDelivered() {
        UUID id = UUID.randomUUID();
        UUID abandonedAttempt = UUID.randomUUID();
        Timestamp longAgo = Timestamp.from(Instant.now().minusSeconds(120));
        String webhookUrl = webhookServer.url("/ok");
        jdbcClient.sql("""
                        INSERT INTO notification_events (
                            id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                            delivery_status, attempt_count, cycle_attempt_count, last_attempt_at,
                            locked_by, locked_until, origin)
                        VALUES (:id, :eventId, 'CLIENT009', 'e2e_crash', 'content', :longAgo, :webhookUrl,
                                'PROCESSING', 1, 1, :longAgo, 'crashed-worker', :expired, 'KAFKA')
                        """)
                .param("id", id)
                .param("eventId", "EVT-" + UUID.randomUUID())
                .param("longAgo", longAgo)
                .param("webhookUrl", webhookUrl)
                .param("expired", Timestamp.from(Instant.now().minusSeconds(60)))
                .update();
        jdbcClient.sql("""
                        INSERT INTO delivery_attempts (id, notification_event_id, attempt_number, attempt_trigger,
                                                       webhook_url, status, started_at)
                        VALUES (:attemptId, :id, 1, 'INITIAL', :webhookUrl, 'IN_PROGRESS', :longAgo)
                        """)
                .param("attemptId", abandonedAttempt)
                .param("id", id)
                .param("webhookUrl", webhookUrl)
                .param("longAgo", longAgo)
                .update();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(jdbcClient
                .sql("SELECT delivery_status FROM notification_events WHERE id = :id")
                .param("id", id).query(String.class).single()).isEqualTo("COMPLETED"));

        assertThat(attempts(id))
                .extracting(a -> a.get("attempt_number") + " " + a.get("status") + " " + a.get("error_code"))
                .containsExactly("1 ABANDONED lease_expired", "2 SUCCESS null");
    }

    private String publishTo(String webhookUrl) {
        String clientId = "CLIENT-" + UUID.randomUUID().toString().substring(0, 8);
        String eventType = "e2e_event";
        String eventId = "EVT-" + UUID.randomUUID();
        subscriptions.upsertActive(UUID.randomUUID(), clientId, eventType, webhookUrl);
        kafkaTemplate.send(topic, clientId, """
                {"schema_version":1,"event_id":"%s","event_type":"%s","client_id":"%s",
                 "occurred_at":"2026-09-23T12:00:00Z","content":"Bank transfer received for $1,500.00"}
                """.formatted(eventId, eventType, clientId));
        return eventId;
    }

    private Map<String, Object> awaitStatus(String eventId, String status) {
        await().atMost(TIMEOUT)
                .untilAsserted(() -> assertThat(notification(eventId)).containsEntry("delivery_status", status));
        return notification(eventId);
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

    private List<Map<String, Object>> attempts(Object notificationId) {
        return jdbcClient.sql("""
                        SELECT * FROM delivery_attempts WHERE notification_event_id = :id ORDER BY attempt_number
                        """)
                .param("id", notificationId)
                .query()
                .listOfRows();
    }

    private static Instant instant(Object timestamp) {
        return switch (timestamp) {
            case Timestamp ts -> ts.toInstant();
            case OffsetDateTime odt -> odt.toInstant();
            default -> throw new IllegalArgumentException("Unexpected timestamp type: " + timestamp.getClass());
        };
    }
}
