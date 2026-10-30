package com.changescope.exception;

import java.time.Duration;
import java.time.Instant;

public class GitHubRateLimitException extends GitHubApiException {

    private final Instant resetAt;

    public GitHubRateLimitException(String message) {
        this(message, null, null);
    }

    public GitHubRateLimitException(String message, Instant resetAt, Duration retryAfter) {
        super(429, "RATE_LIMIT_EXCEEDED", message, null, retryAfter);
        this.resetAt = resetAt;
    }

    public Instant getResetAt() {
        return resetAt;
    }
}
