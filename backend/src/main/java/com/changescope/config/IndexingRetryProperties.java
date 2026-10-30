package com.changescope.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Configuration properties for bounded exponential backoff retries of transient repository indexing failures.
 */
@Configuration
@ConfigurationProperties(prefix = "indexing.retry")
public class IndexingRetryProperties {

    /**
     * Maximum number of retry attempts before giving up on a transient failure. Defaults to 3.
     */
    private int maxAttempts = 3;

    /**
     * Initial backoff delay for the first retry attempt. Defaults to 10 seconds.
     */
    private Duration initialDelay = Duration.ofSeconds(10);

    /**
     * Multiplier applied to the backoff delay on each subsequent retry attempt. Defaults to 2.0.
     */
    private double multiplier = 2.0;

    /**
     * Maximum backoff delay cap between successive retries. Defaults to 15 minutes.
     */
    private Duration maxDelay = Duration.ofMinutes(15);

    /**
     * Jitter factor (0.0 to 1.0) applied to prevent synchronized retry storms. Defaults to 0.2 (+/- 20%).
     */
    private double jitterFactor = 0.2;

    /**
     * Polling interval between background reconciliation checks for due retries. Defaults to 30 seconds.
     */
    private Duration checkInterval = Duration.ofSeconds(30);

    /**
     * Maximum number of due retries to claim and dispatch in a single reconciliation cycle. Defaults to 10.
     */
    private int batchSize = 10;

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialDelay() {
        return initialDelay;
    }

    public void setInitialDelay(Duration initialDelay) {
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be a positive duration");
        }
        this.initialDelay = initialDelay;
    }

    public double getMultiplier() {
        return multiplier;
    }

    public void setMultiplier(double multiplier) {
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        this.multiplier = multiplier;
    }

    public Duration getMaxDelay() {
        return maxDelay;
    }

    public void setMaxDelay(Duration maxDelay) {
        if (maxDelay == null || maxDelay.isNegative() || maxDelay.isZero()) {
            throw new IllegalArgumentException("maxDelay must be a positive duration");
        }
        this.maxDelay = maxDelay;
    }

    public double getJitterFactor() {
        return jitterFactor;
    }

    public void setJitterFactor(double jitterFactor) {
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
        }
        this.jitterFactor = jitterFactor;
    }

    public Duration getCheckInterval() {
        return checkInterval;
    }

    public void setCheckInterval(Duration checkInterval) {
        if (checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be a positive duration");
        }
        this.checkInterval = checkInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
        this.batchSize = batchSize;
    }

    @PostConstruct
    public void validate() {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1");
        }
        if (initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()) {
            throw new IllegalArgumentException("initialDelay must be a positive duration");
        }
        if (multiplier < 1.0) {
            throw new IllegalArgumentException("multiplier must be at least 1.0");
        }
        if (maxDelay == null || maxDelay.isNegative() || maxDelay.isZero()) {
            throw new IllegalArgumentException("maxDelay must be a positive duration");
        }
        if (maxDelay.minus(initialDelay).isNegative()) {
            throw new IllegalArgumentException("maxDelay must be greater than or equal to initialDelay");
        }
        if (jitterFactor < 0.0 || jitterFactor > 1.0) {
            throw new IllegalArgumentException("jitterFactor must be between 0.0 and 1.0");
        }
        if (checkInterval == null || checkInterval.isNegative() || checkInterval.isZero()) {
            throw new IllegalArgumentException("checkInterval must be a positive duration");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be at least 1");
        }
    }

    /**
     * Calculates the exact next retry instant for a transient failure.
     * Respects rate-limit reset times and Retry-After durations if provided;
     * otherwise computes exponential backoff with jitter bounded by maxDelay.
     *
     * @param currentRetryCount 0-indexed attempt count prior to this retry
     * @param now               current timestamp reference
     * @param resetAt           explicit rate-limit reset timestamp, if available
     * @param retryAfter        explicit Retry-After duration, if available
     * @return calculated next retry timestamp
     */
    public Instant calculateNextRetryAt(int currentRetryCount, Instant now, Instant resetAt, Duration retryAfter) {
        Instant baseNow = (now != null) ? now : Instant.now();

        // 1. Explicit rate-limit reset timestamp has top priority
        if (resetAt != null && resetAt.isAfter(baseNow)) {
            return resetAt;
        }

        // 2. Explicit Retry-After duration has second priority
        if (retryAfter != null && !retryAfter.isNegative() && !retryAfter.isZero()) {
            return baseNow.plus(retryAfter);
        }

        // 3. Exponential backoff: initialDelay * multiplier ^ currentRetryCount
        int safeCount = Math.max(0, currentRetryCount);
        double backoffMs = initialDelay.toMillis() * Math.pow(multiplier, safeCount);
        long maxDelayMs = maxDelay.toMillis();
        double cappedBackoffMs = Math.min(backoffMs, (double) maxDelayMs);

        // 4. Jitter application: uniform distribution in [-jitterFactor, +jitterFactor]
        double jitterRange = (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * jitterFactor;
        double jitteredMs = cappedBackoffMs * (1.0 + jitterRange);

        long finalDelayMs = Math.max(1000L, Math.round(jitteredMs));
        finalDelayMs = Math.min(finalDelayMs, maxDelayMs);

        return baseNow.plusMillis(finalDelayMs);
    }
}
