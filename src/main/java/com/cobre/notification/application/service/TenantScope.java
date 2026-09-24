package com.cobre.notification.application.service;

import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.port.in.Requester.Client;
import com.cobre.notification.application.port.in.Requester.Operator;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.domain.model.NotificationEvent;

import java.util.Optional;
import java.util.UUID;

/**
 * Tenant predicates live here (and in the SQL they produce), not in the controller.
 */
final class TenantScope {

    private TenantScope() {
    }

    static String effectiveClientId(Requester requester, String requestedClientId) {
        return switch (requester) {
            case Client client -> client.clientId();
            case Operator ignored -> requestedClientId;
        };
    }

    /**
     * For operator list audit: the filter they asked for, or {@code *} when they see every tenant.
     */
    static String auditClientId(Requester requester, String requestedClientId) {
        return switch (requester) {
            case Client client -> client.clientId();
            case Operator ignored -> requestedClientId == null || requestedClientId.isBlank() ? "*" : requestedClientId;
        };
    }

    static Optional<NotificationEvent> findVisible(NotificationEventQueryRepository queries, Requester requester,
                                                   UUID notificationEventId) {
        String tenant = switch (requester) {
            case Client client -> client.clientId();
            case Operator ignored -> null;
        };
        return queries.findById(notificationEventId, tenant);
    }
}
