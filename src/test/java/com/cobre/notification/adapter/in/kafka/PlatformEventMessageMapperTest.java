package com.cobre.notification.adapter.in.kafka;

import com.cobre.notification.domain.model.PlatformEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlatformEventMessageMapperTest {

    private final PlatformEventMessageMapper mapper = new PlatformEventMessageMapper(JsonMapper.builder().build());

    @Test
    void mapsValidMessage() {
        PlatformEvent event = mapper.toPlatformEvent("""
                {"schema_version":1,"event_id":"EVT101","event_type":"credit_card_payment","client_id":"CLIENT001",
                 "occurred_at":"2026-09-23T12:00:00Z","content":"Credit card payment received","extra":"ignored"}
                """);

        assertThat(event.eventId()).isEqualTo("EVT101");
        assertThat(event.eventType()).isEqualTo("credit_card_payment");
        assertThat(event.clientId()).isEqualTo("CLIENT001");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-23T12:00:00Z"));
        assertThat(event.content()).isEqualTo("Credit card payment received");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "not json                                                                                     | malformed_json",
            "{\"schema_version\":2,\"event_id\":\"E\",\"event_type\":\"t\",\"client_id\":\"C\",\"occurred_at\":\"2026-09-23T12:00:00Z\",\"content\":\"x\"} | unsupported_schema_version",
            "{\"schema_version\":1,\"event_type\":\"t\",\"client_id\":\"C\",\"occurred_at\":\"2026-09-23T12:00:00Z\",\"content\":\"x\"} | missing_event_id",
            "{\"schema_version\":1,\"event_id\":\"E\",\"client_id\":\"C\",\"occurred_at\":\"2026-09-23T12:00:00Z\",\"content\":\"x\"} | missing_event_type",
            "{\"schema_version\":1,\"event_id\":\"E\",\"event_type\":\"t\",\"occurred_at\":\"2026-09-23T12:00:00Z\",\"content\":\"x\"} | missing_client_id",
            "{\"schema_version\":1,\"event_id\":\"E\",\"event_type\":\"t\",\"client_id\":\"C\",\"occurred_at\":\"yesterday\",\"content\":\"x\"} | invalid_occurred_at",
            "{\"schema_version\":1,\"event_id\":\"E\",\"event_type\":\"t\",\"client_id\":\"C\",\"occurred_at\":\"2026-09-23T12:00:00Z\"} | missing_content"
    })
    void rejectsInvalidMessages(String json, String expectedReason) {
        assertThatThrownBy(() -> mapper.toPlatformEvent(json))
                .isInstanceOf(InvalidPlatformEventException.class)
                .extracting(e -> ((InvalidPlatformEventException) e).reason())
                .isEqualTo(expectedReason);
    }

    @Test
    void errorMessageNeverContainsPayload() {
        String secretContent = "Transfer to account #4567";

        assertThatThrownBy(() -> mapper.toPlatformEvent("{\"content\":\"" + secretContent + "\", broken"))
                .isInstanceOf(InvalidPlatformEventException.class)
                .hasMessageNotContaining(secretContent);
    }
}
