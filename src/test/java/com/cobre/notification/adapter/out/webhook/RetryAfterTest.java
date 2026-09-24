package com.cobre.notification.adapter.out.webhook;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RetryAfterTest {

    @ParameterizedTest
    @CsvSource({"0, 0", "2, 2", "' 120 ', 120", "999999999, 999999999"})
    void parsesDelaySeconds(String header, long expectedSeconds) {
        assertThat(RetryAfter.parse(header)).isEqualTo(Duration.ofSeconds(expectedSeconds));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "-1", "1.5", "abc", "1000000000", "Wed, 21 Oct 2015 07:28:00 GMT"})
    void ignoresAnythingElse(String header) {
        assertThat(RetryAfter.parse(header)).isNull();
    }
}
