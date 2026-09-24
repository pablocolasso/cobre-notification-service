package com.cobre.notification.adapter.out.webhook;

import com.cobre.notification.config.WebhookProperties;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryResult;
import com.cobre.notification.domain.model.DeliveryResult.HttpResponseReceived;
import com.cobre.notification.domain.model.DeliveryResult.TransportFailure;
import com.cobre.notification.domain.model.DeliveryTask;
import com.cobre.notification.support.RecordingWebhookServer;
import com.cobre.notification.support.RecordingWebhookServer.ReceivedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JdkWebhookClientTest {

    private static RecordingWebhookServer server;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final JdkWebhookClient client = new JdkWebhookClient(
            new WebhookProperties(Duration.ofSeconds(1), Duration.ofMillis(500)), jsonMapper);

    @BeforeAll
    static void startServer() {
        server = RecordingWebhookServer.start();
    }

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void closeClient() {
        client.close();
    }

    @Test
    void postsPayloadWithIdempotencyHeaders() {
        DeliveryTask task = task(server.url("/ok"));

        DeliveryResult result = client.deliver(task);

        assertThat(result).isEqualTo(new HttpResponseReceived(200));
        ReceivedRequest request = server.receivedOn("/ok").getLast();
        assertThat(request.headers().getFirst("Idempotency-Key")).isEqualTo(task.notificationEventId().toString());
        assertThat(request.headers().getFirst("X-Cobre-Event-Id")).isEqualTo(task.eventId());
        assertThat(request.headers().getFirst("X-Cobre-Event-Type")).isEqualTo("credit_card_payment");
        assertThat(request.headers().getFirst("X-Cobre-Delivery-Attempt")).isEqualTo("1");
        assertThat(request.headers().getFirst("Content-Type")).isEqualTo("application/json");
        assertThat(request.protocol()).isEqualTo("HTTP/1.1");
        assertThat(request.headers().containsKey("Upgrade")).isFalse();

        JsonNode body = jsonMapper.readTree(request.body());
        assertThat(body.get("notification_event_id").asString()).isEqualTo(task.notificationEventId().toString());
        assertThat(body.get("event_id").asString()).isEqualTo(task.eventId());
        assertThat(body.get("client_id").asString()).isEqualTo("CLIENT001");
        assertThat(body.get("occurred_at").asString()).isEqualTo("2026-09-23T12:00:00Z");
        assertThat(body.get("content").asString()).isEqualTo("Credit card payment received for $150.00");
    }

    @Test
    void reportsErrorStatus() {
        assertThat(client.deliver(task(server.url("/error")))).isEqualTo(new HttpResponseReceived(500));
    }

    @Test
    void reportsRetryAfterOnRateLimit() {
        assertThat(client.deliver(task(server.url("/rate-limited"))))
                .isEqualTo(new HttpResponseReceived(429, Duration.ofSeconds(1)));
    }

    @Test
    void doesNotFollowRedirects() {
        int okRequestsBefore = server.receivedOn("/ok").size();

        DeliveryResult result = client.deliver(task(server.url("/redirect")));

        assertThat(result).isEqualTo(new HttpResponseReceived(302));
        assertThat(server.receivedOn("/ok")).hasSize(okRequestsBefore);
    }

    @Test
    void reportsTimeout() {
        DeliveryResult result = client.deliver(task(server.url("/slow")));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class,
                failure -> assertThat(failure.error().code()).isEqualTo("timeout"));
    }

    @Test
    void reportsConnectionFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        DeliveryResult result = client.deliver(task("http://localhost:" + closedPort + "/hook"));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class,
                failure -> assertThat(failure.error().code()).isEqualTo("connection_failed"));
    }

    @Test
    void reportsInvalidDestination() {
        DeliveryResult result = client.deliver(task("ftp://client.example/hook"));

        assertThat(result).isInstanceOfSatisfying(TransportFailure.class,
                failure -> assertThat(failure.error().code()).isEqualTo("invalid_destination"));
    }

    private static DeliveryTask task(String url) {
        return new DeliveryTask(UUID.randomUUID(), UUID.randomUUID(), 1, 1, AttemptTrigger.INITIAL,
                "EVT-" + UUID.randomUUID(), "CLIENT001", "credit_card_payment",
                "Credit card payment received for $150.00", Instant.parse("2026-09-23T12:00:00Z"), url,
                Instant.parse("2026-09-23T12:00:01Z"));
    }
}
