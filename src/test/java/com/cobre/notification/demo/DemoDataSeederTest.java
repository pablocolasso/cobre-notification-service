package com.cobre.notification.demo;

import com.cobre.notification.AbstractIntegrationTest;
import com.cobre.notification.application.port.in.ReplayNotificationEventUseCase;
import com.cobre.notification.application.port.in.Requester;
import com.cobre.notification.application.port.out.ClaimRequest;
import com.cobre.notification.application.port.out.DeliveryRepository;
import com.cobre.notification.application.port.out.IdGenerator;
import com.cobre.notification.application.port.out.NotificationEventRepository;
import com.cobre.notification.application.port.out.SubscriptionRepository;
import com.cobre.notification.domain.model.DeliveryTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DemoDataSeederTest extends AbstractIntegrationTest {

    @Autowired
    SubscriptionRepository subscriptions;

    @Autowired
    NotificationEventRepository notifications;

    @Autowired
    IdGenerator ids;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    ReplayNotificationEventUseCase replay;

    @Autowired
    DeliveryRepository deliveries;

    @BeforeEach
    void clean() {
        deleteAllNotifications();
        jdbcClient.sql("DELETE FROM subscriptions").update();
        seedSubscriptions();
    }

    @Test
    void seedsTenRowsWithOneSyntheticAttemptAndIsIdempotent() throws Exception {
        DemoDataSeeder seeder = new DemoDataSeeder(subscriptions, notifications, ids, objectMapper);
        seeder.run(null);
        seeder.run(null);

        assertThat(jdbcClient.sql("SELECT count(*) FROM notification_events").query(Long.class).single()).isEqualTo(10);
        assertThat(jdbcClient.sql("SELECT count(*) FROM delivery_attempts").query(Long.class).single()).isEqualTo(10);
        List<Map<String, Object>> rows = jdbcClient.sql("""
                        SELECT n.event_id, n.attempt_count, n.cycle_attempt_count, n.origin, n.last_http_status,
                               a.error_code, count(a.id) AS attempts
                        FROM notification_events n
                        JOIN delivery_attempts a ON a.notification_event_id = n.id
                        GROUP BY n.event_id, n.attempt_count, n.cycle_attempt_count, n.origin, n.last_http_status,
                                 a.error_code
                        """).query().listOfRows();
        assertThat(rows).hasSize(10).allSatisfy(row -> {
            assertThat(row.get("attempt_count")).isEqualTo(1);
            assertThat(row.get("cycle_attempt_count")).isEqualTo(1);
            assertThat(row.get("origin")).isEqualTo("FIXTURE");
            assertThat(row.get("last_http_status")).isNull();
            assertThat(row.get("error_code")).isEqualTo("fixture_synthetic");
            assertThat(row.get("attempts")).isEqualTo(1L);
        });
        assertThat(jdbcClient.sql("""
                        SELECT last_error FROM notification_events
                        WHERE event_id IN ('EVT003', 'EVT005', 'EVT009')
                        """).query(String.class).list()).containsOnly("fixture_synthetic");
    }

    @Test
    void replayOfAFailedSeedCreatesAttemptTwoWithReplayTrigger() throws Exception {
        new DemoDataSeeder(subscriptions, notifications, ids, objectMapper).run(null);
        UUID id = jdbcClient.sql("SELECT id FROM notification_events WHERE event_id = 'EVT003'")
                .query(UUID.class).single();

        replay.replay(new Requester.Operator("ops"), id, "corr-seed");

        Instant now = Instant.now().plusSeconds(1);
        List<DeliveryTask> claimed = deliveries.claimDue(new ClaimRequest(
                "test-worker", List.of(UUID.randomUUID()), now, now.plus(Duration.ofSeconds(60))));

        assertThat(claimed).singleElement().satisfies(task -> {
            assertThat(task.notificationEventId()).isEqualTo(id);
            assertThat(task.attemptNumber()).isEqualTo(2);
            assertThat(task.trigger().name()).isEqualTo("REPLAY");
        });
        assertThat(jdbcClient.sql("""
                        SELECT attempt_number, attempt_trigger FROM delivery_attempts
                        WHERE notification_event_id = :id ORDER BY attempt_number
                        """).param("id", id).query().listOfRows())
                .extracting(row -> row.get("attempt_number") + " " + row.get("attempt_trigger"))
                .containsExactly("1 INITIAL", "2 REPLAY");
    }

    private void seedSubscriptions() {
        List<String> pairs = List.of(
                "CLIENT001|credit_card_payment",
                "CLIENT001|debit_card_withdrawal",
                "CLIENT001|credit_deposit",
                "CLIENT001|debit_subscription",
                "CLIENT002|credit_transfer",
                "CLIENT002|debit_automatic_payment",
                "CLIENT002|debit_purchase",
                "CLIENT003|credit_refund",
                "CLIENT003|debit_transfer",
                "CLIENT003|credit_cashback");
        for (String pair : pairs) {
            String[] parts = pair.split("\\|");
            subscriptions.upsertActive(UUID.randomUUID(), parts[0], parts[1], "https://hooks.example.com/ok");
        }
    }
}
