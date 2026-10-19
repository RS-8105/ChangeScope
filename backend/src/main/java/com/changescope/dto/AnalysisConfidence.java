package com.changescope.dto;

import java.util.List;

public record AnalysisConfidence(
    ConfidenceLevel level,
    int score,
    String rating,
    List<String> positiveFactors,
    List<String> negativeFactors
) {
    public AnalysisConfidence {
        positiveFactors = positiveFactors != null ? List.copyOf(positiveFactors) : List.of();
        negativeFactors = negativeFactors != null ? List.copyOf(negativeFactors) : List.of();
    }
}
