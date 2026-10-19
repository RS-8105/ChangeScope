package com.changescope.dto;

public record AffectedMethod(
    String methodIdentifier,
    String declaringType,
    int distance
) {}
