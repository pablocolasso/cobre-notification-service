package com.cobre.notification.adapter.in.kafka;

import com.cobre.notification.application.port.in.IngestPlatformEventUseCase;
import com.cobre.notification.application.port.in.IngestionResult;
import com.cobre.notification.domain.model.PlatformEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

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

    @KafkaListener(topics = "${app.kafka.topics.platform-events}")
    void onMessage(ConsumerRecord<String, String> record) {
        PlatformEvent event = mapper.toPlatformEvent(record.value());
        IngestionResult result = ingestPlatformEvent.ingest(event);

        log.atInfo()
                .addKeyValue("event_id", event.eventId())
                .addKeyValue("client_id", event.clientId())
                .addKeyValue("event_type", event.eventType())
                .addKeyValue("outcome", result.name().toLowerCase())
                .addKeyValue("partition", record.partition())
                .addKeyValue("offset", record.offset())
                .log("Platform event ingested");
    }
}
