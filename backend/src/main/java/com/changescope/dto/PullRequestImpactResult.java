package com.changescope.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PullRequestImpactResult(
    Long analysisId,
    PullRequestMetadata pullRequest,
    ImpactReport impactReport,
    List<String> extractionWarnings,
    List<ChangedTypeDetection> changedTypeDetections
) {
    public PullRequestImpactResult {
        extractionWarnings = extractionWarnings != null ? List.copyOf(extractionWarnings) : List.of();
        changedTypeDetections = changedTypeDetections != null ? List.copyOf(changedTypeDetections) : List.of();
    }

    public PullRequestImpactResult(
        PullRequestMetadata pullRequest,
        ImpactReport impactReport,
        List<String> extractionWarnings,
        List<ChangedTypeDetection> changedTypeDetections
    ) {
        this(null, pullRequest, impactReport, extractionWarnings, changedTypeDetections);
    }

    public PullRequestImpactResult withAnalysisId(Long analysisId) {
        return new PullRequestImpactResult(
            analysisId,
            this.pullRequest,
            this.impactReport,
            this.extractionWarnings,
            this.changedTypeDetections
        );
    }

    @JsonIgnore
    public boolean hasWarnings() {
        return !extractionWarnings.isEmpty();
    }
}
