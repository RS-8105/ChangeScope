package com.changescope.dto;

import java.util.List;

public record VerificationPlan(
    AnalysisConfidence confidence,
    List<VerificationRecommendation> recommendations,
    int totalRecommendations,
    int highPriorityCount,
    int mediumPriorityCount,
    int lowPriorityCount
) {
    public VerificationPlan {
        recommendations = recommendations != null ? List.copyOf(recommendations) : List.of();
        totalRecommendations = recommendations.size();
        highPriorityCount = (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.HIGH).count();
        mediumPriorityCount = (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.MEDIUM).count();
        lowPriorityCount = (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.LOW).count();
    }

    public VerificationPlan(
        AnalysisConfidence confidence,
        List<VerificationRecommendation> recommendations
    ) {
        this(
            confidence,
            recommendations,
            recommendations != null ? recommendations.size() : 0,
            recommendations != null ? (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.HIGH).count() : 0,
            recommendations != null ? (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.MEDIUM).count() : 0,
            recommendations != null ? (int) recommendations.stream().filter(r -> r.priority() == VerificationPriority.LOW).count() : 0
        );
    }
}
