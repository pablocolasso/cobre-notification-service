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
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");

        var tables = jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'",
                String.class);

        assertThat(tables).contains("subscriptions", "notification_events", "delivery_attempts");
        assertThat(jdbcTemplate.queryForObject(
                """
                        SELECT count(*) FROM information_schema.columns
                        WHERE table_name = 'subscriptions' AND column_name = 'signing_secret'
                        """,
                Integer.class)).isEqualTo(1);
    }

}
