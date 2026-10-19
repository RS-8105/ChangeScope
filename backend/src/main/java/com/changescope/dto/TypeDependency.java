package com.changescope.dto;

public record TypeDependency(
    String sourceType,
    String targetType,
    RelationshipKind kind,
    String sourceFilePath
) {}
