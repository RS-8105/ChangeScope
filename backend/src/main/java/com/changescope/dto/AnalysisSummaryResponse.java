package com.changescope.dto;

import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;

import java.time.Instant;

public record AnalysisSummaryResponse(
    Long id,
    Long analysisId,
    Long repositoryId,
    int pullNumber,
    String headCommitSha,
    String baseCommitSha,
    AnalysisStatus status,
    String failureSummary,
    Instant startedAt,
    Instant completedAt
) {
    public AnalysisSummaryResponse(
        Long id,
        Long repositoryId,
        int pullNumber,
        String headCommitSha,
        String baseCommitSha,
        AnalysisStatus status,
        Instant startedAt,
        Instant completedAt
    ) {
        this(id, id, repositoryId, pullNumber, headCommitSha, baseCommitSha, status, null, startedAt, completedAt);
    }

    public static AnalysisSummaryResponse fromEntity(PullRequestAnalysisEntity entity) {
        return new AnalysisSummaryResponse(
            entity.getId(),
            entity.getId(),
            entity.getRepository() != null ? entity.getRepository().getId() : null,
            entity.getPullNumber(),
            entity.getHeadCommitSha(),
            entity.getBaseCommitSha(),
            entity.getStatus(),
            entity.getFailureSummary(),
            entity.getStartedAt(),
            entity.getCompletedAt()
        );
    }
}
