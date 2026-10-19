package com.changescope.dto;

public record VerificationRecommendation(
    VerificationPriority priority,
    String category,
    String target,
    String action,
    String reason
) {}
