package com.cobre.notification.application.port.in;

import com.cobre.notification.domain.model.PlatformEvent;

public interface IngestPlatformEventUseCase {

    IngestionResult ingest(PlatformEvent event);
}
