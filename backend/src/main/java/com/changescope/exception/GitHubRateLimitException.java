package com.changescope.exception;

public class GitHubRateLimitException extends GitHubApiException {

    public GitHubRateLimitException(String message) {
        super(429, "RATE_LIMIT_EXCEEDED", message);
    }
}
