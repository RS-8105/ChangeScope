package com.changescope.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ImpactAnalysisRequest(
    @NotNull(message = "files must not be null")
    List<@Valid SourceFileDto> files,

    @NotNull(message = "changedTypes must not be null")
    List<String> changedTypes
) {
    public ImpactAnalysisRequest {
        files = files != null ? List.copyOf(files) : null;
        changedTypes = changedTypes != null ? List.copyOf(changedTypes) : null;
    }

    public List<JavaSourceFileInput> toFileInputs() {
        return files != null
            ? files.stream().map(SourceFileDto::toDomain).toList()
            : List.of();
    }
}
