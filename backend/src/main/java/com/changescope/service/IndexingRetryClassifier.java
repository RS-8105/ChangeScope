package com.changescope.service;

import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.exception.ResourceNotFoundException;
import org.springframework.web.client.ResourceAccessException;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies exceptions encountered during repository indexing as transient (retryable)
 * or permanent (non-retryable), and extracts rate-limit reset or Retry-After hints.
 */
public final class IndexingRetryClassifier {

    private static final Pattern RESET_AT_PATTERN = Pattern.compile("(?i)reset at:\\s*(\\d+)");
    private static final Pattern RETRY_AFTER_PATTERN = Pattern.compile("(?i)retry-after:\\s*(\\d+)");

    private IndexingRetryClassifier() {
    }

    /**
     * Determines whether an exception represents a transient failure eligible for delayed retry.
     *
     * Transient (retryable):
     * - GitHub rate limiting (GitHubRateLimitException, HTTP 429)
     * - Upstream server errors (HTTP 5xx)
     * - Network I/O, connect, reset, or timeout failures
     * - Temporary thread pool queue saturation (RejectedExecutionException)
     *
     * Permanent (non-retryable):
     * - Invalid credentials or authorization (GitHubAuthenticationException, HTTP 401, non-rate-limit 403)
     * - Missing repository or commit SHA (ResourceNotFoundException, GitHubResourceNotFoundException, HTTP 404, HTTP 422)
     * - Malformed input or validation errors (IllegalArgumentException, HTTP 400)
     * - Unsupported revision or merge conflicts (HTTP 409)
     *
     * @param throwable the error to inspect
     * @return true if retryable, false if permanent
     */
    public static boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 1. Permanent: Validation and missing entity
        if (throwable instanceof IllegalArgumentException
            || throwable instanceof ResourceNotFoundException
            || throwable instanceof GitHubResourceNotFoundException
            || throwable instanceof GitHubAuthenticationException) {
            return false;
        }

        // 2. Rate limiting (always retryable)
        if (throwable instanceof GitHubRateLimitException) {
            return true;
        }

        // 3. GitHub API status codes
        if (throwable instanceof GitHubApiException gha) {
            int status = gha.getStatusCode();
            if ("HISTORICAL_REVISION_UNSUPPORTED".equalsIgnoreCase(gha.getErrorCategory())
                || status == 409 || status == 400 || status == 404 || status == 422) {
                return false;
            }
            if (status == 401 || (status == 403 && !(throwable instanceof GitHubRateLimitException))) {
                return false;
            }
            if (status >= 500 || status == 429) {
                return true;
            }
            // Other 4xx client errors are permanent
            if (status >= 400 && status < 500) {
                return false;
            }
        }

        // 4. Transient system & network failures
        if (throwable instanceof ResourceAccessException
            || throwable instanceof IOException
            || throwable instanceof TimeoutException
            || throwable instanceof RejectedExecutionException) {
            return true;
        }

        // 5. Inspect nested cause
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isRetryable(cause);
        }

        return false;
    }

    /**
     * Extracts an exact rate-limit reset timestamp from the exception or its message, if available.
     *
     * @param throwable the exception to inspect
     * @return Instant representing the reset time, or null if unknown
     */
    public static Instant extractResetAt(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        if (throwable instanceof GitHubRateLimitException rle && rle.getResetAt() != null) {
            return rle.getResetAt();
        }

        String msg = throwable.getMessage();
        if (msg != null && !msg.isBlank()) {
            Matcher matcher = RESET_AT_PATTERN.matcher(msg);
            if (matcher.find()) {
                try {
                    long epochSeconds = Long.parseLong(matcher.group(1));
                    return Instant.ofEpochSecond(epochSeconds);
                } catch (Exception ignored) {
                }
            }
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return extractResetAt(cause);
        }

        return null;
    }

    /**
     * Extracts a Retry-After duration from the exception or its message, if available.
     *
     * @param throwable the exception to inspect
     * @return Duration representing the delay, or null if unknown
     */
    public static Duration extractRetryAfter(Throwable throwable) {
        if (throwable == null) {
            return null;
        }

        if (throwable instanceof GitHubApiException gha && gha.getRetryAfter() != null) {
            return gha.getRetryAfter();
        }

        String msg = throwable.getMessage();
        if (msg != null && !msg.isBlank()) {
            Matcher matcher = RETRY_AFTER_PATTERN.matcher(msg);
            if (matcher.find()) {
                try {
                    long seconds = Long.parseLong(matcher.group(1));
                    return Duration.ofSeconds(seconds);
                } catch (Exception ignored) {
                }
            }
        }

        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return extractRetryAfter(cause);
        }

        return null;
    }
}
