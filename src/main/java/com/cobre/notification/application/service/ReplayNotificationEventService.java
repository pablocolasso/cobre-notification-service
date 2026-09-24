package com.cobre.notification.application.service;

import com.cobre.notification.application.NotReplayableException;
import com.cobre.notification.application.NotificationEventNotFoundException;
import com.cobre.notification.application.SubscriptionInactiveException;
import com.cobre.notification.application.port.in.NotificationEventDetails;
import com.cobre.notification.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.port.in.Requester.Operator;
import com.cobre.notification.application.port.out.AuditLog;
import com.cobre.notification.application.port.out.AuditLog.Action;
import com.cobre.notification.application.port.out.AuditLog.OperatorAction;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.NotificationMetrics;
import com.cobre.notification.application.port.out.ReplayCommand;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.Subscription;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

public class ReplayNotificationEventService implements ReplayNotificationEventUseCase {

    private final NotificationEventQueryRepository queries;
    private final NotificationEventRepository notifications;
    private final SubscriptionRepository subscriptions;
    private final AuditLog auditLog;
    private final Clock clock;
    private final NotificationMetrics metrics;

    public ReplayNotificationEventService(NotificationEventQueryRepository queries,
                                          NotificationEventRepository notifications,
                                          SubscriptionRepository subscriptions,
                                          AuditLog auditLog,
                                          Clock clock,
                                          NotificationMetrics metrics) {
        this.queries = queries;
        this.notifications = notifications;
        this.subscriptions = subscriptions;
        this.auditLog = auditLog;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public NotificationEventDetails replay(Requester requester, UUID notificationEventId, String correlationId) {
        NotificationEvent existing = TenantScope.findVisible(queries, requester, notificationEventId)
                .orElseThrow(() -> {
                    audit(requester, notificationEventId, TenantScope.auditClientId(requester, null), "not_found",
                            correlationId);
                    metrics.replay("not_found");
                    return new NotificationEventNotFoundException(notificationEventId);
                });

        if (existing.status() != DeliveryStatus.FAILED) {
            audit(requester, notificationEventId, existing.clientId(), "not_replayable", correlationId);
            metrics.replay("not_replayable");
            throw new NotReplayableException();
        }

        Subscription subscription = subscriptions.findActive(existing.clientId(), existing.eventType())
                .orElseThrow(() -> {
                    audit(requester, notificationEventId, existing.clientId(), "subscription_inactive", correlationId);
                    metrics.replay("not_replayable");
                    return new SubscriptionInactiveException();
                });

        Instant now = clock.instant();
        existing.replay(subscription.webhookUrl(), now);

        String tenant = switch (requester) {
            case Requester.Client client -> client.clientId();
            case Operator ignored -> null;
        };
        boolean updated = notifications.requestReplay(new ReplayCommand(
                notificationEventId, tenant, subscription.webhookUrl(), now));
        if (!updated) {
            audit(requester, notificationEventId, existing.clientId(), "not_replayable", correlationId);
            metrics.replay("not_replayable");
            throw new NotReplayableException();
        }

        audit(requester, notificationEventId, existing.clientId(), "accepted", correlationId);
        metrics.replay("accepted");
        NotificationEvent replayed = TenantScope.findVisible(queries, requester, notificationEventId)
                .orElseThrow(() -> new NotificationEventNotFoundException(notificationEventId));
        return new NotificationEventDetails(replayed, queries.findAttempts(notificationEventId));
    }

    private void audit(Requester requester, UUID notificationEventId, String clientId, String outcome,
                       String correlationId) {
        if (requester instanceof Operator) {
            auditLog.record(new OperatorAction(
                    requester.keyName(),
                    Action.REPLAY_NOTIFICATION,
                    notificationEventId,
                    clientId,
                    outcome,
                    correlationId));
        }
    }
}
