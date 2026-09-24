package com.cobre.notification.adapter.in.web;

import com.cobre.notification.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NotificationEventControllerTest extends AbstractIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-21T10:00:00Z");

    private static final UUID LOW_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID HIGH_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID NEWEST_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID OTHER_CLIENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    @Autowired
    MockMvc mockMvc;

    @BeforeEach
    void seed() {
        deleteAllNotifications();
        insertCompleted(LOW_ID, "EVT-A", "CLIENT001", T1);
        insertCompleted(HIGH_ID, "EVT-B", "CLIENT001", T1);
        insertCompleted(NEWEST_ID, "EVT-C", "CLIENT001", T2);
        insertCompleted(OTHER_CLIENT_ID, "EVT-D", "CLIENT002", T2);
    }

    @Test
    void listsNewestFirstWithIdAsTieBreaker() throws Exception {
        mockMvc.perform(get("/notification_events").param("client_id", "CLIENT001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].notification_event_id")
                        .value(contains(NEWEST_ID.toString(), HIGH_ID.toString(), LOW_ID.toString())))
                .andExpect(jsonPath("$.total_elements").value(3));
    }

    @Test
    void paginatesDeterministically() throws Exception {
        mockMvc.perform(get("/notification_events").param("client_id", "CLIENT001")
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
        mockMvc.perform(get("/notification_events").param("client_id", "CLIENT002"))
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
    void withoutClientFilterListsAllClients() throws Exception {
        mockMvc.perform(get("/notification_events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_elements").value(4));
    }

    @Test
    void rejectsInvalidPagination() throws Exception {
        mockMvc.perform(get("/notification_events").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        mockMvc.perform(get("/notification_events").param("page", "-1"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/notification_events").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsDetailsWithAttemptsAndMaskedWebhookUrl() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", OTHER_CLIENT_ID))
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
    void unknownIdReturnsProblemDetail() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("notification_event_not_found"));
    }

    @Test
    void malformedIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/notification_events/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
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
        jdbcClient.sql("""
                        INSERT INTO delivery_attempts (
                            id, notification_event_id, attempt_number, attempt_trigger, webhook_url, status,
                            http_status, started_at, completed_at, duration_ms)
                        VALUES (:attemptId, :id, 1, 'INITIAL', :webhookUrl, 'SUCCESS', 200, :createdAt,
                                :deliveredAt, 1000)
                        """)
                .param("attemptId", UUID.randomUUID())
                .param("id", id)
                .param("webhookUrl", webhookUrl)
                .param("createdAt", createdAt)
                .param("deliveredAt", deliveredAt)
                .update();
    }
}
