package com.changescope.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record CreateRepositoryRequest(
    @Positive(message = "GitHub repository ID must be positive")
    Long githubId,

    @Positive(message = "Installation ID must be positive")
    Long installationId,

    @NotBlank(message = "Owner must not be blank")
    @Size(max = 100, message = "Owner cannot exceed 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Owner contains invalid characters (allowed: letters, numbers, _, ., -)")
    String owner,

    @NotBlank(message = "Repository name must not be blank")
    @Size(max = 100, message = "Repository name cannot exceed 100 characters")
    @Pattern(regexp = "^[a-zA-Z0-9_.-]+$", message = "Repository name contains invalid characters (allowed: letters, numbers, _, ., -)")
    String name,

    @Size(max = 100, message = "Default branch cannot exceed 100 characters")
    String defaultBranch
) {
    public CreateRepositoryRequest(Long githubId, String owner, String name, String defaultBranch) {
        this(githubId, null, owner, name, defaultBranch);
    }
}
