package com.changescope.dto;

import java.util.List;

public record GitDiffExtractionResult(
    List<String> changedTypes,
    List<ChangedTypeDetection> detections,
    List<String> warnings
) {
    public GitDiffExtractionResult {
        changedTypes = changedTypes != null ? List.copyOf(changedTypes) : List.of();
        detections = detections != null ? List.copyOf(detections) : List.of();
        warnings = warnings != null ? List.copyOf(warnings) : List.of();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }
}
