package com.changescope.dto;

public record PullRequestMetadata(
    String owner,
    String repo,
    int number,
    String title,
    String baseCommitSha,
    String headCommitSha,
    PullRequestState state
) {
    public String getRepositoryFullName() {
        return owner + "/" + repo;
    }

    public boolean isOpen() {
        return state == PullRequestState.OPEN;
    }
}
