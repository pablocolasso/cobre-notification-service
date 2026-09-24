package com.cobre.notification.adapter.in.web;

import com.cobre.notification.adapter.in.web.dto.NotificationEventDetailsResponse;
import com.cobre.notification.adapter.in.web.dto.NotificationEventResponse;
import com.cobre.notification.adapter.in.web.dto.PageResponse;
import com.cobre.notification.application.port.in.GetNotificationEventUseCase;
import com.cobre.notification.application.port.in.ListNotificationEventsUseCase;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Unauthenticated in Phase 1: {@code client_id} is a plain filter here. From Phase 3 the tenant comes from the API key.
 */
@RestController
@RequestMapping("/notification_events")
class NotificationEventController {

    static final int MAX_PAGE_SIZE = 100;

    private final ListNotificationEventsUseCase listNotificationEvents;
    private final GetNotificationEventUseCase getNotificationEvent;

    NotificationEventController(ListNotificationEventsUseCase listNotificationEvents,
                                GetNotificationEventUseCase getNotificationEvent) {
        this.listNotificationEvents = listNotificationEvents;
        this.getNotificationEvent = getNotificationEvent;
    }

    @GetMapping
    PageResponse<NotificationEventResponse> list(
            @RequestParam(name = "client_id", required = false) String clientId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        var query = new NotificationEventQuery(clientId, page, size);
        return PageResponse.from(listNotificationEvents.list(query).map(NotificationEventResponse::from));
    }

    @GetMapping("/{notification_event_id}")
    NotificationEventDetailsResponse get(@PathVariable("notification_event_id") UUID notificationEventId) {
        return getNotificationEvent.get(notificationEventId)
                .map(NotificationEventDetailsResponse::from)
                .orElseThrow(() -> new NotificationEventNotFoundException(notificationEventId));
    }
}
