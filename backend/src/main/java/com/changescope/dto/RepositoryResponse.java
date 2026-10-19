package com.changescope.dto;

import com.changescope.model.RepositoryEntity;

import java.time.Instant;

public record RepositoryResponse(
    Long id,
    Long githubId,
    Long installationId,
    String owner,
    String name,
    String fullName,
    String defaultBranch,
    String lastIndexedCommit,
    Instant createdAt,
    Instant updatedAt
) {
    public RepositoryResponse(
        Long id,
        Long githubId,
        String owner,
        String name,
        String fullName,
        String defaultBranch,
        String lastIndexedCommit,
        Instant createdAt,
        Instant updatedAt
    ) {
        this(id, githubId, null, owner, name, fullName, defaultBranch, lastIndexedCommit, createdAt, updatedAt);
    }

    public static RepositoryResponse fromEntity(RepositoryEntity entity) {
        return new RepositoryResponse(
            entity.getId(),
            entity.getGithubId(),
            entity.getInstallationId(),
            entity.getOwner(),
            entity.getName(),
            entity.getFullName(),
            entity.getDefaultBranch(),
            entity.getLastIndexedCommit(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
