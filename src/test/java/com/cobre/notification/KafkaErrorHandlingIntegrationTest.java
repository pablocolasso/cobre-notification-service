package com.cobre.notification;

import com.cobre.notification.adapter.in.kafka.DeadLetterMetrics;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.support.FaultInjectingIngestion;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@ExtendWith(OutputCaptureExtension.class)
class KafkaErrorHandlingIntegrationTest extends AbstractIntegrationTest {

    private static final String CLIENT_ID = "CLIENT001";
    private static final String EVENT_TYPE = "credit_card_payment";
    private static final String TEST_ID_HEADER = "test-id";
    private static final String SECRET_CONTENT = "Transfer to account #4567-SECRET";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    ConsumerFactory<String, String> consumerFactory;

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    FaultInjectingIngestion faultInjection;

    @Autowired
    MeterRegistry meterRegistry;

    @Value("${app.kafka.topics.platform-events}")
    String topic;

    @Value("${app.kafka.topics.platform-events-dlt}")
    String deadLetterTopic;

    private Consumer<String, String> deadLetterConsumer;
    private final Map<String, ConsumerRecord<String, String>> deadLettered = new ConcurrentHashMap<>();
    private String partitionKey;

    @BeforeEach
    void setUp() {
        subscriptions.upsertActive(UUID.randomUUID(), CLIENT_ID, EVENT_TYPE, "https://client.example/hook");
        partitionKey = "key-" + UUID.randomUUID();
        deadLetterConsumer = consumerFactory.createConsumer("dlt-reader-" + UUID.randomUUID(), null);
        deadLetterConsumer.subscribe(List.of(deadLetterTopic));
    }

    @AfterEach
    void tearDown() {
        deadLetterConsumer.close();
    }

    @Test
    void invalidMessagesGoToTheDeadLetterTopicAndThePartitionKeepsMoving(CapturedOutput output) {
        double before = deadLettered("invalid_event");
        Map<String, String> invalid = Map.of(
                "malformed", "{not valid json " + SECRET_CONTENT,
                "null-literal", "null",
                "nul-in-content", event("EVT-" + UUID.randomUUID(), "before\\u0000" + SECRET_CONTENT),
                "missing-field", """
                        {"schema_version":1,"event_type":"t","client_id":"C","occurred_at":"2026-09-23T12:00:00Z",
                         "content":"%s"}
                        """.formatted(SECRET_CONTENT));
        Map<String, String> expectedDetail = Map.of(
                "malformed", "malformed_json",
                "null-literal", "null_message",
                "nul-in-content", "invalid_characters_content",
                "missing-field", "missing_event_id");

        invalid.forEach(this::send);
        String marker = "EVT-" + UUID.randomUUID();
        send("marker", event(marker, "valid"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            pollDeadLetters();
            assertThat(deadLettered).containsKeys(invalid.keySet().toArray(String[]::new));
        });

        invalid.forEach((testId, payload) -> {
            ConsumerRecord<String, String> record = deadLettered.get(testId);
            assertThat(record.value()).isEqualTo(payload);
            assertThat(record.key()).isEqualTo(partitionKey);
            assertThat(header(record, "x-cobre-dlt-reason")).isEqualTo("invalid_event");
            assertThat(header(record, "x-cobre-dlt-detail")).isEqualTo(expectedDetail.get(testId));
            assertThat(record.headers().lastHeader("kafka_dlt-exception-message")).isNull();
            assertThat(record.headers().lastHeader("kafka_dlt-exception-stacktrace")).isNull();
        });
        assertThat(deadLettered("invalid_event") - before).isEqualTo(4.0);
        assertThat(output).doesNotContain(SECRET_CONTENT);
    }

    @Test
    void unexpectedErrorIsDeadLetteredWithoutRetrying(CapturedOutput output) {
        double before = deadLettered("unexpected_error");
        String eventId = FaultInjectingIngestion.UNEXPECTED_PREFIX + UUID.randomUUID();
        send("unexpected", event(eventId, SECRET_CONTENT));
        String marker = "EVT-" + UUID.randomUUID();
        send("marker", event(marker, "valid"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            pollDeadLetters();
            assertThat(deadLettered).containsKey("unexpected");
        });

        ConsumerRecord<String, String> record = deadLettered.get("unexpected");
        assertThat(header(record, "x-cobre-dlt-reason")).isEqualTo("unexpected_error");
        assertThat(header(record, "x-cobre-dlt-detail")).isEqualTo("IllegalStateException");
        assertThat(record.headers().lastHeader("kafka_dlt-exception-message")).isNull();
        assertThat(faultInjection.calls(eventId)).isEqualTo(1);
        assertThat(deadLettered("unexpected_error") - before).isEqualTo(1.0);
        assertThat(output).doesNotContain(SECRET_CONTENT);
    }

    @Test
    void transientErrorIsRetriedUntilItSucceedsAndIsNotDeadLettered() {
        String eventId = FaultInjectingIngestion.TRANSIENT_PREFIX + UUID.randomUUID();
        send("transient", event(eventId, "valid"));
        String marker = "EVT-" + UUID.randomUUID();
        send("marker", event(marker, "valid"));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> assertThat(countByEventId(marker)).isEqualTo(1));

        assertThat(countByEventId(eventId)).isEqualTo(1);
        assertThat(faultInjection.calls(eventId)).isEqualTo(FaultInjectingIngestion.TRANSIENT_FAILURES + 1);
        pollDeadLetters();
        assertThat(deadLettered).doesNotContainKey("transient");
    }

    private void send(String testId, String payload) {
        var record = new ProducerRecord<>(topic, partitionKey, payload);
        record.headers().add(TEST_ID_HEADER, testId.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record).join();
    }

    private void pollDeadLetters() {
        for (ConsumerRecord<String, String> record : deadLetterConsumer.poll(Duration.ofMillis(500))) {
            if (partitionKey.equals(record.key())) {
                deadLettered.put(header(record, TEST_ID_HEADER), record);
            }
        }
    }

    private double deadLettered(String reason) {
        var counter = meterRegistry.find(DeadLetterMetrics.PUBLISHED).tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String event(String eventId, String content) {
        return """
                {"schema_version":1,"event_id":"%s","event_type":"%s","client_id":"%s",
                 "occurred_at":"2026-09-23T12:00:00Z","content":"%s"}
                """.formatted(eventId, EVENT_TYPE, CLIENT_ID, content);
    }

    private long countByEventId(String eventId) {
        return jdbcClient.sql("SELECT count(*) FROM notification_events WHERE event_id = :eventId")
                .param("eventId", eventId)
                .query(Long.class)
                .single();
    }
}
