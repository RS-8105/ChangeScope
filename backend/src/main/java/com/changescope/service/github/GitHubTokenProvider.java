package com.changescope.service.github;

import java.util.Optional;

/**
 * Strategy interface for providing bearer authentication tokens for GitHub API requests.
 * Encapsulates token lifecycle, caching, and refresh logic away from HTTP gateway classes.
 */
public interface GitHubTokenProvider {

    /**
     * Retrieves an active bearer token for GitHub API requests.
     *
     * @return an Optional containing the token, or empty if unauthenticated.
     */
    Optional<String> getToken();

    /**
     * Retrieves an active bearer token for a specific repository.
     * Defaults to delegating to {@link #getToken()}.
     *
     * @param owner the repository owner
     * @param repo  the repository name
     * @return an Optional containing the token, or empty if unauthenticated.
     */
    default Optional<String> getTokenForRepository(String owner, String repo) {
        return getToken();
    }
}
