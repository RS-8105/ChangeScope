package com.changescope.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Structured analysis coverage metadata reporting the depth, completeness, and boundary
 * of repository source inspection.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisCoverage(
    CoverageScope scope,
    String baseCommitSha,
    int productionFilesDiscovered,
    int productionFilesIncluded,
    boolean isTruncatedOrCapped,
    boolean isComplete,
    List<String> incompleteReasons
) {
    public AnalysisCoverage {
        incompleteReasons = incompleteReasons != null ? List.copyOf(incompleteReasons) : List.of();
    }

    public static AnalysisCoverage prFilesOnly(String baseCommitSha) {
        return new AnalysisCoverage(
            CoverageScope.PR_FILES_ONLY,
            baseCommitSha,
            0,
            0,
            false,
            true,
            List.of()
        );
    }

    public static AnalysisCoverage prFilesOnlyIncomplete(String baseCommitSha, String reason) {
        return new AnalysisCoverage(
            CoverageScope.PR_FILES_ONLY,
            baseCommitSha,
            0,
            0,
            false,
            false,
            reason != null ? List.of(reason) : List.of()
        );
    }

    public static AnalysisCoverage boundedPartial(
        String baseCommitSha,
        int productionFilesDiscovered,
        int productionFilesIncluded,
        boolean isTruncatedOrCapped,
        List<String> incompleteReasons
    ) {
        return new AnalysisCoverage(
            CoverageScope.PARTIAL_INDEX_BOUNDED,
            baseCommitSha,
            productionFilesDiscovered,
            productionFilesIncluded,
            isTruncatedOrCapped,
            false,
            incompleteReasons
        );
    }
}
