package com.cobre.notification.application.service;

import com.cobre.notification.application.NotReplayableException;
import com.cobre.notification.application.NotificationEventNotFoundException;
import com.cobre.notification.application.SubscriptionInactiveException;
import com.cobre.notification.application.port.in.NotificationEventDetails;
import com.cobre.notification.application.port.in.NotificationEventQuery;
import com.cobre.notification.application.port.in.PageResult;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.port.out.AuditLog;
import com.cobre.notification.application.port.out.AuditLog.Action;
import com.cobre.notification.application.port.out.AuditLog.OperatorAction;
import com.cobre.notification.application.port.out.NotificationEventQueryRepository;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.ReplayCommand;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.DeliveryAttempt;
import com.cobre.notification.domain.model.DeliveryStatus;
import com.cobre.notification.domain.model.NotificationEvent;
import com.cobre.notification.domain.model.NotificationOrigin;
import com.cobre.notification.domain.model.Subscription;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplayNotificationEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final Requester.Operator OPS = new Requester.Operator("ops");
    private static final Requester.Client CLIENT_A = new Requester.Client("client-001", "CLIENT001");

    private final Store store = new Store();
    private final RecordingAuditLog audit = new RecordingAuditLog();
    private final ReplayNotificationEventService service = new ReplayNotificationEventService(
            store, store, store, audit, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void replaysAFailedNotificationAndAuditsAcceptance() {
        store.put(row("CLIENT002", DeliveryStatus.FAILED));
        store.put(new Subscription(UUID.randomUUID(), "CLIENT002", "credit_transfer",
                "https://hooks.example.com/current", true));

        NotificationEventDetails details = service.replay(OPS, ID, "corr-1");

        assertThat(details.notification().status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(details.notification().cycleAttemptCount()).isZero();
        assertThat(details.notification().replayCount()).isEqualTo(1);
        assertThat(details.notification().attemptCount()).isEqualTo(2);
        assertThat(details.notification().webhookUrl()).isEqualTo("https://hooks.example.com/current");
        assertThat(store.lastCommand.now()).isEqualTo(NOW);
        assertThat(audit.actions).singleElement().satisfies(action -> {
            assertThat(action.action()).isEqualTo(Action.REPLAY_NOTIFICATION);
            assertThat(action.operator()).isEqualTo("ops");
            assertThat(action.outcome()).isEqualTo("accepted");
            assertThat(action.clientId()).isEqualTo("CLIENT002");
            assertThat(action.correlationId()).isEqualTo("corr-1");
            assertThat(action.notificationEventId()).isEqualTo(ID);
        });
    }

    @Test
    void missingRowIsNotFound() {
        assertThatThrownBy(() -> service.replay(OPS, ID, "corr-1"))
                .isInstanceOf(NotificationEventNotFoundException.class);
        assertThat(audit.outcomes()).containsExactly("not_found");
    }

    @Test
    void otherTenantIsNotFoundForAClient() {
        store.put(row("CLIENT002", DeliveryStatus.FAILED));

        assertThatThrownBy(() -> service.replay(CLIENT_A, ID, "corr-1"))
                .isInstanceOf(NotificationEventNotFoundException.class);
        assertThat(store.lastCommand).isNull();
        assertThat(audit.actions).isEmpty();
    }

    @Test
    void completedIsNotReplayable() {
        store.put(row("CLIENT002", DeliveryStatus.COMPLETED));

        assertThatThrownBy(() -> service.replay(OPS, ID, "corr-1"))
                .isInstanceOf(NotReplayableException.class);
        assertThat(audit.outcomes()).containsExactly("not_replayable");
    }

    @Test
    void missingSubscriptionIsInactive() {
        store.put(row("CLIENT002", DeliveryStatus.FAILED));

        assertThatThrownBy(() -> service.replay(OPS, ID, "corr-1"))
                .isInstanceOf(SubscriptionInactiveException.class);
        assertThat(audit.outcomes()).containsExactly("subscription_inactive");
    }

    @Test
    void lostRaceIsNotReplayable() {
        store.put(row("CLIENT002", DeliveryStatus.FAILED));
        store.put(new Subscription(UUID.randomUUID(), "CLIENT002", "credit_transfer",
                "https://hooks.example.com/current", true));
        store.succeedReplay = false;

        assertThatThrownBy(() -> service.replay(OPS, ID, "corr-1"))
                .isInstanceOf(NotReplayableException.class);
        assertThat(audit.outcomes()).containsExactly("not_replayable");
    }

    private static NotificationEvent row(String clientId, DeliveryStatus status) {
        Instant deliveredAt = status == DeliveryStatus.COMPLETED ? NOW : null;
        Instant nextAttemptAt = status == DeliveryStatus.PENDING ? NOW : null;
        return new NotificationEvent(
                ID, "EVT003", UUID.randomUUID(), clientId, "credit_transfer", "secret",
                NOW.minusSeconds(60), "https://hooks.example.com/old", status, 2, 2, 0,
                nextAttemptAt, NOW, deliveredAt, 500, "http_status: HTTP 500", NotificationOrigin.KAFKA, NOW, NOW);
    }

    private static final class RecordingAuditLog implements AuditLog {
        final List<OperatorAction> actions = new ArrayList<>();

        @Override
        public void record(OperatorAction action) {
            actions.add(action);
        }

        List<String> outcomes() {
            return actions.stream().map(OperatorAction::outcome).toList();
        }
    }

    private static final class Store implements NotificationEventQueryRepository, NotificationEventRepository,
            SubscriptionRepository {

        private final Map<UUID, NotificationEvent> byId = new HashMap<>();
        private final Map<String, Subscription> subscriptions = new HashMap<>();
        ReplayCommand lastCommand;
        boolean succeedReplay = true;

        void put(NotificationEvent notification) {
            byId.put(notification.id(), notification);
        }

        void put(Subscription subscription) {
            subscriptions.put(subscription.clientId() + "|" + subscription.eventType(), subscription);
        }

        @Override
        public PageResult<NotificationEvent> findPage(NotificationEventQuery query) {
            return new PageResult<>(List.of(), 0, query.size(), 0, 0);
        }

        @Override
        public Optional<NotificationEvent> findById(UUID id, String clientId) {
            return Optional.ofNullable(byId.get(id))
                    .filter(notification -> clientId == null || clientId.equals(notification.clientId()));
        }

        @Override
        public List<DeliveryAttempt> findAttempts(UUID notificationEventId) {
            return List.of();
        }

        @Override
        public boolean saveIfAbsent(NotificationEvent notification) {
            return byId.putIfAbsent(notification.id(), notification) == null;
        }

        @Override
        public boolean saveAttemptIfAbsent(DeliveryAttempt attempt) {
            return true;
        }

        @Override
        public boolean requestReplay(ReplayCommand command) {
            lastCommand = command;
            if (!succeedReplay) {
                return false;
            }
            NotificationEvent current = byId.get(command.notificationEventId());
            if (current == null || current.status() != DeliveryStatus.FAILED) {
                return false;
            }
            if (command.clientId() != null && !command.clientId().equals(current.clientId())) {
                return false;
            }
            byId.put(current.id(), current.replay(command.webhookUrl(), command.now()));
            return true;
        }

        @Override
        public Optional<Subscription> findActive(String clientId, String eventType) {
            return Optional.ofNullable(subscriptions.get(clientId + "|" + eventType));
        }

        @Override
        public void upsertActive(UUID id, String clientId, String eventType, String webhookUrl) {
            put(new Subscription(id, clientId, eventType, webhookUrl, true));
        }
    }
}
