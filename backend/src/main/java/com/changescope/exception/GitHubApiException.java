package com.changescope.exception;

public class GitHubApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCategory;
    private final java.time.Duration retryAfter;

    public GitHubApiException(String message) {
        this(500, "GITHUB_ERROR", message, null, null);
    }

    public GitHubApiException(int statusCode, String errorCategory, String message) {
        this(statusCode, errorCategory, message, null, null);
    }

    public GitHubApiException(int statusCode, String errorCategory, String message, Throwable cause) {
        this(statusCode, errorCategory, message, cause, null);
    }

    public GitHubApiException(int statusCode, String errorCategory, String message, Throwable cause, java.time.Duration retryAfter) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
        this.retryAfter = retryAfter;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCategory() {
        return errorCategory;
    }

    public java.time.Duration getRetryAfter() {
        return retryAfter;
    }
}
