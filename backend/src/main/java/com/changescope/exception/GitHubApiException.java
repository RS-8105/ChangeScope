package com.changescope.exception;

public class GitHubApiException extends RuntimeException {

    private final int statusCode;
    private final String errorCategory;

    public GitHubApiException(String message) {
        this(500, "GITHUB_ERROR", message);
    }

    public GitHubApiException(int statusCode, String errorCategory, String message) {
        super(message);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
    }

    public GitHubApiException(int statusCode, String errorCategory, String message, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCategory = errorCategory;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCategory() {
        return errorCategory;
    }
}
