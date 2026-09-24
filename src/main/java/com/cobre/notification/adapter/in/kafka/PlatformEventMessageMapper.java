package com.cobre.notification.adapter.in.kafka;

import com.cobre.notification.domain.model.PlatformEvent;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.format.DateTimeParseException;

@Component
class PlatformEventMessageMapper {

    static final int SUPPORTED_SCHEMA_VERSION = 1;

    private final JsonMapper jsonMapper;

    PlatformEventMessageMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    PlatformEvent toPlatformEvent(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidPlatformEventException("empty_message");
        }

        PlatformEventMessage message;
        try {
            message = jsonMapper.readValue(json, PlatformEventMessage.class);
        } catch (JacksonException e) {
            throw new InvalidPlatformEventException("malformed_json");
        }

        if (message.schemaVersion() == null || message.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
            throw new InvalidPlatformEventException("unsupported_schema_version");
        }

        return new PlatformEvent(
                required(message.eventId(), "event_id", 100),
                required(message.eventType(), "event_type", 100),
                required(message.clientId(), "client_id", 64),
                parseInstant(message.occurredAt()),
                requiredContent(message.content()),
                message.schemaVersion());
    }

    private static String required(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidPlatformEventException("missing_" + field);
        }
        if (value.length() > maxLength) {
            throw new InvalidPlatformEventException("too_long_" + field);
        }
        return value;
    }

    private static String requiredContent(String content) {
        if (content == null) {
            throw new InvalidPlatformEventException("missing_content");
        }
        return content;
    }

    private static Instant parseInstant(String occurredAt) {
        if (occurredAt == null || occurredAt.isBlank()) {
            throw new InvalidPlatformEventException("missing_occurred_at");
        }
        try {
            return Instant.parse(occurredAt);
        } catch (DateTimeParseException e) {
            throw new InvalidPlatformEventException("invalid_occurred_at");
        }
    }
}
