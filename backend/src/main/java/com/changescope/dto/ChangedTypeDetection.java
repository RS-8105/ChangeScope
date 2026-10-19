package com.changescope.dto;

public record ChangedTypeDetection(
    String fullyQualifiedName,
    String filePath,
    TypeChangeEvidence evidence,
    String detail
) {}
