package com.changescope.service;

import com.changescope.dto.AnalysisSummaryResponse;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.changescope.config.AnalysisProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class PullRequestAnalysisService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
    public static final String DEFAULT_STALE_FAILURE_SUMMARY = "Analysis timed out or process was restarted";

    private final PullRequestAnalysisRepository pullRequestAnalysisRepository;
    private final RepositoryEntityRepository repositoryEntityRepository;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties analysisProperties;

    public PullRequestAnalysisService(
        PullRequestAnalysisRepository pullRequestAnalysisRepository,
        RepositoryEntityRepository repositoryEntityRepository,
        ObjectMapper objectMapper
    ) {
        this(pullRequestAnalysisRepository, repositoryEntityRepository, objectMapper, new AnalysisProperties());
    }

    @Autowired
    public PullRequestAnalysisService(
        PullRequestAnalysisRepository pullRequestAnalysisRepository,
        RepositoryEntityRepository repositoryEntityRepository,
        ObjectMapper objectMapper,
        @Autowired(required = false) AnalysisProperties analysisProperties
    ) {
        this.pullRequestAnalysisRepository = pullRequestAnalysisRepository;
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.objectMapper = objectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();
        this.analysisProperties = analysisProperties != null ? analysisProperties : new AnalysisProperties();
    }

    @Transactional
    public PullRequestAnalysisEntity startAnalysis(Long repositoryId, int pullNumber) {
        return startAnalysis(repositoryId, pullNumber, null);
    }

    @Transactional
    public PullRequestAnalysisEntity startAnalysis(Long repositoryId, int pullNumber, String headCommitSha) {
        if (repositoryId == null || repositoryId <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (pullNumber <= 0) {
            throw new IllegalArgumentException("Pull request number must be a positive integer");
        }

        RepositoryEntity repository = repositoryEntityRepository.findById(repositoryId)
            .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id: " + repositoryId));

        PullRequestAnalysisEntity entity = new PullRequestAnalysisEntity(
            repository,
            pullNumber,
            headCommitSha != null && !headCommitSha.isBlank() ? headCommitSha.trim() : null,
            null,
            AnalysisStatus.RUNNING,
            null,
            Instant.now(),
            null,
            null
        );

        return pullRequestAnalysisRepository.save(entity);
    }

    @Transactional
    public PullRequestImpactResult completeAnalysis(
        Long analysisId,
        PullRequestImpactResult result,
        Instant completedAt
    ) {
        if (analysisId == null || analysisId <= 0) {
            throw new IllegalArgumentException("Analysis ID must be a positive integer");
        }
        if (result == null) {
            throw new IllegalArgumentException("Pull request impact result must not be null");
        }

        PullRequestAnalysisEntity entity = pullRequestAnalysisRepository.findById(analysisId)
            .orElseThrow(() -> new ResourceNotFoundException("Analysis not found with id: " + analysisId));

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException ex) {
            entity.setStatus(AnalysisStatus.FAILED);
            entity.setCompletedAt(completedAt != null ? completedAt : Instant.now());
            entity.setFailureSummary(sanitizeFailureSummary(ex));
            pullRequestAnalysisRepository.save(entity);
            throw new IllegalStateException("Failed to serialize pull request impact result to JSON snapshot", ex);
        }

        entity.setStatus(AnalysisStatus.COMPLETED);
        entity.setCompletedAt(completedAt != null ? completedAt : Instant.now());
        entity.setHeadCommitSha(result.pullRequest() != null ? result.pullRequest().headCommitSha() : null);
        entity.setBaseCommitSha(result.pullRequest() != null ? result.pullRequest().baseCommitSha() : null);
        entity.setReportSnapshot(snapshotJson);
        entity.setFailureSummary(null);

        pullRequestAnalysisRepository.save(entity);
        return result.withAnalysisId(entity.getId());
    }

    @Transactional
    public void failAnalysis(Long analysisId, Throwable error, Instant completedAt) {
        if (analysisId == null || analysisId <= 0) {
            return;
        }

        pullRequestAnalysisRepository.findById(analysisId).ifPresent(entity -> {
            entity.setStatus(AnalysisStatus.FAILED);
            entity.setCompletedAt(completedAt != null ? completedAt : Instant.now());
            entity.setFailureSummary(sanitizeFailureSummary(error));
            pullRequestAnalysisRepository.save(entity);
        });
    }

    /**
     * Reconciles abandoned analyses using the configured stale timeout property.
     * Stale RUNNING analyses whose startedAt timestamp is older than the cutoff
     * are atomically transitioned to FAILED, their completedAt set, and a sanitized
     * failure summary recorded.
     *
     * @return count of reconciled analyses
     */
    @Transactional
    public int reconcileStaleAnalyses() {
        Duration timeout = (analysisProperties != null && analysisProperties.getStaleTimeout() != null)
            ? analysisProperties.getStaleTimeout()
            : Duration.ofMinutes(30);
        return reconcileStaleAnalyses(timeout);
    }

    /**
     * Reconciles abandoned analyses using the specified positive timeout.
     *
     * @param timeout duration after which a RUNNING analysis is deemed abandoned
     * @return count of reconciled analyses
     */
    @Transactional
    public int reconcileStaleAnalyses(Duration timeout) {
        return reconcileStaleAnalyses(timeout, Instant.now());
    }

    /**
     * Reconciles abandoned analyses using the specified timeout and reference completion timestamp.
     *
     * @param timeout duration after which a RUNNING analysis is deemed abandoned
     * @param completedAt reference timestamp representing completion/reconciliation time
     * @return count of reconciled analyses
     */
    @Transactional
    public int reconcileStaleAnalyses(Duration timeout, Instant completedAt) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Stale analysis timeout must be a positive duration");
        }
        Instant executionTime = completedAt != null ? completedAt : Instant.now();
        Instant cutoff = executionTime.minus(timeout);
        return pullRequestAnalysisRepository.reconcileStaleAnalyses(
            AnalysisStatus.RUNNING,
            cutoff,
            AnalysisStatus.FAILED,
            executionTime,
            DEFAULT_STALE_FAILURE_SUMMARY
        );
    }


    @Transactional
    public PullRequestImpactResult saveCompletedAnalysis(
        Long repositoryId,
        PullRequestImpactResult result,
        Instant startedAt,
        Instant completedAt
    ) {
        int pullNumber = result.pullRequest() != null ? result.pullRequest().number() : 0;
        PullRequestAnalysisEntity entity = startAnalysis(repositoryId, pullNumber > 0 ? pullNumber : 1);
        if (startedAt != null) {
            entity.setStartedAt(startedAt);
            pullRequestAnalysisRepository.save(entity);
        }
        return completeAnalysis(entity.getId(), result, completedAt);
    }

    public Page<AnalysisSummaryResponse> getHistoricalAnalyses(Long repositoryId, int page, int size) {
        if (repositoryId == null || repositoryId <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (!repositoryEntityRepository.existsById(repositoryId)) {
            throw new ResourceNotFoundException("Repository not found with id: " + repositoryId);
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero");
        }

        int boundedSize = Math.min(size, MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(page, boundedSize, Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id")));

        return pullRequestAnalysisRepository.findByRepositoryId(repositoryId, pageable)
            .map(AnalysisSummaryResponse::fromEntity);
    }

    public List<AnalysisSummaryResponse> getHistoricalAnalyses(Long repositoryId) {
        if (repositoryId == null || repositoryId <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (!repositoryEntityRepository.existsById(repositoryId)) {
            throw new ResourceNotFoundException("Repository not found with id: " + repositoryId);
        }

        return pullRequestAnalysisRepository.findByRepositoryIdOrderByStartedAtDescIdDesc(repositoryId)
            .stream()
            .map(AnalysisSummaryResponse::fromEntity)
            .toList();
    }

    public PullRequestImpactResult getAnalysisSnapshot(Long repositoryId, Long analysisId) {
        if (repositoryId == null || repositoryId <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (analysisId == null || analysisId <= 0) {
            throw new IllegalArgumentException("Analysis ID must be a positive integer");
        }
        if (!repositoryEntityRepository.existsById(repositoryId)) {
            throw new ResourceNotFoundException("Repository not found with id: " + repositoryId);
        }

        PullRequestAnalysisEntity entity = pullRequestAnalysisRepository.findById(analysisId)
            .orElseThrow(() -> new ResourceNotFoundException("Analysis not found with id: " + analysisId));

        if (entity.getRepository() == null || !entity.getRepository().getId().equals(repositoryId)) {
            throw new ResourceNotFoundException("Analysis with id " + analysisId + " does not belong to repository with id " + repositoryId);
        }

        if (entity.getReportSnapshot() == null || entity.getReportSnapshot().isBlank()) {
            throw new IllegalStateException("Snapshot for analysis id " + analysisId + " is empty");
        }

        try {
            PullRequestImpactResult result = objectMapper.readValue(entity.getReportSnapshot(), PullRequestImpactResult.class);
            return result.withAnalysisId(entity.getId());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize analysis snapshot for analysis id " + analysisId, ex);
        }
    }

    public static String sanitizeFailureSummary(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        String sanitized = message
            .replaceAll("-----BEGIN[A-Z0-9 -]+KEY-----[\\s\\S]*?-----END[A-Z0-9 -]+KEY-----", "[PRIVATE_KEY_REDACTED]")
            .replaceAll("(?i)(bearer\\s+|token\\s+|secret\\s*[:=]\\s*|password\\s*[:=]\\s*)[A-Za-z0-9_.-]+", "$1***")
            .replaceAll("gh[pousr]_[A-Za-z0-9_]+", "gh***")
            .replaceAll("github_pat_[A-Za-z0-9_]+", "github_pat_***")
            .replaceAll("ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}", "[JWT_REDACTED]");
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 252) + "...";
        }
        return sanitized;
    }
}
