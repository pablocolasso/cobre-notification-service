package com.cobre.notification;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class CobreNotificationServiceApplicationTests extends AbstractIntegrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void contextLoadsAndFlywayAppliesInitialSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");

        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("subscriptions", "notification_events", "delivery_attempts");
    }

}
