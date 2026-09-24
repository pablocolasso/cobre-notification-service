package com.cobre.notification.application.service;

import com.cobre.notification.application.NotificationEventNotFoundException;
import com.cobre.notification.application.port.in.GetNotificationEventUseCase;
import com.cobre.notification.application.port.in.ListNotificationEventsUseCase;
import com.cobre.notification.application.port.in.NotificationEventDetails;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.PageResult;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.port.in.Requester.Client;
import com.cobre.notification.application.port.in.Requester.Operator;
import com.cobre.notification.application.port.out.AuditLog;
import com.cobre.notification.application.port.out.AuditLog.Action;
import com.cobre.notification.application.port.out.AuditLog.OperatorAction;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.domain.model.NotificationEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class NotificationQueryService implements ListNotificationEventsUseCase, GetNotificationEventUseCase {

    private static final Logger log = LoggerFactory.getLogger(NotificationQueryService.class);

    private final NotificationEventQueryRepository queries;
    private final AuditLog auditLog;

    public NotificationQueryService(NotificationEventQueryRepository queries, AuditLog auditLog) {
        this.queries = queries;
        this.auditLog = auditLog;
    }

    @Override
    public PageResult<NotificationEvent> list(Requester requester, NotificationEventQuery query, String correlationId) {
        NotificationEventQuery scoped = query.withClientId(TenantScope.effectiveClientId(requester, query.clientId()));
        if (requester instanceof Client && query.clientId() != null) {
            log.debug("Ignoring client_id query parameter for a client requester");
        }
        PageResult<NotificationEvent> page = queries.findPage(scoped);
        auditIfOperator(requester, new OperatorAction(
                requester.keyName(),
                Action.LIST_NOTIFICATIONS,
                null,
                TenantScope.auditClientId(requester, query.clientId()),
                "accepted",
                correlationId));
        return page;
    }

    @Override
    public NotificationEventDetails get(Requester requester, UUID notificationEventId, String correlationId) {
        return TenantScope.findVisible(queries, requester, notificationEventId)
                .map(notification -> {
                    var details = new NotificationEventDetails(notification, queries.findAttempts(notificationEventId));
                    auditIfOperator(requester, new OperatorAction(
                            requester.keyName(),
                            Action.GET_NOTIFICATION,
                            notificationEventId,
                            notification.clientId(),
                            "accepted",
                            correlationId));
                    return details;
                })
                .orElseGet(() -> {
                    auditIfOperator(requester, new OperatorAction(
                            requester.keyName(),
                            Action.GET_NOTIFICATION,
                            notificationEventId,
                            TenantScope.auditClientId(requester, null),
                            "not_found",
                            correlationId));
                    throw new NotificationEventNotFoundException(notificationEventId);
                });
    }

    private void auditIfOperator(Requester requester, OperatorAction action) {
        if (requester instanceof Operator) {
            auditLog.record(action);
        }
    }
}
