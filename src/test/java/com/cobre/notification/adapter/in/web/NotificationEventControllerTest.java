package com.cobre.notification.adapter.in.web;

import com.cobre.notification.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(OutputCaptureExtension.class)
class NotificationEventControllerTest extends AbstractIntegrationTest {

    static final String CLIENT_001 = "test-client-001";
    static final String CLIENT_002 = "test-client-002";
    static final String OPS = "test-ops";

    private static final Instant T1 = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-21T10:00:00Z");

    private static final UUID LOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NEWEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID FAILED_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ORPHAN_FAILED_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void seed() {
        deleteAllNotifications();
        jdbcClient.sql("DELETE FROM subscriptions").update();
        insertCompleted(LOW_ID, "EVT-A", "CLIENT001", T1);
        insertCompleted(HIGH_ID, "EVT-B", "CLIENT001", T1);
        insertCompleted(NEWEST_ID, "EVT-C", "CLIENT001", T2);
        insertCompleted(OTHER_CLIENT_ID, "EVT-D", "CLIENT002", T2);
        insertFailed(FAILED_ID, "EVT-FAIL", "CLIENT002", "credit_transfer");
        insertFailed(ORPHAN_FAILED_ID, "EVT-ORPHAN", "CLIENT002", "no_such_type");
        upsertSubscription("CLIENT002", "credit_transfer", "https://hooks.example.com/current");
    }

    @Test
    void missingAndInvalidKeysReturnTheSameUnauthorizedBody() throws Exception {
        MvcResult missing = mockMvc.perform(get("/notification_events"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn();
        MvcResult invalid = mockMvc.perform(get("/notification_events").header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andReturn();
        assertThat(missing.getResponse().getContentAsString()).isEqualTo(invalid.getResponse().getContentAsString());
        assertThat(missing.getResponse().getContentAsString()).doesNotContain("nope");
    }

    @Test
    void listsNewestFirstWithIdAsTieBreaker() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_001))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].notification_event_id")
                        .value(contains(NEWEST_ID.toString(), HIGH_ID.toString(), LOW_ID.toString())))
                .andExpect(jsonPath("$.total_elements").value(3));
    }

    @Test
    void paginatesDeterministically() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_001)
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].notification_event_id").value(LOW_ID.toString()))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.total_elements").value(3))
                .andExpect(jsonPath("$.total_pages").value(2));
    }

    @Test
    void listItemsUseSnakeCaseLowercaseStatusAndNoDeliveryDate() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_002)
                        .param("delivery_status", "completed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].event_id").value("EVT-D"))
                .andExpect(jsonPath("$.items[0].client_id").value("CLIENT002"))
                .andExpect(jsonPath("$.items[0].event_type").value("credit_transfer"))
                .andExpect(jsonPath("$.items[0].content").value("Transfer received"))
                .andExpect(jsonPath("$.items[0].delivery_status").value("completed"))
                .andExpect(jsonPath("$.items[0].event_created_at").value("2026-09-21T10:00:00Z"))
                .andExpect(jsonPath("$.items[0].delivered_at").exists())
                .andExpect(jsonPath("$.items[0]", not(hasKey("delivery_date"))))
                .andExpect(jsonPath("$.items[0]", not(hasKey("webhook_url"))));
    }

    @Test
    void clientIgnoresForeignClientIdParameter() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_001)
                        .param("client_id", "CLIENT002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(3))
                .andExpect(jsonPath("$.items[*].client_id", contains("CLIENT001", "CLIENT001", "CLIENT001")));
    }

    @Test
    void operatorWithoutClientIdSeesEveryTenant() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(6));
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS).param("client_id", ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(6));
    }

    @Test
    void operatorCanFilterByClientId() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS).param("client_id", "CLIENT002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(3))
                .andExpect(jsonPath("$.items[*].client_id", contains("CLIENT002", "CLIENT002", "CLIENT002")));
    }

    @Test
    void filtersByCreatedWindowAndKeepsDeterministicOrder() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_001)
                        .param("created_from", "2026-09-20T10:00:00Z")
                        .param("created_to", "2026-09-21T10:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].notification_event_id")
                        .value(contains(HIGH_ID.toString(), LOW_ID.toString())))
                .andExpect(jsonPath("$.total_elements").value(2));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS).param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsDetailsWithAttemptsAndMaskedWebhookUrl() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", OTHER_CLIENT_ID).header("X-API-Key", CLIENT_002))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notification_event_id").value(OTHER_CLIENT_ID.toString()))
                .andExpect(jsonPath("$.delivery_status").value("completed"))
                .andExpect(jsonPath("$.webhook_url").value("https://hooks.example.com"))
                .andExpect(jsonPath("$.attempt_count").value(1))
                .andExpect(jsonPath("$", not(hasKey("delivery_date"))))
                .andExpect(jsonPath("$.delivery_attempts", hasSize(1)))
                .andExpect(jsonPath("$.delivery_attempts[0].attempt_number").value(1))
                .andExpect(jsonPath("$.delivery_attempts[0].trigger").value("initial"))
                .andExpect(jsonPath("$.delivery_attempts[0].status").value("success"))
                .andExpect(jsonPath("$.delivery_attempts[0].http_status").value(200))
                .andExpect(jsonPath("$.delivery_attempts[0]", not(hasKey("webhook_url"))));
    }

    @Test
    void crossTenantDetailAndReplayAreNotFound() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", OTHER_CLIENT_ID).header("X-API-Key", CLIENT_001))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("notification_event_not_found"));
        mockMvc.perform(post("/notification_events/{id}/replay", OTHER_CLIENT_ID).header("X-API-Key", CLIENT_001))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("notification_event_not_found"));
    }

    @Test
    void unknownIdReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", UUID.randomUUID()).header("X-API-Key", OPS))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("notification_event_not_found"));
    }

    @Test
    void malformedIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", "not-a-uuid").header("X-API-Key", OPS))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void replayOfNonFailedIsConflict() throws Exception {
        mockMvc.perform(post("/notification_events/{id}/replay", OTHER_CLIENT_ID).header("X-API-Key", OPS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("not_replayable"));
    }

    @Test
    void replayWithoutActiveSubscriptionIsConflict() throws Exception {
        mockMvc.perform(post("/notification_events/{id}/replay", ORPHAN_FAILED_ID).header("X-API-Key", OPS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("subscription_inactive"));
    }

    @Test
    void replayOfFailedReturnsAcceptedAndResetsTheCycle() throws Exception {
        mockMvc.perform(post("/notification_events/{id}/replay", FAILED_ID).header("X-API-Key", OPS))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.delivery_status").value("pending"))
                .andExpect(jsonPath("$.replay_count").value(1))
                .andExpect(jsonPath("$.attempt_count").value(1))
                .andExpect(jsonPath("$.delivery_attempts", hasSize(1)));
        assertThat(jdbcClient.sql("SELECT cycle_attempt_count FROM notification_events WHERE id = :id")
                .param("id", FAILED_ID).query(Integer.class).single()).isZero();
        assertThat(jdbcClient.sql("SELECT webhook_url FROM notification_events WHERE id = :id")
                .param("id", FAILED_ID).query(String.class).single()).isEqualTo("https://hooks.example.com/current");
    }

    @Test
    void operatorActionsAreAuditedWithoutContent(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", OPS)
                        .header("X-Request-Id", "corr-list")
                        .param("client_id", "CLIENT002"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/notification_events/{id}", OTHER_CLIENT_ID).header("X-API-Key", OPS)
                        .header("X-Request-Id", "corr-get"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/notification_events/{id}/replay", OTHER_CLIENT_ID).header("X-API-Key", OPS)
                        .header("X-Request-Id", "corr-replay"))
                .andExpect(status().isConflict());
        mockMvc.perform(get("/notification_events/{id}", UUID.randomUUID()).header("X-API-Key", OPS)
                        .header("X-Request-Id", "corr-missing"))
                .andExpect(status().isNotFound());

        String logs = output.getOut();
        assertThat(logs).contains("audit_event=\"operator_action\"");
        assertThat(logs).contains("operator=\"ops\"");
        assertThat(logs).contains("action=\"LIST_NOTIFICATIONS\"");
        assertThat(logs).contains("action=\"GET_NOTIFICATION\"");
        assertThat(logs).contains("action=\"REPLAY_NOTIFICATION\"");
        assertThat(logs).contains("client_id=\"CLIENT002\"");
        assertThat(logs).contains("outcome=\"accepted\"");
        assertThat(logs).contains("outcome=\"not_replayable\"");
        assertThat(logs).contains("outcome=\"not_found\"");
        assertThat(logs).contains("correlation_id=\"corr-list\"");
        assertThat(logs).doesNotContain("Transfer received");
    }

    @Test
    void clientDoesNotEmitOperatorAudit(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/notification_events").header("X-API-Key", CLIENT_001)).andExpect(status().isOk());
        mockMvc.perform(get("/notification_events/{id}", NEWEST_ID).header("X-API-Key", CLIENT_001))
                .andExpect(status().isOk());

        assertThat(output.getOut()).doesNotContain("audit_event=\"operator_action\"");
    }

    private void insertCompleted(UUID id, String eventId, String clientId, Instant eventCreatedAt) {
        Timestamp createdAt = Timestamp.from(eventCreatedAt);
        Timestamp deliveredAt = Timestamp.from(eventCreatedAt.plusSeconds(1));
        String webhookUrl = "https://hooks.example.com/cobre/secret-token?sig=abc";
        jdbcClient.sql("""
                        INSERT INTO notification_events (
                            id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                            delivery_status, attempt_count, cycle_attempt_count, last_attempt_at, delivered_at,
                            last_http_status, origin)
                        VALUES (:id, :eventId, :clientId, 'credit_transfer', 'Transfer received', :createdAt,
                                :webhookUrl, 'COMPLETED', 1, 1, :createdAt, :deliveredAt, 200, 'KAFKA')
                        """)
                .param("id", id)
                .param("eventId", eventId)
                .param("clientId", clientId)
                .param("createdAt", createdAt)
                .param("webhookUrl", webhookUrl)
                .param("deliveredAt", deliveredAt)
                .update();
        insertAttempt(id, webhookUrl, createdAt, deliveredAt, "SUCCESS", 200, null);
    }

    private void insertFailed(UUID id, String eventId, String clientId, String eventType) {
        Timestamp createdAt = Timestamp.from(T2);
        String webhookUrl = "https://hooks.example.com/old";
        jdbcClient.sql("""
                        INSERT INTO notification_events (
                            id, event_id, client_id, event_type, content, event_created_at, webhook_url,
                            delivery_status, attempt_count, cycle_attempt_count, last_attempt_at, last_error, origin)
                        VALUES (:id, :eventId, :clientId, :eventType, 'Transfer received', :createdAt,
                                :webhookUrl, 'FAILED', 1, 1, :createdAt, 'http_status: HTTP 500', 'KAFKA')
                        """)
                .param("id", id)
                .param("eventId", eventId)
                .param("clientId", clientId)
                .param("eventType", eventType)
                .param("createdAt", createdAt)
                .param("webhookUrl", webhookUrl)
                .update();
        insertAttempt(id, webhookUrl, createdAt, createdAt, "PERMANENT_FAILURE", 500, "http_status");
    }

    private void insertAttempt(UUID id, String webhookUrl, Timestamp started, Timestamp completed, String status,
                               Integer httpStatus, String errorCode) {
        jdbcClient.sql("""
                        INSERT INTO delivery_attempts (
                            id, notification_event_id, attempt_number, attempt_trigger, webhook_url, status,
                            http_status, error_code, started_at, completed_at, duration_ms)
                        VALUES (:attemptId, :id, 1, 'INITIAL', :webhookUrl, :status, :httpStatus, :errorCode,
                                :started, :completed, 1000)
                        """)
                .param("attemptId", UUID.randomUUID())
                .param("id", id)
                .param("webhookUrl", webhookUrl)
                .param("status", status)
                .param("httpStatus", httpStatus)
                .param("errorCode", errorCode)
                .param("started", started)
                .param("completed", completed)
                .update();
    }

    private void upsertSubscription(String clientId, String eventType, String webhookUrl) {
        jdbcClient.sql("""
                        INSERT INTO subscriptions (id, client_id, event_type, webhook_url, active)
                        VALUES (:id, :clientId, :eventType, :webhookUrl, TRUE)
                        """)
                .param("id", UUID.randomUUID())
                .param("clientId", clientId)
                .param("eventType", eventType)
                .param("webhookUrl", webhookUrl)
                .update();
    }
}
