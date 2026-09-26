package com.cobre.notification.application.service;

import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryCompletion;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.ExpiredLease;
import com.cobre.notification.application.port.out.LeaseRecovery;
import com.cobre.notification.domain.model.AttemptTrigger;
import com.cobre.notification.domain.model.DeliveryTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

final class InMemoryDeliveries implements DeliveryRepository {

    static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");

    final List<DeliveryTask> due = new ArrayList<>();
    final List<ClaimRequest> claims = new ArrayList<>();
    final List<DeliveryCompletion> completions = new CopyOnWriteArrayList<>();
    final List<ExpiredLease> expired = new ArrayList<>();
    final List<LeaseRecovery> recoveries = new ArrayList<>();
    final List<RevertedClaim> reverts = new ArrayList<>();
    boolean leaseLost;
    boolean recoveryLost;
    boolean revertLost;
    RuntimeException recordFailure;
    RuntimeException claimFailure;
    RuntimeException revertFailure;

    DeliveryTask addDueTask() {
        return addDueTask(1);
    }

    DeliveryTask addDueTask(int cycleAttemptNumber) {
        DeliveryTask task = new DeliveryTask(UUID.randomUUID(), null, cycleAttemptNumber, cycleAttemptNumber,
                AttemptTrigger.of(cycleAttemptNumber, 0), "EVT-" + UUID.randomUUID(), "CLIENT001",
                "credit_card_payment", "content", NOW, "https://client.example/hook", NOW);
        due.add(task);
        return task;
    }

    @Override
    public List<DeliveryTask> claimDue(ClaimRequest request) {
        claims.add(request);
        if (claimFailure != null) {
            throw claimFailure;
        }
        List<DeliveryTask> claimed = new ArrayList<>();
        for (int i = 0; i < Math.min(request.limit(), due.size()); i++) {
            DeliveryTask task = due.get(i);
            claimed.add(new DeliveryTask(task.notificationEventId(), request.attemptIds().get(i),
                    task.attemptNumber(), task.cycleAttemptNumber(), task.trigger(), task.eventId(),
                    task.clientId(), task.eventType(), task.content(), task.eventCreatedAt(), task.webhookUrl(),
                    request.now(), task.signingSecret(), task.nextAttemptAtBeforeClaim(),
                    task.lastAttemptAtBeforeClaim()));
        }
        due.subList(0, claimed.size()).clear();
        return claimed;
    }

    @Override
    public boolean revertClaim(DeliveryTask task, String workerId, Instant now) {
        if (revertFailure != null) {
            throw revertFailure;
        }
        reverts.add(new RevertedClaim(task, workerId, now));
        return !revertLost;
    }

    @Override
    public boolean recordResult(DeliveryCompletion completion) {
        if (recordFailure != null) {
            throw recordFailure;
        }
        completions.add(completion);
        return !leaseLost;
    }

    @Override
    public List<ExpiredLease> findExpiredLeases(Instant now, int limit) {
        return List.copyOf(expired.subList(0, Math.min(limit, expired.size())));
    }

    @Override
    public boolean recoverLease(LeaseRecovery recovery) {
        recoveries.add(recovery);
        return !recoveryLost;
    }

    record RevertedClaim(DeliveryTask task, String workerId, Instant now) {
    }
}
