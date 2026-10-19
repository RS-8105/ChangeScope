package com.changescope.dto;

import java.util.List;

public record TestImpactResult(
    List<TestImpactCandidate> candidateTests,
    List<FileParseError> parseErrors
) {
    public TestImpactResult {
        candidateTests = candidateTests != null ? List.copyOf(candidateTests) : List.of();
        parseErrors = parseErrors != null ? List.copyOf(parseErrors) : List.of();
    }
}
