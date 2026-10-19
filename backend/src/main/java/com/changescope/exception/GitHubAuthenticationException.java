package com.changescope.exception;

public class GitHubAuthenticationException extends GitHubApiException {

    public GitHubAuthenticationException(int statusCode, String message) {
        super(statusCode, "AUTHENTICATION_FAILED", message);
    }
}
