package com.cobre.notification;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class OpenApiSmokeTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void openApiDocumentIsServed() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.components.securitySchemes.apiKey.name").value("X-API-Key"))
                .andExpect(jsonPath("$.paths['/notification_events'].get").exists())
                .andExpect(jsonPath("$.paths['/notification_events/{notification_event_id}'].get").exists())
                .andExpect(jsonPath("$.paths['/notification_events/{notification_event_id}/replay'].post").exists())
                .andExpect(jsonPath("$.paths['/notification_events'].get.parameters[*].name",
                        not(hasItem("requester"))))
                .andExpect(jsonPath("$.paths['/notification_events'].get.parameters[*].name",
                        not(hasItem("correlationId"))))
                .andExpect(jsonPath("$.paths['/notification_events'].get.parameters[*].name",
                        hasItem("X-Request-Id")))
                .andExpect(jsonPath("$.paths['/notification_events/{notification_event_id}'].get.parameters[*].name",
                        not(hasItem("requester"))));
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

}
