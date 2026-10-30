package com.changescope.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response DTO returning the number of deleted analysis records for a repository.
 *
 * @param repositoryId ID of the repository whose analyses were deleted
 * @param deletedCount number of analysis records deleted
 */
public record DeleteAnalysesResponse(
    Long repositoryId,
    long deletedCount
) {
    @JsonProperty("count")
    public long count() {
        return deletedCount;
    }
}
