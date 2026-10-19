package com.changescope.dto;

public record SourceFileContent(
    String filePath,
    String content,
    String commitSha
) {
    public SourceFileContent(String filePath, String content) {
        this(filePath, content, null);
    }

    public JavaSourceFileInput toFileInput() {
        return new JavaSourceFileInput(filePath, content);
    }
}
