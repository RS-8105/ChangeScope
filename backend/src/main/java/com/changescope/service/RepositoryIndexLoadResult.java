package com.changescope.service;

import com.changescope.dto.BaseGraphSnapshot;
import com.changescope.model.RepositoryIndexStatus;

import java.util.Optional;

public sealed interface RepositoryIndexLoadResult permits
    RepositoryIndexLoadResult.Usable,
    RepositoryIndexLoadResult.Unusable,
    RepositoryIndexLoadResult.NotFound {

    String commitSha();

    record Usable(
        String commitSha,
        RepositoryIndexStatus status,
        boolean isTruncatedOrCapped,
        int totalFilesDiscovered,
        int totalFilesIndexed,
        BaseGraphSnapshot snapshot
    ) implements RepositoryIndexLoadResult {
        public boolean isComplete() {
            return status == RepositoryIndexStatus.COMPLETED && !isTruncatedOrCapped;
        }
    }

    record Unusable(
        String commitSha,
        RepositoryIndexStatus status,
        String reason
    ) implements RepositoryIndexLoadResult {}

    record NotFound(
        String commitSha,
        String reason
    ) implements RepositoryIndexLoadResult {}

    default boolean isUsable() {
        return this instanceof Usable;
    }

    default Optional<Usable> asUsable() {
        return (this instanceof Usable u) ? Optional.of(u) : Optional.empty();
    }
}
