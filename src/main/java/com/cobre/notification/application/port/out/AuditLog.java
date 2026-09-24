package com.cobre.notification.application.port.out;

import java.util.UUID;

/**
 * Operator-only audit trail. Implementations must never receive {@code content} or the raw API key.
 */
public interface AuditLog {

    void record(OperatorAction action);

    record OperatorAction(
            String operator,
            Action action,
            UUID notificationEventId,
            String clientId,
            String outcome,
            String correlationId) {
    }

    enum Action {
        LIST_NOTIFICATIONS,
        GET_NOTIFICATION,
        REPLAY_NOTIFICATION
    }
}
