package com.changescope.dto;

public record JavaSourceFileInput(
    String filePath,
    String sourceCode
) {
    public JavaSourceFileInput {
        if (filePath == null || filePath.isBlank()) {
            throw new IllegalArgumentException("filePath must not be null or blank");
        }
    }
}
