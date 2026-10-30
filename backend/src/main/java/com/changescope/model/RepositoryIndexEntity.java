package com.changescope.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Table(
    name = "repository_indices",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_repo_indices_repo_commit", columnNames = {"repository_id", "commit_sha"})
    },
    indexes = {
        @Index(name = "idx_repo_indices_repo_commit", columnList = "repository_id, commit_sha"),
        @Index(name = "idx_repo_indices_repo_status", columnList = "repository_id, status"),
        @Index(name = "idx_repo_indices_created_at", columnList = "created_at DESC"),
        @Index(name = "idx_repo_indices_retry", columnList = "status, retryable, next_retry_at")
    }
)
public class RepositoryIndexEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RepositoryEntity repository;

    @NotBlank(message = "Commit SHA must not be blank")
    @Size(max = 40, message = "Commit SHA cannot exceed 40 characters")
    @Column(name = "commit_sha", nullable = false, length = 40)
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private RepositoryIndexStatus status = RepositoryIndexStatus.PENDING;

    @Column(name = "total_files_discovered", nullable = false)
    private int totalFilesDiscovered = 0;

    @Column(name = "total_files_indexed", nullable = false)
    private int totalFilesIndexed = 0;

    @Column(name = "total_types_indexed", nullable = false)
    private int totalTypesIndexed = 0;

    @Column(name = "total_dependencies_indexed", nullable = false)
    private int totalDependenciesIndexed = 0;

    @Column(name = "is_truncated_or_capped", nullable = false)
    private boolean isTruncatedOrCapped = false;

    @Column(name = "failure_reason", length = 1000)
    private String failureReason;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "retryable", nullable = false)
    private boolean retryable = false;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "attempt_token", length = 64)
    private String attemptToken;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "index", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IndexedTypeEntity> indexedTypes = new ArrayList<>();

    @OneToMany(mappedBy = "index", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<IndexedTypeDependencyEntity> indexedDependencies = new ArrayList<>();

    public RepositoryIndexEntity() {
    }

    public RepositoryIndexEntity(RepositoryEntity repository, String commitSha) {
        this(repository, commitSha, RepositoryIndexStatus.PENDING);
    }

    public RepositoryIndexEntity(RepositoryEntity repository, String commitSha, RepositoryIndexStatus status) {
        this.repository = repository;
        this.commitSha = commitSha;
        this.status = (status != null) ? status : RepositoryIndexStatus.PENDING;
    }

    @PrePersist
    protected void onCreate() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    public void addIndexedType(IndexedTypeEntity typeEntity) {
        if (typeEntity != null) {
            indexedTypes.add(typeEntity);
            typeEntity.setIndex(this);
            this.totalTypesIndexed = indexedTypes.size();
        }
    }

    public void addIndexedDependency(IndexedTypeDependencyEntity dependencyEntity) {
        if (dependencyEntity != null) {
            indexedDependencies.add(dependencyEntity);
            dependencyEntity.setIndex(this);
            this.totalDependenciesIndexed = indexedDependencies.size();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public RepositoryEntity getRepository() {
        return repository;
    }

    public void setRepository(RepositoryEntity repository) {
        this.repository = repository;
    }

    public String getCommitSha() {
        return commitSha;
    }

    public void setCommitSha(String commitSha) {
        this.commitSha = commitSha;
    }

    public RepositoryIndexStatus getStatus() {
        return status;
    }

    public void setStatus(RepositoryIndexStatus status) {
        this.status = status;
    }

    public int getTotalFilesDiscovered() {
        return totalFilesDiscovered;
    }

    public void setTotalFilesDiscovered(int totalFilesDiscovered) {
        this.totalFilesDiscovered = totalFilesDiscovered;
    }

    public int getTotalFilesIndexed() {
        return totalFilesIndexed;
    }

    public void setTotalFilesIndexed(int totalFilesIndexed) {
        this.totalFilesIndexed = totalFilesIndexed;
    }

    public int getTotalTypesIndexed() {
        return totalTypesIndexed;
    }

    public void setTotalTypesIndexed(int totalTypesIndexed) {
        this.totalTypesIndexed = totalTypesIndexed;
    }

    public int getTotalDependenciesIndexed() {
        return totalDependenciesIndexed;
    }

    public void setTotalDependenciesIndexed(int totalDependenciesIndexed) {
        this.totalDependenciesIndexed = totalDependenciesIndexed;
    }

    public boolean isTruncatedOrCapped() {
        return isTruncatedOrCapped;
    }

    public void setTruncatedOrCapped(boolean truncatedOrCapped) {
        isTruncatedOrCapped = truncatedOrCapped;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    public Instant getNextRetryAt() {
        return nextRetryAt;
    }

    public void setNextRetryAt(Instant nextRetryAt) {
        this.nextRetryAt = nextRetryAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public String getAttemptToken() {
        return attemptToken;
    }

    public void setAttemptToken(String attemptToken) {
        this.attemptToken = attemptToken;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public List<IndexedTypeEntity> getIndexedTypes() {
        return indexedTypes;
    }

    public void setIndexedTypes(List<IndexedTypeEntity> indexedTypes) {
        this.indexedTypes = indexedTypes != null ? indexedTypes : new ArrayList<>();
    }

    public List<IndexedTypeDependencyEntity> getIndexedDependencies() {
        return indexedDependencies;
    }

    public void setIndexedDependencies(List<IndexedTypeDependencyEntity> indexedDependencies) {
        this.indexedDependencies = indexedDependencies != null ? indexedDependencies : new ArrayList<>();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RepositoryIndexEntity that = (RepositoryIndexEntity) o;
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
