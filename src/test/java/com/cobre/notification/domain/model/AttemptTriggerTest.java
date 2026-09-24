package com.cobre.notification.domain.model;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class AttemptTriggerTest {

    @ParameterizedTest(name = "cycle attempt {0}, replays {1} -> {2}")
    @CsvSource({
            "1, 0, INITIAL",
            "2, 0, RETRY",
            "5, 0, RETRY",
            "1, 1, REPLAY",
            "2, 1, RETRY",
            "1, 3, REPLAY"
    })
    void firstAttemptOfACycleIsInitialOrReplayAndTheRestAreRetries(int cycleAttempt, int replays,
                                                                   AttemptTrigger expected) {
        assertThat(AttemptTrigger.of(cycleAttempt, replays)).isEqualTo(expected);
    }
}
