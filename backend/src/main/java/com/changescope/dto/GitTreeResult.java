package com.changescope.dto;

import java.util.List;

/**
 * Result of querying a repository's Git tree at a specific commit.
 * Contains repository file paths and indicates whether the response was truncated by GitHub.
 */
public record GitTreeResult(
    List<String> filePaths,
    boolean truncated
) {
    public GitTreeResult {
        filePaths = (filePaths != null) ? List.copyOf(filePaths) : List.of();
    }

    /**
     * Alias for filePaths() to support common access patterns.
     */
    public List<String> paths() {
        return filePaths;
    }
}
