package com.cobre.notification.adapter.in.web;

import com.cobre.notification.adapter.in.web.dto.NotificationEventDetailsResponse;
import com.cobre.notification.adapter.in.web.dto.NotificationEventResponse;
import com.cobre.notification.adapter.in.web.dto.PageResponse;
import com.cobre.notification.application.port.in.GetNotificationEventUseCase;
import com.cobre.notification.application.port.in.ListNotificationEventsUseCase;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.domain.model.DeliveryStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/notification_events")
@Tag(name = "Notification events")
@SecurityRequirement(name = "apiKey")
class NotificationEventController {

    static final int MAX_PAGE_SIZE = NotificationEventQuery.MAX_PAGE_SIZE;

    private final ListNotificationEventsUseCase listNotificationEvents;
    private final GetNotificationEventUseCase getNotificationEvent;
    private final ReplayNotificationEventUseCase replayNotificationEvent;

    NotificationEventController(ListNotificationEventsUseCase listNotificationEvents,
                                GetNotificationEventUseCase getNotificationEvent,
                                ReplayNotificationEventUseCase replayNotificationEvent) {
        this.listNotificationEvents = listNotificationEvents;
        this.getNotificationEvent = getNotificationEvent;
        this.replayNotificationEvent = replayNotificationEvent;
    }

    @GetMapping
    @Operation(summary = "List notification events",
            description = "For a client key the tenant is always the key's client_id; a client_id query parameter is ignored.")
    @ApiResponse(responseCode = "200", description = "Page of notification events")
    @ApiResponse(responseCode = "400", description = "Invalid query", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    PageResponse<NotificationEventResponse> list(
            Requester requester,
            @CorrelationId String correlationId,
            @Parameter(description = "Ignored for client keys; optional tenant filter for operators")
            @RequestParam(name = "client_id", required = false) String clientId,
            @Parameter(description = "Lowercase delivery status")
            @RequestParam(name = "delivery_status", required = false) String deliveryStatus,
            @Parameter(description = "Inclusive lower bound on event_created_at (ISO-8601)")
            @RequestParam(name = "created_from", required = false) Instant createdFrom,
            @Parameter(description = "Exclusive upper bound on event_created_at (ISO-8601)")
            @RequestParam(name = "created_to", required = false) Instant createdTo,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        var query = new NotificationEventQuery(clientId, parseDeliveryStatus(deliveryStatus), createdFrom, createdTo,
                page, size);
        return PageResponse.from(listNotificationEvents.list(requester, query, correlationId)
                .map(NotificationEventResponse::from));
    }

    @GetMapping("/{notification_event_id}")
    @Operation(summary = "Get a notification event and its attempts")
    @ApiResponse(responseCode = "200", description = "Notification event")
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown or not visible", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    NotificationEventDetailsResponse get(
            Requester requester,
            @CorrelationId String correlationId,
            @Parameter(in = ParameterIn.PATH) @PathVariable("notification_event_id") UUID notificationEventId) {
        return NotificationEventDetailsResponse.from(
                getNotificationEvent.get(requester, notificationEventId, correlationId));
    }

    @PostMapping("/{notification_event_id}/replay")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Replay a failed notification")
    @ApiResponse(responseCode = "202", description = "Queued for a new delivery cycle")
    @ApiResponse(responseCode = "401", description = "Missing or invalid API key", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "Unknown or not visible", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "Not failed, or no active subscription",
            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    NotificationEventDetailsResponse replay(
            Requester requester,
            @CorrelationId String correlationId,
            @PathVariable("notification_event_id") UUID notificationEventId) {
        return NotificationEventDetailsResponse.from(
                replayNotificationEvent.replay(requester, notificationEventId, correlationId));
    }

    private static DeliveryStatus parseDeliveryStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new InvalidRequestException("Unknown delivery_status");
        }
    }
}
