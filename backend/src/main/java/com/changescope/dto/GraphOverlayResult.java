package com.changescope.dto;

import java.util.List;
import java.util.Set;

public record GraphOverlayResult(
    List<TypeDependency> dependencies,
    Set<String> activeTypes,
    List<FileParseError> parseErrors
) {
    public GraphOverlayResult {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        activeTypes = activeTypes != null ? Set.copyOf(activeTypes) : Set.of();
        parseErrors = parseErrors != null ? List.copyOf(parseErrors) : List.of();
    }

    public boolean hasErrors() {
        return !parseErrors.isEmpty();
    }

    public DependencyAnalysisResult toDependencyAnalysisResult() {
        return new DependencyAnalysisResult(dependencies, parseErrors);
    }
}
