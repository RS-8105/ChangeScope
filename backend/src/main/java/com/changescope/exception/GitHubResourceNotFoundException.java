package com.changescope.exception;

public class GitHubResourceNotFoundException extends GitHubApiException {

    public GitHubResourceNotFoundException(String message) {
        super(404, "NOT_FOUND", message);
    }
}
