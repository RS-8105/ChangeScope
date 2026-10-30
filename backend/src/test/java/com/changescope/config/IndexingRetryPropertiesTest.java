package com.changescope.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingRetryPropertiesTest {

    @Test
    @DisplayName("Default properties have expected values and pass validation")
    void defaultProperties_passValidation() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        props.validate();

        assertThat(props.getMaxAttempts()).isEqualTo(3);
        assertThat(props.getInitialDelay()).isEqualTo(Duration.ofSeconds(10));
        assertThat(props.getMultiplier()).isEqualTo(2.0);
        assertThat(props.getMaxDelay()).isEqualTo(Duration.ofMinutes(15));
        assertThat(props.getJitterFactor()).isEqualTo(0.2);
        assertThat(props.getCheckInterval()).isEqualTo(Duration.ofSeconds(30));
        assertThat(props.getBatchSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("Validation fails when maxAttempts is less than 1")
    void validation_nonPositiveMaxAttempts_throwsException() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        assertThatThrownBy(() -> props.setMaxAttempts(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxAttempts must be at least 1");
    }

    @Test
    @DisplayName("Validation fails on non-positive initialDelay")
    void validation_nonPositiveInitialDelay_throwsException() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        assertThatThrownBy(() -> props.setInitialDelay(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("initialDelay must be a positive duration");
    }

    @Test
    @DisplayName("Validation fails when multiplier is less than 1.0")
    void validation_invalidMultiplier_throwsException() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        assertThatThrownBy(() -> props.setMultiplier(0.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("multiplier must be at least 1.0");
    }

    @Test
    @DisplayName("Validation fails when jitterFactor is outside [0.0, 1.0]")
    void validation_invalidJitterFactor_throwsException() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        assertThatThrownBy(() -> props.setJitterFactor(1.5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("jitterFactor must be between 0.0 and 1.0");
    }

    @Test
    @DisplayName("calculateNextRetryAt prioritizes future resetAt timestamp")
    void calculateNextRetryAt_prioritizesResetAt() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        Instant futureReset = now.plusSeconds(120);

        Instant nextRetry = props.calculateNextRetryAt(0, now, futureReset, Duration.ofSeconds(10));
        assertThat(nextRetry).isEqualTo(futureReset);
    }

    @Test
    @DisplayName("calculateNextRetryAt prioritizes retryAfter duration when resetAt is absent")
    void calculateNextRetryAt_prioritizesRetryAfter() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        Duration retryAfter = Duration.ofSeconds(45);

        Instant nextRetry = props.calculateNextRetryAt(0, now, null, retryAfter);
        assertThat(nextRetry).isEqualTo(now.plus(retryAfter));
    }

    @Test
    @DisplayName("calculateNextRetryAt applies exponential backoff bounded by maxDelay")
    void calculateNextRetryAt_appliesExponentialBackoffAndMaxDelayCap() {
        IndexingRetryProperties props = new IndexingRetryProperties();
        props.setInitialDelay(Duration.ofSeconds(10));
        props.setMultiplier(2.0);
        props.setMaxDelay(Duration.ofSeconds(30));
        props.setJitterFactor(0.0); // Disable jitter for deterministic calculation

        Instant now = Instant.parse("2026-10-30T10:00:00Z");

        // Attempt 0: 10 * 2^0 = 10s
        assertThat(props.calculateNextRetryAt(0, now, null, null)).isEqualTo(now.plusSeconds(10));

        // Attempt 1: 10 * 2^1 = 20s
        assertThat(props.calculateNextRetryAt(1, now, null, null)).isEqualTo(now.plusSeconds(20));

        // Attempt 2: 10 * 2^2 = 40s -> capped at maxDelay (30s)
        assertThat(props.calculateNextRetryAt(2, now, null, null)).isEqualTo(now.plusSeconds(30));
    }
}
