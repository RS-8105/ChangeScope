package com.changescope.service;

import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class IndexingRetryClassifierTest {

    @Test
    @DisplayName("Rate-limit, 5xx server errors, timeouts, and network I/O are classified as retryable")
    void isRetryable_transientFailures_classifiedAsRetryable() {
        // Rate limits
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubRateLimitException("Rate limit exceeded"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(429, "RATE_LIMIT_EXCEEDED", "Too Many Requests"))).isTrue();

        // 5xx upstream
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(500, "INTERNAL_ERROR", "GitHub 500"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(502, "BAD_GATEWAY", "GitHub 502"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(503, "SERVICE_UNAVAILABLE", "GitHub 503"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(504, "GATEWAY_TIMEOUT", "GitHub 504"))).isTrue();

        // Network & executor capacity
        assertThat(IndexingRetryClassifier.isRetryable(new ResourceAccessException("Connection timed out"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new IOException("Connection reset by peer"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new TimeoutException("Operation timed out"))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new RejectedExecutionException("Executor queue saturated"))).isTrue();

        // Nested causes
        assertThat(IndexingRetryClassifier.isRetryable(new RuntimeException("Wrapper", new TimeoutException("Timeout")))).isTrue();
        assertThat(IndexingRetryClassifier.isRetryable(new RuntimeException("Wrapper", new GitHubRateLimitException("Nested limit")))).isTrue();
    }

    @Test
    @DisplayName("Permanent client errors (400, 401, 403 non-rate, 404, 409, 422, validation) are non-retryable")
    void isRetryable_permanentFailures_classifiedAsNonRetryable() {
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubAuthenticationException(401, "Bad credentials"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubAuthenticationException(403, "Forbidden"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubResourceNotFoundException("Repository not found"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new ResourceNotFoundException("Repository missing in database"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new IllegalArgumentException("Commit SHA cannot be empty"))).isFalse();

        // Permanent 4xx status codes
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(400, "BAD_REQUEST", "Bad request syntax"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(404, "NOT_FOUND", "Commit not found"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(409, "CONFLICT", "Branch conflict"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED", "PR advanced"))).isFalse();
        assertThat(IndexingRetryClassifier.isRetryable(new GitHubApiException(422, "UNPROCESSABLE_ENTITY", "Invalid git tree"))).isFalse();

        // Nested permanent cause
        assertThat(IndexingRetryClassifier.isRetryable(new RuntimeException("Wrapper", new ResourceNotFoundException("Repo missing")))).isFalse();
    }

    @Test
    @DisplayName("Extracts reset timestamp from GitHubRateLimitException or message pattern")
    void extractResetAt_returnsValidResetTimestamp() {
        Instant expected = Instant.ofEpochSecond(1730312400L);
        GitHubRateLimitException withField = new GitHubRateLimitException("Rate limit", expected, null);
        assertThat(IndexingRetryClassifier.extractResetAt(withField)).isEqualTo(expected);

        // From message pattern
        RuntimeException withMessage = new RuntimeException("GitHub API rate limit exceeded (HTTP 403). Reset at: 1730312400");
        assertThat(IndexingRetryClassifier.extractResetAt(withMessage)).isEqualTo(expected);

        // Nested cause
        RuntimeException wrapped = new RuntimeException("Error occurred", withField);
        assertThat(IndexingRetryClassifier.extractResetAt(wrapped)).isEqualTo(expected);

        // Unknown
        assertThat(IndexingRetryClassifier.extractResetAt(new RuntimeException("No reset information"))).isNull();
    }

    @Test
    @DisplayName("Extracts Retry-After duration from exception or message pattern")
    void extractRetryAfter_returnsValidDuration() {
        Duration expected = Duration.ofSeconds(60);
        GitHubApiException withField = new GitHubApiException(503, "UNAVAILABLE", "Unavailable", null, expected);
        assertThat(IndexingRetryClassifier.extractRetryAfter(withField)).isEqualTo(expected);

        // From message pattern
        RuntimeException withMessage = new RuntimeException("Upstream 429 response. Retry-After: 60");
        assertThat(IndexingRetryClassifier.extractRetryAfter(withMessage)).isEqualTo(expected);

        // Nested cause
        RuntimeException wrapped = new RuntimeException("Error", withField);
        assertThat(IndexingRetryClassifier.extractRetryAfter(wrapped)).isEqualTo(expected);

        // Unknown
        assertThat(IndexingRetryClassifier.extractRetryAfter(new RuntimeException("No delay hint"))).isNull();
    }
}
