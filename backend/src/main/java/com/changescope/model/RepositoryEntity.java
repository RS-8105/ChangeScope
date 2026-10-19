package com.changescope.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "repositories",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_repositories_owner_name", columnNames = {"owner", "name"})
    }
)
public class RepositoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "github_id")
    private Long githubId;

    @Column(name = "installation_id")
    private Long installationId;

    @NotBlank(message = "Owner must not be blank")
    @Size(max = 100, message = "Owner cannot exceed 100 characters")
    @Column(name = "owner", nullable = false, length = 100)
    private String owner;

    @NotBlank(message = "Repository name must not be blank")
    @Size(max = 100, message = "Repository name cannot exceed 100 characters")
    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @NotBlank(message = "Default branch must not be blank")
    @Size(max = 100, message = "Default branch cannot exceed 100 characters")
    @Column(name = "default_branch", nullable = false, length = 100)
    private String defaultBranch = "main";

    @Size(max = 40, message = "Last indexed commit SHA cannot exceed 40 characters")
    @Column(name = "last_indexed_commit", length = 40)
    private String lastIndexedCommit;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public RepositoryEntity() {
    }

    public RepositoryEntity(String owner, String name, String defaultBranch, Long githubId) {
        this(owner, name, defaultBranch, githubId, null);
    }

    public RepositoryEntity(String owner, String name, String defaultBranch, Long githubId, Long installationId) {
        this.owner = owner;
        this.name = name;
        this.defaultBranch = (defaultBranch != null && !defaultBranch.isBlank()) ? defaultBranch : "main";
        setGithubId(githubId);
        setInstallationId(installationId);
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.updatedAt == null) {
            this.updatedAt = now;
        }
        if (this.defaultBranch == null || this.defaultBranch.isBlank()) {
            this.defaultBranch = "main";
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getGithubId() {
        return githubId;
    }

    public void setGithubId(Long githubId) {
        if (githubId != null && githubId <= 0) {
            throw new IllegalArgumentException("GitHub repository ID must be a positive integer");
        }
        this.githubId = githubId;
    }

    public Long getInstallationId() {
        return installationId;
    }

    public void setInstallationId(Long installationId) {
        if (installationId != null && installationId <= 0) {
            throw new IllegalArgumentException("Installation ID must be a positive integer");
        }
        this.installationId = installationId;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDefaultBranch() {
        return defaultBranch;
    }

    public void setDefaultBranch(String defaultBranch) {
        this.defaultBranch = defaultBranch;
    }

    public String getLastIndexedCommit() {
        return lastIndexedCommit;
    }

    public void setLastIndexedCommit(String lastIndexedCommit) {
        this.lastIndexedCommit = lastIndexedCommit;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getFullName() {
        return owner + "/" + name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RepositoryEntity that)) return false;
        return Objects.equals(owner, that.owner) && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(owner, name);
    }
}
