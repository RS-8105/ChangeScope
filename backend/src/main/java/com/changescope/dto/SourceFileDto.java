package com.changescope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SourceFileDto(
    @NotBlank(message = "filePath must not be blank")
    String filePath,

    @NotNull(message = "sourceCode must not be null")
    String sourceCode
) {
    public JavaSourceFileInput toDomain() {
        return new JavaSourceFileInput(filePath, sourceCode);
    }
}
