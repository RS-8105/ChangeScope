package com.changescope.model;

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
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "pull_request_analyses",
    indexes = {
        @Index(name = "idx_pr_analyses_repo_started_id", columnList = "repository_id, started_at DESC, id DESC"),
        @Index(name = "idx_pr_analyses_status_started_at", columnList = "status, started_at")
    }
)
public class PullRequestAnalysisEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repository_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private RepositoryEntity repository;

    @Column(name = "pull_number", nullable = false)
    private int pullNumber;

    @Column(name = "head_commit_sha", length = 40)
    private String headCommitSha;

    @Column(name = "base_commit_sha", length = 40)
    private String baseCommitSha;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private AnalysisStatus status = AnalysisStatus.RUNNING;

    @Column(name = "failure_summary", length = 1000)
    private String failureSummary;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "report_snapshot", columnDefinition = "TEXT")
    private String reportSnapshot;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public PullRequestAnalysisEntity() {
    }

    public PullRequestAnalysisEntity(
        RepositoryEntity repository,
        int pullNumber,
        String headCommitSha,
        String baseCommitSha,
        AnalysisStatus status,
        Instant startedAt,
        Instant completedAt,
        String reportSnapshot
    ) {
        this(repository, pullNumber, headCommitSha, baseCommitSha, status, null, startedAt, completedAt, reportSnapshot);
    }

    public PullRequestAnalysisEntity(
        RepositoryEntity repository,
        int pullNumber,
        String headCommitSha,
        String baseCommitSha,
        AnalysisStatus status,
        String failureSummary,
        Instant startedAt,
        Instant completedAt,
        String reportSnapshot
    ) {
        this.repository = repository;
        this.pullNumber = pullNumber;
        this.headCommitSha = headCommitSha;
        this.baseCommitSha = baseCommitSha;
        this.status = status != null ? status : AnalysisStatus.RUNNING;
        this.failureSummary = failureSummary;
        this.startedAt = startedAt != null ? startedAt : Instant.now();
        this.completedAt = completedAt;
        this.reportSnapshot = reportSnapshot;
    }

    @PrePersist
    protected void onCreate() {
        Instant now = Instant.now();
        if (this.createdAt == null) {
            this.createdAt = now;
        }
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        if (this.status == null) {
            this.status = AnalysisStatus.RUNNING;
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

    public int getPullNumber() {
        return pullNumber;
    }

    public void setPullNumber(int pullNumber) {
        this.pullNumber = pullNumber;
    }

    public String getHeadCommitSha() {
        return headCommitSha;
    }

    public void setHeadCommitSha(String headCommitSha) {
        this.headCommitSha = headCommitSha;
    }

    public String getBaseCommitSha() {
        return baseCommitSha;
    }

    public void setBaseCommitSha(String baseCommitSha) {
        this.baseCommitSha = baseCommitSha;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public void setStatus(AnalysisStatus status) {
        this.status = status;
    }

    public String getFailureSummary() {
        return failureSummary;
    }

    public void setFailureSummary(String failureSummary) {
        this.failureSummary = failureSummary;
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

    public String getReportSnapshot() {
        return reportSnapshot;
    }

    public void setReportSnapshot(String reportSnapshot) {
        this.reportSnapshot = reportSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PullRequestAnalysisEntity that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
