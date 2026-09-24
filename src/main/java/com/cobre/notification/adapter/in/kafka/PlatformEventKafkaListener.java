package com.cobre.notification.adapter.in.kafka;

import com.cobre.notification.application.port.in.IngestPlatformEventUseCase;
import com.cobre.notification.application.port.in.IngestionResult;
import com.cobre.notification.domain.model.PlatformEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Validates and persists platform events; never performs HTTP. The offset is committed only after this method
 * returns, i.e. after the notification is durably stored.
 */
@Component
class PlatformEventKafkaListener {

    private static final Logger log = LoggerFactory.getLogger(PlatformEventKafkaListener.class);

    private final PlatformEventMessageMapper mapper;
    private final IngestPlatformEventUseCase ingestPlatformEvent;

    PlatformEventKafkaListener(PlatformEventMessageMapper mapper, IngestPlatformEventUseCase ingestPlatformEvent) {
        this.mapper = mapper;
        this.ingestPlatformEvent = ingestPlatformEvent;
    }

    static final String CORRELATION_ID_HEADER = "correlation-id";

    @KafkaListener(topics = "${app.kafka.topics.platform-events}")
    void onMessage(ConsumerRecord<String, String> record) {
        String correlationId = header(record, CORRELATION_ID_HEADER);
        MDC.put("correlation_id", correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString() : correlationId);
        try {
            PlatformEvent event = mapper.toPlatformEvent(record.value());
            MDC.put("event_id", event.eventId());
            MDC.put("client_id", event.clientId());
            IngestionResult result = ingestPlatformEvent.ingest(event);
            MDC.put("status", result.name().toLowerCase());

            log.atInfo()
                    .addKeyValue("event_id", event.eventId())
                    .addKeyValue("client_id", event.clientId())
                    .addKeyValue("event_type", event.eventType())
                    .addKeyValue("outcome", result.name().toLowerCase())
                    .addKeyValue("partition", record.partition())
                    .addKeyValue("offset", record.offset())
                    .log("Platform event ingested");
        } finally {
            MDC.remove("correlation_id");
            MDC.remove("event_id");
            MDC.remove("client_id");
            MDC.remove("status");
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
