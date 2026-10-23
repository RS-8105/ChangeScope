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

    public PullRequestMetadata withHeadCommitSha(String newHeadCommitSha) {
        return new PullRequestMetadata(
            this.owner,
            this.repo,
            this.number,
            this.title,
            this.baseCommitSha,
            newHeadCommitSha,
            this.state
        );
    }

    public PullRequestMetadata withBaseCommitSha(String newBaseCommitSha) {
        return new PullRequestMetadata(
            this.owner,
            this.repo,
            this.number,
            this.title,
            newBaseCommitSha,
            this.headCommitSha,
            this.state
        );
    }

    public PullRequestMetadata withRevisions(String newHeadCommitSha, String newBaseCommitSha) {
        return new PullRequestMetadata(
            this.owner,
            this.repo,
            this.number,
            this.title,
            newBaseCommitSha != null ? newBaseCommitSha : this.baseCommitSha,
            newHeadCommitSha != null ? newHeadCommitSha : this.headCommitSha,
            this.state
        );
    }
}
