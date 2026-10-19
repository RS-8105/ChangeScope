package com.changescope.dto;

import java.util.List;

public record DependencyAnalysisResult(
    List<TypeDependency> dependencies,
    List<FileParseError> errors
) {
    public DependencyAnalysisResult {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        errors = errors != null ? List.copyOf(errors) : List.of();
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
