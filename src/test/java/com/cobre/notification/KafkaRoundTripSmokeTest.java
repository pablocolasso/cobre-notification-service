package com.cobre.notification;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Verifies the Spring Kafka wiring (String serde, record ack mode, listener container) against a real broker.
 * The production listener is added in Phase 1.
 */
@Import(KafkaRoundTripSmokeTest.SmokeListenerConfiguration.class)
class KafkaRoundTripSmokeTest extends AbstractIntegrationTest {

    static final String SMOKE_TOPIC = "smoke.test";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    SmokeListener smokeListener;

    @Test
    void publishedRecordIsConsumedByListener() {
        kafkaTemplate.send(SMOKE_TOPIC, "CLIENT001", "{\"event_id\":\"SMOKE-1\"}");

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(smokeListener.received).contains("{\"event_id\":\"SMOKE-1\"}"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SmokeListenerConfiguration {

        @Bean
        NewTopic smokeTopic() {
            return TopicBuilder.name(SMOKE_TOPIC).partitions(1).replicas(1).build();
        }

        @Bean
        SmokeListener smokeListener() {
            return new SmokeListener();
        }
    }

    static class SmokeListener {

        final List<String> received = new CopyOnWriteArrayList<>();

        @KafkaListener(topics = SMOKE_TOPIC, groupId = "smoke-test")
        void onMessage(String value) {
            received.add(value);
        }
    }

}
