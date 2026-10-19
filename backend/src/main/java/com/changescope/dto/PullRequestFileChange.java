package com.changescope.dto;

public record PullRequestFileChange(
    String filePath,
    FileChangeStatus status,
    int additions,
    int deletions,
    String patch,
    String previousFilePath
) {
    public PullRequestFileChange(
        String filePath,
        FileChangeStatus status,
        int additions,
        int deletions,
        String patch
    ) {
        this(filePath, status, additions, deletions, patch, null);
    }

    public boolean isJavaSource() {
        return filePath != null && filePath.endsWith(".java");
    }

    public boolean hasPatch() {
        return patch != null && !patch.isBlank();
    }
}
