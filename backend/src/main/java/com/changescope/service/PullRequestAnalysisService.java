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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class PullRequestAnalysisService {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private final PullRequestAnalysisRepository pullRequestAnalysisRepository;
    private final RepositoryEntityRepository repositoryEntityRepository;
    private final ObjectMapper objectMapper;

    public PullRequestAnalysisService(
        PullRequestAnalysisRepository pullRequestAnalysisRepository,
        RepositoryEntityRepository repositoryEntityRepository,
        ObjectMapper objectMapper
    ) {
        this.pullRequestAnalysisRepository = pullRequestAnalysisRepository;
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.objectMapper = objectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .findAndRegisterModules();
    }

    @Transactional
    public PullRequestAnalysisEntity startAnalysis(Long repositoryId, int pullNumber) {
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
            null,
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
        String sanitized = message.replaceAll("(?i)(bearer\\s+|token\\s+|ghp_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+)[A-Za-z0-9_.-]+", "$1***");
        if (sanitized.length() > 255) {
            sanitized = sanitized.substring(0, 252) + "...";
        }
        return sanitized;
    }
}
