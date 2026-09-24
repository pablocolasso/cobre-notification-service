package com.cobre.notification.support;

import com.cobre.notification.application.port.in.IngestPlatformEventUseCase;
import com.cobre.notification.application.port.in.IngestionResult;
import com.cobre.notification.domain.model.PlatformEvent;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delegates to the real use case except for event ids with a {@code FAULT-} prefix, which simulate consumer
 * failures. Shared by every integration context so fault tests do not need their own containers.
 */
public class FaultInjectingIngestion implements IngestPlatformEventUseCase {

    public static final String TRANSIENT_PREFIX = "FAULT-TRANSIENT-";
    public static final String UNEXPECTED_PREFIX = "FAULT-UNEXPECTED-";
    public static final int TRANSIENT_FAILURES = 2;

    private final IngestPlatformEventUseCase delegate;
    private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

    public FaultInjectingIngestion(IngestPlatformEventUseCase delegate) {
        this.delegate = delegate;
    }

    @Override
    public IngestionResult ingest(PlatformEvent event) {
        int call = calls.computeIfAbsent(event.eventId(), id -> new AtomicInteger()).incrementAndGet();
        if (event.eventId().startsWith(TRANSIENT_PREFIX) && call <= TRANSIENT_FAILURES) {
            throw new CannotGetJdbcConnectionException("Simulated: database unavailable");
        }
        if (event.eventId().startsWith(UNEXPECTED_PREFIX)) {
            // Deliberately quotes the content: nothing downstream may copy exception messages to logs or the DLT.
            throw new IllegalStateException("Simulated bug while handling " + event.content());
        }
        return delegate.ingest(event);
    }

    public int calls(String eventId) {
        AtomicInteger count = calls.get(eventId);
        return count == null ? 0 : count.get();
    }
}
