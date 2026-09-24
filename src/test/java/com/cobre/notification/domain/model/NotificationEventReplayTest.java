package com.cobre.notification.domain.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationEventReplayTest {

    private static final Instant CREATED = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-09-24T12:00:00Z");
    private static final String CURRENT_URL = "https://hooks.example.com/current";

    @Test
    void replayFromFailedResetsTheCycleAndKeepsAttemptCount() {
        NotificationEvent replayed = failed(3, 3, 2, "https://hooks.example.com/old").replay(CURRENT_URL, NOW);

        assertThat(replayed.status()).isEqualTo(DeliveryStatus.PENDING);
        assertThat(replayed.cycleAttemptCount()).isZero();
        assertThat(replayed.replayCount()).isEqualTo(3);
        assertThat(replayed.attemptCount()).isEqualTo(3);
        assertThat(replayed.webhookUrl()).isEqualTo(CURRENT_URL);
        assertThat(replayed.nextAttemptAt()).isEqualTo(NOW);
        assertThat(replayed.updatedAt()).isEqualTo(NOW);
        assertThat(replayed.content()).isEqualTo("secret-content");
    }

    @ParameterizedTest
    @EnumSource(value = DeliveryStatus.class, names = "FAILED", mode = EnumSource.Mode.EXCLUDE)
    void replayIsRejectedUnlessTheNotificationFailed(DeliveryStatus status) {
        NotificationEvent notification = notification(status, 1, 1, 0, "https://hooks.example.com/old");

        assertThatThrownBy(() -> notification.replay(CURRENT_URL, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(status.name());
        assertThat(notification.attemptCount()).isEqualTo(1);
        assertThat(notification.replayCount()).isZero();
        assertThat(notification.webhookUrl()).isEqualTo("https://hooks.example.com/old");
    }

    private static NotificationEvent failed(int attemptCount, int cycleAttemptCount, int replayCount, String url) {
        return notification(DeliveryStatus.FAILED, attemptCount, cycleAttemptCount, replayCount, url);
    }

    private static NotificationEvent notification(DeliveryStatus status, int attemptCount, int cycleAttemptCount,
                                                  int replayCount, String url) {
        Instant deliveredAt = status == DeliveryStatus.COMPLETED ? CREATED : null;
        Instant nextAttemptAt = status == DeliveryStatus.PENDING || status == DeliveryStatus.RETRYING ? CREATED : null;
        return new NotificationEvent(
                UUID.randomUUID(),
                "EVT-R",
                UUID.randomUUID(),
                "CLIENT002",
                "credit_transfer",
                "secret-content",
                CREATED,
                url,
                status,
                attemptCount,
                cycleAttemptCount,
                replayCount,
                nextAttemptAt,
                CREATED,
                deliveredAt,
                status == DeliveryStatus.FAILED ? 500 : 200,
                status == DeliveryStatus.FAILED ? "http_status: HTTP 500" : null,
                NotificationOrigin.KAFKA,
                CREATED,
                CREATED);
    }
}
