package com.changescope.service;

import com.changescope.config.AnalysisProperties;
import com.changescope.config.IndexingExecutorConfig;
import com.changescope.config.IndexingRetryProperties;
import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.GitTreeResult;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.ParsedJavaFile;
import com.changescope.dto.ParsedType;
import com.changescope.dto.SourceFileContent;
import com.changescope.dto.TypeDependency;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import com.changescope.service.github.GitHubGateway;
import com.changescope.service.github.GitHubWebhookDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Service for creating and persisting repository Java dependency graph snapshots at exact Git commit SHAs.
 */
@Service
public class RepositoryIndexingService {

    private static final Logger log = LoggerFactory.getLogger(RepositoryIndexingService.class);

    public static final int MAX_PRODUCTION_FILES = 5000;
    public static final int BATCH_SIZE = 100;
    public static final String DEFAULT_STALE_INDEX_FAILURE_REASON =
        "Repository indexing timed out or was abandoned; marked as FAILED by reconciliation";

    private final GitHubGateway gitHubGateway;
    private final JavaDependencyAnalysisService dependencyAnalysisService;
    private final JavaSourceParserService parserService;
    private final RepositoryEntityRepository repositoryEntityRepository;
    private final RepositoryIndexRepository repositoryIndexRepository;
    private final IndexedTypeRepository indexedTypeRepository;
    private final IndexedTypeDependencyRepository indexedTypeDependencyRepository;
    private final PlatformTransactionManager transactionManager;
    private final AnalysisProperties analysisProperties;
    private final IndexingRetryProperties retryProperties;
    private final Executor taskExecutor;

    public RepositoryIndexingService(
        GitHubGateway gitHubGateway,
        JavaDependencyAnalysisService dependencyAnalysisService,
        JavaSourceParserService parserService,
        RepositoryEntityRepository repositoryEntityRepository,
        RepositoryIndexRepository repositoryIndexRepository,
        IndexedTypeRepository indexedTypeRepository,
        IndexedTypeDependencyRepository indexedTypeDependencyRepository,
        PlatformTransactionManager transactionManager,
        AnalysisProperties analysisProperties
    ) {
        this(
            gitHubGateway,
            dependencyAnalysisService,
            parserService,
            repositoryEntityRepository,
            repositoryIndexRepository,
            indexedTypeRepository,
            indexedTypeDependencyRepository,
            transactionManager,
            analysisProperties,
            new IndexingRetryProperties(),
            null
        );
    }

    @Autowired
    public RepositoryIndexingService(
        GitHubGateway gitHubGateway,
        JavaDependencyAnalysisService dependencyAnalysisService,
        JavaSourceParserService parserService,
        RepositoryEntityRepository repositoryEntityRepository,
        RepositoryIndexRepository repositoryIndexRepository,
        IndexedTypeRepository indexedTypeRepository,
        IndexedTypeDependencyRepository indexedTypeDependencyRepository,
        PlatformTransactionManager transactionManager,
        AnalysisProperties analysisProperties,
        @Autowired(required = false) IndexingRetryProperties retryProperties,
        @Qualifier(IndexingExecutorConfig.EXECUTOR_BEAN_NAME) @Autowired(required = false) Executor taskExecutor
    ) {
        this.gitHubGateway = gitHubGateway;
        this.dependencyAnalysisService = dependencyAnalysisService;
        this.parserService = parserService;
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.repositoryIndexRepository = repositoryIndexRepository;
        this.indexedTypeRepository = indexedTypeRepository;
        this.indexedTypeDependencyRepository = indexedTypeDependencyRepository;
        this.transactionManager = transactionManager;
        this.analysisProperties = analysisProperties;
        this.retryProperties = (retryProperties != null) ? retryProperties : new IndexingRetryProperties();
        this.taskExecutor = taskExecutor;
    }

    /**
     * Filters production Java files by including .java and excluding test/build/generated directories and test suffixes.
     */
    public static boolean isProductionJavaFile(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = path.replace('\\', '/').trim();
        String lower = normalized.toLowerCase();

        if (!lower.endsWith(".java")) {
            return false;
        }

        // Exclude test directories: src/test/, test/, tests/
        if (lower.startsWith("src/test/") || lower.contains("/src/test/")
                || lower.startsWith("test/") || lower.contains("/test/")
                || lower.startsWith("tests/") || lower.contains("/tests/")) {
            return false;
        }

        // Exclude build and generated directories
        if (lower.startsWith("target/") || lower.contains("/target/")
                || lower.startsWith("build/") || lower.contains("/build/")
                || lower.startsWith(".gradle/") || lower.contains("/.gradle/")
                || lower.startsWith(".mvn/") || lower.contains("/.mvn/")
                || lower.startsWith("bin/") || lower.contains("/bin/")
                || lower.startsWith("out/") || lower.contains("/out/")
                || lower.startsWith("generated/") || lower.contains("/generated/")
                || lower.startsWith("generated-sources/") || lower.contains("/generated-sources/")) {
            return false;
        }

        // Exclude filename patterns: *Test.java, *Tests.java, *IT.java, *TestCase.java
        int lastSlash = normalized.lastIndexOf('/');
        String filename = (lastSlash >= 0) ? normalized.substring(lastSlash + 1) : normalized;
        String simpleName = filename.substring(0, filename.length() - ".java".length());

        if (simpleName.endsWith("Test")
                || simpleName.endsWith("Tests")
                || simpleName.endsWith("IT")
                || simpleName.endsWith("TestCase")) {
            return false;
        }

        return true;
    }

    /**
     * Indexes a repository at an exact commit SHA. Defaults isDefaultBranch to false.
     */
    public RepositoryIndexEntity indexRepository(Long repositoryId, String owner, String repo, String commitSha) {
        return indexRepository(repositoryId, owner, repo, commitSha, false);
    }

    /**
     * Indexes a repository at an exact commit SHA with explicit default-branch indication.
     *
     * @param repositoryId    ID of the registered repository
     * @param owner           repository owner
     * @param repo            repository name
     * @param commitSha       exact 40-character Git commit SHA
     * @param isDefaultBranch whether this commit is on the default branch
     * @return the final RepositoryIndexEntity
     */
    public RepositoryIndexEntity indexRepository(
        Long repositoryId,
        String owner,
        String repo,
        String commitSha,
        boolean isDefaultBranch
    ) {
        validateInputs(repositoryId, owner, repo, commitSha);

        String cleanOwner = owner.trim();
        String cleanRepo = repo.trim();
        String cleanCommitSha = commitSha.trim();

        // 1. Claim or find existing snapshot in isolated short transaction
        ClaimResult claimResult = claimIndexingSnapshot(repositoryId, cleanCommitSha);
        if (claimResult instanceof ClaimResult.AlreadyUsable usable) {
            log.info("Index for repository {} at commit {} already exists with status {}; returning without duplicate work",
                repositoryId, cleanCommitSha, usable.entity().getStatus());
            return usable.entity();
        }
        if (claimResult instanceof ClaimResult.ActivelyIndexing active) {
            log.info("Index for repository {} at commit {} is currently INDEXING; returning without duplicate work",
                repositoryId, cleanCommitSha);
            return active.entity();
        }

        RepositoryIndexEntity claimedIndex = ((ClaimResult.Claimed) claimResult).entity();
        return performIndexing(
            claimedIndex.getId(),
            claimedIndex.getAttemptToken(),
            repositoryId,
            cleanOwner,
            cleanRepo,
            cleanCommitSha,
            isDefaultBranch
        );
    }

    /**
     * Executes the repository indexing ingestion pipeline on an already claimed snapshot.
     * Used by both initial push indexing and background retry reconciliation.
     */
    public RepositoryIndexEntity performIndexing(
        Long indexId,
        String attemptToken,
        Long repositoryId,
        String cleanOwner,
        String cleanRepo,
        String cleanCommitSha,
        boolean isDefaultBranch
    ) {
        try {
            // 2. Fetch recursive Git tree at exact commit SHA
            GitTreeResult treeResult;
            try {
                treeResult = gitHubGateway.getGitTree(cleanOwner, cleanRepo, cleanCommitSha);
            } catch (Exception ex) {
                log.error("Failed to fetch Git tree for {}/{} at commit {}", cleanOwner, cleanRepo, cleanCommitSha, ex);
                return failSnapshot(indexId, attemptToken, "Failed to retrieve recursive Git tree: " + GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage()));
            }

            if (treeResult == null || treeResult.filePaths() == null) {
                return failSnapshot(indexId, attemptToken, "Git tree response was null or missing file paths");
            }

            // 3. Filter production Java files
            List<String> productionFiles = treeResult.filePaths().stream()
                .filter(RepositoryIndexingService::isProductionJavaFile)
                .toList();

            int discoveredCount = productionFiles.size();
            boolean capped = discoveredCount > MAX_PRODUCTION_FILES;
            boolean isTruncatedOrCapped = treeResult.truncated() || capped;

            List<String> filesToFetch = capped ? productionFiles.subList(0, MAX_PRODUCTION_FILES) : productionFiles;

            // 4. Batch retrieve source files with fallback to individual retrieval
            List<JavaSourceFileInput> retrievedFiles = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            boolean downloadFailureOccurred = false;

            for (int i = 0; i < filesToFetch.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, filesToFetch.size());
                List<String> batch = filesToFetch.subList(i, end);
                try {
                    List<SourceFileContent> contents = gitHubGateway.getFilesContent(cleanOwner, cleanRepo, batch, cleanCommitSha);
                    if (contents == null || contents.isEmpty()) {
                        downloadFailureOccurred = true;
                        warnings.add("Received empty response for batch of " + batch.size() + " files");
                    } else {
                        for (SourceFileContent sfc : contents) {
                            if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                retrievedFiles.add(sfc.toFileInput());
                            } else {
                                downloadFailureOccurred = true;
                                String path = (sfc != null && sfc.filePath() != null) ? sfc.filePath() : "unknown";
                                warnings.add("Empty or unavailable content for file '" + path + "'");
                            }
                        }
                    }
                } catch (Exception batchEx) {
                    // Fall back to individual file retrieval for this batch
                    for (String path : batch) {
                        try {
                            SourceFileContent sfc = gitHubGateway.getFileContent(cleanOwner, cleanRepo, path, cleanCommitSha);
                            if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                retrievedFiles.add(sfc.toFileInput());
                            } else {
                                downloadFailureOccurred = true;
                                warnings.add("Empty or unavailable content for file '" + path + "'");
                            }
                        } catch (Exception singleEx) {
                            downloadFailureOccurred = true;
                            warnings.add("Skipping file '" + path + "': " + GitHubWebhookDeliveryService.sanitizeFailureSummary(singleEx.getMessage()));
                        }
                    }
                }
            }

            // 5. Analyze retrieved files through JavaDependencyAnalysisService
            DependencyAnalysisResult analysisResult = dependencyAnalysisService.analyze(retrievedFiles);
            boolean parseFailureOccurred = analysisResult.hasErrors();
            for (FileParseError err : analysisResult.errors()) {
                warnings.add("Parse error in '" + err.filePath() + "': " + GitHubWebhookDeliveryService.sanitizeFailureSummary(err.errorMessage()));
            }

            // 6. Map parsed types
            List<IndexedTypeEntity> typesToSave = new ArrayList<>();
            Set<String> seenTypeKeys = new HashSet<>();
            for (JavaSourceFileInput file : retrievedFiles) {
                ParsedJavaFile parsed = parserService.parse(file.sourceCode());
                if (parsed != null && parsed.success()) {
                    String pkg = parsed.packageName();
                    for (ParsedType type : parsed.types()) {
                        String simpleName = type.name();
                        String fqn = (pkg != null && !pkg.isBlank()) ? pkg + "." + simpleName : simpleName;
                        String key = fqn + "::" + file.filePath();
                        if (seenTypeKeys.add(key)) {
                            typesToSave.add(new IndexedTypeEntity(
                                null,
                                fqn,
                                simpleName,
                                pkg,
                                file.filePath(),
                                type.kind()
                            ));
                        }
                    }
                }
            }

            // 7. Map dependencies
            List<IndexedTypeDependencyEntity> depsToSave = new ArrayList<>();
            Set<String> seenDepKeys = new HashSet<>();
            for (TypeDependency dep : analysisResult.dependencies()) {
                String key = dep.sourceType() + "->" + dep.targetType() + "::" + dep.kind() + "::" + dep.sourceFilePath();
                if (seenDepKeys.add(key)) {
                    depsToSave.add(new IndexedTypeDependencyEntity(
                        null,
                        dep.sourceType(),
                        dep.targetType(),
                        dep.kind(),
                        dep.sourceFilePath()
                    ));
                }
            }

            // 8. Determine final status
            RepositoryIndexStatus finalStatus;
            String failureReason = null;

            if (discoveredCount > 0 && typesToSave.isEmpty()) {
                finalStatus = RepositoryIndexStatus.FAILED;
                failureReason = "No usable graph could be produced from non-empty production codebase (" + discoveredCount + " files discovered)";
            } else if (isTruncatedOrCapped || downloadFailureOccurred || parseFailureOccurred) {
                finalStatus = RepositoryIndexStatus.PARTIAL;
                List<String> reasons = new ArrayList<>();
                if (treeResult.truncated()) reasons.add("Git tree was truncated by GitHub API");
                if (capped) reasons.add("File discovery capped at limit of " + MAX_PRODUCTION_FILES);
                if (downloadFailureOccurred) reasons.add("Some production files could not be retrieved");
                if (parseFailureOccurred) reasons.add("Some files contained syntax or parse errors");
                failureReason = String.join("; ", reasons);
            } else {
                finalStatus = RepositoryIndexStatus.COMPLETED;
                failureReason = null;
            }

            // 9. Atomic persistence of children and status transition in isolated transaction
            RepositoryIndexEntity finalizedIndex = persistIndexAndFinalize(
                indexId,
                attemptToken,
                repositoryId,
                finalStatus,
                discoveredCount,
                retrievedFiles.size(),
                typesToSave,
                depsToSave,
                isTruncatedOrCapped,
                failureReason
            );

            // 10. Concurrency-safe lastIndexedCommit update outside DB transaction
            if (isDefaultBranch && (finalStatus == RepositoryIndexStatus.COMPLETED || finalStatus == RepositoryIndexStatus.PARTIAL)) {
                updateLastIndexedCommitSafely(repositoryId, cleanOwner, cleanRepo, cleanCommitSha);
            }

            return finalizedIndex;
        } catch (Throwable ex) {
            String sanitized = GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage());
            log.error("Unexpected error during indexing of repositoryId {} at commit {}: {}", repositoryId, cleanCommitSha, sanitized);
            return recordFailure(indexId, attemptToken, repositoryId, cleanCommitSha, ex);
        }
    }


    private void validateInputs(Long repositoryId, String owner, String repo, String commitSha) {
        if (repositoryId == null) {
            throw new IllegalArgumentException("Repository ID must not be null");
        }
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Repository owner must not be null or blank");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be null or blank");
        }
        if (commitSha == null || commitSha.isBlank()) {
            throw new IllegalArgumentException("Commit SHA must not be null or blank");
        }
        if (commitSha.length() > 40) {
            throw new IllegalArgumentException("Commit SHA cannot exceed 40 characters");
        }
    }

    private sealed interface ClaimResult {
        record AlreadyUsable(RepositoryIndexEntity entity) implements ClaimResult {}
        record ActivelyIndexing(RepositoryIndexEntity entity) implements ClaimResult {}
        record Claimed(RepositoryIndexEntity entity) implements ClaimResult {}
    }

    /**
     * Reconciles abandoned repository indexing tasks using the configured stale timeout property.
     * Records in INDEXING status whose startedAt timestamp is older than the cutoff
     * are atomically transitioned to FAILED.
     *
     * @return count of reconciled index records
     */
    @Transactional
    public int reconcileStaleIndexes() {
        Duration timeout = (analysisProperties != null && analysisProperties.getStaleTimeout() != null)
            ? analysisProperties.getStaleTimeout()
            : Duration.ofMinutes(30);
        return reconcileStaleIndexes(timeout);
    }

    /**
     * Reconciles abandoned repository indexing tasks using the specified positive timeout.
     *
     * @param timeout duration after which an INDEXING snapshot is deemed abandoned
     * @return count of reconciled index records
     */
    @Transactional
    public int reconcileStaleIndexes(Duration timeout) {
        return reconcileStaleIndexes(timeout, Instant.now());
    }

    /**
     * Reconciles abandoned repository indexing tasks using the specified timeout and reference completion timestamp.
     *
     * @param timeout duration after which an INDEXING snapshot is deemed abandoned
     * @param completedAt reference timestamp representing reconciliation time
     * @return count of reconciled index records
     */
    @Transactional
    public int reconcileStaleIndexes(Duration timeout, Instant completedAt) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Stale index timeout must be a positive duration");
        }
        Instant executionTime = completedAt != null ? completedAt : Instant.now();
        Instant cutoff = executionTime.minus(timeout);

        int maxAttempts = (retryProperties != null) ? retryProperties.getMaxAttempts() : 3;
        int batchLimit = (retryProperties != null) ? retryProperties.getBatchSize() : 10;

        List<RepositoryIndexEntity> staleCandidates = repositoryIndexRepository.findStaleIndexes(
            RepositoryIndexStatus.INDEXING,
            cutoff,
            PageRequest.of(0, batchLimit)
        );

        if (staleCandidates != null && !staleCandidates.isEmpty()) {
            int reconciledCount = 0;
            for (RepositoryIndexEntity candidate : staleCandidates) {
                Long id = candidate.getId();
                int currentRetryCount = candidate.getRetryCount();

                boolean retryable;
                int newRetryCount;
                Instant nextRetryAt;
                String failureReason;

                if (currentRetryCount < maxAttempts) {
                    newRetryCount = currentRetryCount + 1;
                    retryable = true;
                    nextRetryAt = (retryProperties != null)
                        ? retryProperties.calculateNextRetryAt(currentRetryCount, executionTime, null, null)
                        : executionTime.plusSeconds(10);
                    failureReason = "Stale indexing operation timed out after " + timeout
                        + " (attempt " + newRetryCount + "/" + maxAttempts + "); scheduled for retry";
                } else {
                    newRetryCount = currentRetryCount;
                    retryable = false;
                    nextRetryAt = null;
                    failureReason = "Stale indexing operation timed out after " + timeout
                        + ": exceeded maximum retry attempts (" + maxAttempts + ")";
                }

                TransactionTemplate template = new TransactionTemplate(transactionManager);
                template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

                boolean updated = Boolean.TRUE.equals(template.execute(status -> {
                    int count = repositoryIndexRepository.reconcileStaleIndexById(
                        id,
                        RepositoryIndexStatus.INDEXING,
                        cutoff,
                        RepositoryIndexStatus.FAILED,
                        executionTime,
                        failureReason,
                        retryable,
                        newRetryCount,
                        nextRetryAt
                    );
                    return count == 1;
                }));

                if (updated) {
                    reconciledCount++;
                }
            }
            return reconciledCount;
        }

        // Fallback for compatibility if findStaleIndexes returned empty or was not mocked
        return repositoryIndexRepository.reconcileStaleIndexes(
            RepositoryIndexStatus.INDEXING,
            cutoff,
            RepositoryIndexStatus.FAILED,
            executionTime,
            DEFAULT_STALE_INDEX_FAILURE_REASON
        );
    }

    ClaimResult claimIndexingSnapshot(Long repositoryId, String commitSha) {
        try {
            return executeClaimIndexingSnapshot(repositoryId, commitSha);
        } catch (DataIntegrityViolationException ex) {
            log.debug("Concurrent initial claim race detected for repositoryId {} at commit {}; resolving in fresh transaction",
                repositoryId, commitSha);
            return resolveClaimConflict(repositoryId, commitSha, ex);
        }
    }

    private ClaimResult executeClaimIndexingSnapshot(Long repositoryId, String commitSha) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> {
            Optional<RepositoryIndexEntity> existingOpt = repositoryIndexRepository.findForUpdate(repositoryId, commitSha);
            if (existingOpt.isPresent()) {
                RepositoryIndexEntity existing = existingOpt.get();
                if (existing.getStatus() == RepositoryIndexStatus.COMPLETED || existing.getStatus() == RepositoryIndexStatus.PARTIAL) {
                    return new ClaimResult.AlreadyUsable(existing);
                }
                if (existing.getStatus() == RepositoryIndexStatus.INDEXING) {
                    Duration staleTimeout = (analysisProperties != null && analysisProperties.getStaleTimeout() != null)
                        ? analysisProperties.getStaleTimeout()
                        : Duration.ofMinutes(30);
                    Instant started = (existing.getStartedAt() != null) ? existing.getStartedAt() : existing.getCreatedAt();
                    Instant cutoff = Instant.now().minus(staleTimeout);
                    if (started != null && started.isAfter(cutoff)) {
                        return new ClaimResult.ActivelyIndexing(existing);
                    }
                    // Stale INDEXING! Reclaim it!
                    return new ClaimResult.Claimed(reclaimIndexRecord(existing));
                }
                // FAILED or PENDING: Reclaim it
                return new ClaimResult.Claimed(reclaimIndexRecord(existing));
            }

            // Not found, insert new INDEXING record
            RepositoryEntity repo = repositoryEntityRepository.findById(repositoryId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository not found with id " + repositoryId));

            RepositoryIndexEntity newEntity = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
            newEntity.setStartedAt(Instant.now());
            newEntity.setAttemptToken(UUID.randomUUID().toString());
            RepositoryIndexEntity saved = repositoryIndexRepository.saveAndFlush(newEntity);
            return new ClaimResult.Claimed(saved);
        });
    }

    private ClaimResult resolveClaimConflict(Long repositoryId, String commitSha, DataIntegrityViolationException originalEx) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> {
            RepositoryIndexEntity conflict = repositoryIndexRepository.findForUpdate(repositoryId, commitSha)
                .orElseThrow(() -> originalEx);
            if (conflict.getStatus() == RepositoryIndexStatus.COMPLETED || conflict.getStatus() == RepositoryIndexStatus.PARTIAL) {
                return new ClaimResult.AlreadyUsable(conflict);
            }
            if (conflict.getStatus() == RepositoryIndexStatus.INDEXING) {
                Duration staleTimeout = (analysisProperties != null && analysisProperties.getStaleTimeout() != null)
                    ? analysisProperties.getStaleTimeout()
                    : Duration.ofMinutes(30);
                Instant started = (conflict.getStartedAt() != null) ? conflict.getStartedAt() : conflict.getCreatedAt();
                Instant cutoff = Instant.now().minus(staleTimeout);
                if (started != null && started.isAfter(cutoff)) {
                    return new ClaimResult.ActivelyIndexing(conflict);
                }
                return new ClaimResult.Claimed(reclaimIndexRecord(conflict));
            }
            return new ClaimResult.Claimed(reclaimIndexRecord(conflict));
        });
    }

    private RepositoryIndexEntity reclaimIndexRecord(RepositoryIndexEntity existing) {
        indexedTypeDependencyRepository.deleteByIndexId(existing.getId());
        indexedTypeRepository.deleteByIndexId(existing.getId());
        existing.setStatus(RepositoryIndexStatus.INDEXING);
        existing.setStartedAt(Instant.now());
        existing.setCompletedAt(null);
        existing.setFailureReason(null);
        existing.setRetryCount(0);
        existing.setRetryable(false);
        existing.setNextRetryAt(null);
        existing.setAttemptToken(UUID.randomUUID().toString());
        existing.setTotalFilesDiscovered(0);
        existing.setTotalFilesIndexed(0);
        existing.setTotalTypesIndexed(0);
        existing.setTotalDependenciesIndexed(0);
        existing.setTruncatedOrCapped(false);
        return repositoryIndexRepository.saveAndFlush(existing);
    }

    private RepositoryIndexEntity failSnapshot(Long indexId, String reason) {
        return failSnapshot(indexId, null, reason);
    }

    private RepositoryIndexEntity failSnapshot(Long indexId, String attemptToken, String reason) {
        return recordFailure(indexId, attemptToken, null, null, new RuntimeException(reason));
    }

    /**
     * Transitions an existing snapshot in INDEXING status to FAILED if it was interrupted or aborted.
     */
    public RepositoryIndexEntity failSnapshotIfIndexing(Long repositoryId, String commitSha, String reason) {
        return failSnapshotIfIndexing(repositoryId, commitSha, null, reason, null);
    }

    /**
     * Transitions an existing snapshot in INDEXING status to FAILED, evaluating whether the failure
     * is transient and eligible for delayed retry.
     */
    public RepositoryIndexEntity failSnapshotIfIndexing(Long repositoryId, String commitSha, String reason, Throwable cause) {
        return failSnapshotIfIndexing(repositoryId, commitSha, null, reason, cause);
    }

    /**
     * Transitions an existing snapshot in INDEXING status to FAILED with attempt ownership verification.
     */
    public RepositoryIndexEntity failSnapshotIfIndexing(Long repositoryId, String commitSha, String attemptToken, String reason, Throwable cause) {
        boolean retryable = (cause != null) && IndexingRetryClassifier.isRetryable(cause);
        Instant resetAt = (cause != null) ? IndexingRetryClassifier.extractResetAt(cause) : null;
        Duration retryAfter = (cause != null) ? IndexingRetryClassifier.extractRetryAfter(cause) : null;
        String sanitized = GitHubWebhookDeliveryService.sanitizeFailureSummary(reason);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
        return template.execute(status -> {
            Optional<RepositoryIndexEntity> indexOpt = repositoryIndexRepository.findForUpdate(repositoryId, commitSha);
            if (indexOpt.isEmpty()) {
                return null;
            }
            RepositoryIndexEntity index = indexOpt.get();
            if (index.getStatus() == RepositoryIndexStatus.INDEXING) {
                // Verify attempt ownership: if the database entity has a non-null attemptToken,
                // every mutating operation must present the exact matching token.
                // A null or missing caller token must not bypass ownership verification.
                if (index.getAttemptToken() != null && !Objects.equals(index.getAttemptToken(), attemptToken)) {
                    log.warn("Worker with attemptToken {} cannot fail snapshot for repoId {} commit {}; current token is {}",
                        attemptToken, repositoryId, commitSha, index.getAttemptToken());
                    return index;
                }
                Instant now = Instant.now();
                int currentRetryCount = index.getRetryCount();
                int maxAttempts = (retryProperties != null) ? retryProperties.getMaxAttempts() : 3;

                if (retryable && currentRetryCount < maxAttempts) {
                    Instant nextRetryAt = (retryProperties != null)
                        ? retryProperties.calculateNextRetryAt(currentRetryCount, now, resetAt, retryAfter)
                        : now.plusSeconds(10);
                    index.setStatus(RepositoryIndexStatus.FAILED);
                    index.setRetryable(true);
                    index.setRetryCount(currentRetryCount + 1);
                    index.setNextRetryAt(nextRetryAt);
                    index.setFailureReason("Retryable error (attempt " + (currentRetryCount + 1) + "/" + maxAttempts + "): " + sanitized);
                    index.setCompletedAt(now);
                } else {
                    index.setStatus(RepositoryIndexStatus.FAILED);
                    index.setRetryable(false);
                    index.setNextRetryAt(null);
                    if (retryable && currentRetryCount >= maxAttempts) {
                        index.setFailureReason("Exceeded maximum retry attempts (" + maxAttempts + "): " + sanitized);
                    } else {
                        index.setFailureReason(sanitized);
                    }
                    index.setCompletedAt(now);
                }
                return repositoryIndexRepository.saveAndFlush(index);
            }
            return index;
        });
    }

    /**
     * Records an indexing failure, classifying the exception and scheduling delayed retry if transient.
     */
    public RepositoryIndexEntity recordFailure(Long indexId, Long repositoryId, String commitSha, Throwable ex) {
        return recordFailure(indexId, null, repositoryId, commitSha, ex);
    }

    /**
     * Records an indexing failure with attempt ownership verification, preventing stale workers from overwriting newer attempts.
     */
    public RepositoryIndexEntity recordFailure(Long indexId, String attemptToken, Long repositoryId, String commitSha, Throwable ex) {
        boolean retryable = IndexingRetryClassifier.isRetryable(ex);
        Instant resetAt = IndexingRetryClassifier.extractResetAt(ex);
        Duration retryAfter = IndexingRetryClassifier.extractRetryAfter(ex);
        String rawMsg = (ex != null && ex.getMessage() != null) ? ex.getMessage() : "Unknown indexing error";
        String sanitized = GitHubWebhookDeliveryService.sanitizeFailureSummary(rawMsg);

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> {
            RepositoryIndexEntity index = null;
            if (indexId != null) {
                index = repositoryIndexRepository.findById(indexId).orElse(null);
            }
            if (index == null && repositoryId != null && commitSha != null) {
                index = repositoryIndexRepository.findForUpdate(repositoryId, commitSha).orElse(null);
            }
            if (index == null) {
                return null;
            }

            // Never overwrite usable COMPLETED or PARTIAL snapshots
            if (index.getStatus() == RepositoryIndexStatus.COMPLETED || index.getStatus() == RepositoryIndexStatus.PARTIAL) {
                return index;
            }

            // Verify attempt ownership:
            // 1. If database entity has a non-null attemptToken, caller must present exact matching token.
            //    A null or missing caller token must not bypass ownership verification.
            if (index.getAttemptToken() != null && !Objects.equals(index.getAttemptToken(), attemptToken)) {
                log.warn("Worker with attemptToken {} cannot record failure for index {}; current attemptToken is {}",
                    attemptToken, index.getId(), index.getAttemptToken());
                return index;
            }
            // 2. If entity is already in FAILED status waiting for retry, an unauthenticated or stale worker cannot mutate it.
            if (index.getStatus() == RepositoryIndexStatus.FAILED && index.isRetryable()) {
                log.debug("Index {} is already in FAILED status waiting for retry; skipping redundant failure recording", index.getId());
                return index;
            }

            Instant now = Instant.now();
            int currentRetryCount = index.getRetryCount();
            int maxAttempts = (retryProperties != null) ? retryProperties.getMaxAttempts() : 3;

            if (retryable && currentRetryCount < maxAttempts) {
                Instant nextRetryAt = (retryProperties != null)
                    ? retryProperties.calculateNextRetryAt(currentRetryCount, now, resetAt, retryAfter)
                    : now.plusSeconds(10);
                index.setStatus(RepositoryIndexStatus.FAILED);
                index.setRetryable(true);
                index.setRetryCount(currentRetryCount + 1);
                index.setNextRetryAt(nextRetryAt);
                index.setFailureReason("Retryable error (attempt " + (currentRetryCount + 1) + "/" + maxAttempts + "): " + sanitized);
                index.setCompletedAt(now);
            } else {
                index.setStatus(RepositoryIndexStatus.FAILED);
                index.setRetryable(false);
                index.setNextRetryAt(null);
                if (retryable && currentRetryCount >= maxAttempts) {
                    index.setFailureReason("Exceeded maximum retry attempts (" + maxAttempts + "): " + sanitized);
                } else {
                    index.setFailureReason(sanitized);
                }
                index.setCompletedAt(now);
            }

            return repositoryIndexRepository.saveAndFlush(index);
        });
    }

    /**
     * Durably persists a task rejection as a retryable failure so the work is not lost when the executor queue is full.
     * Safely handles concurrent insertion races without emitting unhandled error traces.
     */
    public RepositoryIndexEntity recordTaskRejection(Long repositoryId, String owner, String repoName, String commitSha, RejectedExecutionException ex) {
        try {
            return executeRecordTaskRejection(repositoryId, commitSha, ex);
        } catch (DataIntegrityViolationException dive) {
            log.debug("Concurrent insertion detected during task rejection recording for repositoryId {} at commit {}; retrying update",
                repositoryId, commitSha);
            try {
                return executeRecordTaskRejection(repositoryId, commitSha, ex);
            } catch (Exception retryEx) {
                log.debug("Fallback fetch after concurrent rejection insert for repositoryId {} at commit {}: {}",
                    repositoryId, commitSha, retryEx.getMessage());
                return repositoryIndexRepository.findByRepositoryIdAndCommitSha(repositoryId, commitSha).orElse(null);
            }
        }
    }

    private RepositoryIndexEntity executeRecordTaskRejection(Long repositoryId, String commitSha, RejectedExecutionException ex) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> {
            Optional<RepositoryIndexEntity> existingOpt = repositoryIndexRepository.findForUpdate(repositoryId, commitSha);
            RepositoryIndexEntity index;
            if (existingOpt.isPresent()) {
                index = existingOpt.get();
                if (index.getStatus() == RepositoryIndexStatus.COMPLETED || index.getStatus() == RepositoryIndexStatus.PARTIAL) {
                    return index;
                }
                if (index.getStatus() == RepositoryIndexStatus.INDEXING) {
                    Duration staleTimeout = (analysisProperties != null && analysisProperties.getStaleTimeout() != null)
                        ? analysisProperties.getStaleTimeout()
                        : Duration.ofMinutes(30);
                    Instant started = (index.getStartedAt() != null) ? index.getStartedAt() : index.getCreatedAt();
                    Instant cutoff = Instant.now().minus(staleTimeout);
                    if (started != null && started.isAfter(cutoff)) {
                        log.info("Index for repositoryId {} at commit {} is actively INDEXING; not overwriting on task rejection",
                            repositoryId, commitSha);
                        return index;
                    }
                }
            } else {
                RepositoryEntity repo = repositoryEntityRepository.findById(repositoryId).orElse(null);
                if (repo == null) {
                    return null;
                }
                index = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
            }

            Instant now = Instant.now();
            int currentRetryCount = index.getRetryCount();
            int maxAttempts = (retryProperties != null) ? retryProperties.getMaxAttempts() : 3;

            if (currentRetryCount < maxAttempts) {
                Instant nextRetryAt = (retryProperties != null)
                    ? retryProperties.calculateNextRetryAt(currentRetryCount, now, null, null)
                    : now.plusSeconds(10);
                index.setStatus(RepositoryIndexStatus.FAILED);
                index.setRetryable(true);
                index.setRetryCount(currentRetryCount + 1);
                index.setNextRetryAt(nextRetryAt);
                index.setFailureReason("Task rejected by executor (attempt " + (currentRetryCount + 1) + "/" + maxAttempts + "): queue capacity saturated");
                index.setCompletedAt(now);
            } else {
                index.setStatus(RepositoryIndexStatus.FAILED);
                index.setRetryable(false);
                index.setNextRetryAt(null);
                index.setFailureReason("Task rejected by executor: exceeded maximum retry attempts (" + maxAttempts + ")");
                index.setCompletedAt(now);
            }

            return repositoryIndexRepository.saveAndFlush(index);
        });
    }

    /**
     * Identifies and dispatches due retries using atomic claims to prevent duplicate concurrent work.
     *
     * @return count of successfully claimed and dispatched retries
     */
    public int reconcileDueRetries() {
        return reconcileDueRetries(Instant.now());
    }

    /**
     * Identifies and dispatches due retries as of the specified reference time.
     *
     * @param now reference timestamp
     * @return count of successfully claimed and dispatched retries
     */
    public int reconcileDueRetries(Instant now) {
        Instant executionTime = (now != null) ? now : Instant.now();

        int availableCapacity = getAvailableExecutorCapacity();
        if (availableCapacity <= 0) {
            log.debug("No executor capacity available; deferring due retry reconciliation");
            return 0;
        }

        int batchLimit = (retryProperties != null) ? retryProperties.getBatchSize() : 10;
        int fetchLimit = Math.min(availableCapacity, batchLimit);

        List<RepositoryIndexEntity> dueRetries = repositoryIndexRepository.findDueRetries(
            RepositoryIndexStatus.FAILED,
            executionTime,
            PageRequest.of(0, fetchLimit)
        );

        if (dueRetries.isEmpty()) {
            return 0;
        }

        int dispatchedCount = 0;
        for (RepositoryIndexEntity due : dueRetries) {
            Long indexId = due.getId();
            Long repositoryId = due.getRepository().getId();
            String commitSha = due.getCommitSha();
            String owner = due.getRepository().getOwner();
            String repoName = due.getRepository().getName();
            boolean isDefault = true;

            String attemptToken = UUID.randomUUID().toString();

            TransactionTemplate claimTemplate = new TransactionTemplate(transactionManager);
            claimTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

            boolean claimed = Boolean.TRUE.equals(claimTemplate.execute(status -> {
                int updated = repositoryIndexRepository.claimDueRetry(
                    indexId,
                    RepositoryIndexStatus.FAILED,
                    RepositoryIndexStatus.INDEXING,
                    executionTime,
                    attemptToken
                );
                return updated == 1;
            }));

            if (!claimed) {
                continue;
            }

            if (taskExecutor != null) {
                try {
                    taskExecutor.execute(() -> {
                        try {
                            performIndexing(indexId, attemptToken, repositoryId, owner, repoName, commitSha, isDefault);
                        } catch (Throwable ex) {
                            log.error("Retried indexing failed for repositoryId {} at commit {}: {}",
                                repositoryId, commitSha, GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage()));
                            recordFailure(indexId, attemptToken, repositoryId, commitSha, ex);
                        }
                    });
                    dispatchedCount++;
                } catch (RejectedExecutionException ex) {
                    log.warn("Executor rejected retried task for repositoryId {} at commit {}: queue full", repositoryId, commitSha);
                    TransactionTemplate revertTemplate = new TransactionTemplate(transactionManager);
                    revertTemplate.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);
                    revertTemplate.executeWithoutResult(s -> {
                        repositoryIndexRepository.findById(indexId).ifPresent(entity -> {
                            if (Objects.equals(entity.getAttemptToken(), attemptToken)) {
                                entity.setStatus(RepositoryIndexStatus.FAILED);
                                entity.setRetryable(true);
                                entity.setNextRetryAt(Instant.now().plusSeconds(30));
                                entity.setFailureReason("Retry dispatch rejected: executor capacity saturated");
                                repositoryIndexRepository.saveAndFlush(entity);
                            }
                        });
                    });
                }
            } else {
                try {
                    performIndexing(indexId, attemptToken, repositoryId, owner, repoName, commitSha, isDefault);
                    dispatchedCount++;
                } catch (Throwable ex) {
                    recordFailure(indexId, attemptToken, repositoryId, commitSha, ex);
                }
            }
        }

        return dispatchedCount;
    }

    private int getAvailableExecutorCapacity() {
        if (taskExecutor == null) {
            return 10;
        }
        if (taskExecutor instanceof ThreadPoolTaskExecutor tpte) {
            ThreadPoolExecutor tpe = tpte.getThreadPoolExecutor();
            if (tpe.isShutdown() || tpe.isTerminating()) {
                return 0;
            }
            return tpe.getQueue().remainingCapacity();
        }
        return 10;
    }

    private RepositoryIndexEntity persistIndexAndFinalize(
        Long indexId,
        String attemptToken,
        Long repositoryId,
        RepositoryIndexStatus finalStatus,
        int discoveredCount,
        int indexedFilesCount,
        List<IndexedTypeEntity> typesToSave,
        List<IndexedTypeDependencyEntity> depsToSave,
        boolean isTruncatedOrCapped,
        String failureReason
    ) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> {
            RepositoryIndexEntity index = repositoryIndexRepository.findById(indexId)
                .orElseThrow(() -> new ResourceNotFoundException("Repository index not found with id " + indexId));

            // Verify attempt ownership: if the database entity has a non-null attemptToken,
            // every mutating operation must present the exact matching token.
            // A null or missing caller token must not bypass ownership verification.
            if (index.getAttemptToken() != null && !Objects.equals(index.getAttemptToken(), attemptToken)) {
                log.warn("Worker with attemptToken {} cannot finalize index {}; current attemptToken is {}",
                    attemptToken, indexId, index.getAttemptToken());
                return index;
            }

            // Stale workers cannot finalize an index that is no longer in INDEXING status
            if (index.getStatus() != RepositoryIndexStatus.INDEXING) {
                log.warn("Stale worker with attemptToken {} cannot finalize index {} with status {}",
                    attemptToken, indexId, index.getStatus());
                return index;
            }

            // Delete any existing children (in case of retry/recovery)
            indexedTypeDependencyRepository.deleteByIndexId(indexId);
            indexedTypeRepository.deleteByIndexId(indexId);

            for (IndexedTypeEntity type : typesToSave) {
                type.setIndex(index);
            }
            indexedTypeRepository.saveAll(typesToSave);

            for (IndexedTypeDependencyEntity dep : depsToSave) {
                dep.setIndex(index);
            }
            indexedTypeDependencyRepository.saveAll(depsToSave);

            index.setStatus(finalStatus);
            if (finalStatus == RepositoryIndexStatus.COMPLETED || finalStatus == RepositoryIndexStatus.PARTIAL) {
                index.setRetryable(false);
                index.setNextRetryAt(null);
            }
            index.setTotalFilesDiscovered(discoveredCount);
            index.setTotalFilesIndexed(indexedFilesCount);
            index.setTotalTypesIndexed(typesToSave.size());
            index.setTotalDependenciesIndexed(depsToSave.size());
            index.setTruncatedOrCapped(isTruncatedOrCapped);
            index.setFailureReason(failureReason != null ? GitHubWebhookDeliveryService.sanitizeFailureSummary(failureReason) : null);
            index.setCompletedAt(Instant.now());

            return repositoryIndexRepository.saveAndFlush(index);
        });
    }


    private static final int MAX_POINTER_UPDATE_RETRIES = 2;

    void updateLastIndexedCommitSafely(Long repositoryId, String owner, String repoName, String commitSha) {
        for (int attempt = 0; attempt < MAX_POINTER_UPDATE_RETRIES; attempt++) {
            RepositoryEntity repo = repositoryEntityRepository.findById(repositoryId).orElse(null);
            if (repo == null) {
                return;
            }

            String defaultBranch = repo.getDefaultBranch();
            if (defaultBranch == null || defaultBranch.isBlank()) {
                defaultBranch = "main";
            }

            String expectedPreviousCommit = repo.getLastIndexedCommit();
            if (commitSha.equalsIgnoreCase(expectedPreviousCommit)) {
                // Already pointing to this commit
                return;
            }

            // 1. Verify that the indexed commit is still current default-branch HEAD on GitHub (outside DB transaction)
            String currentHeadSha;
            try {
                currentHeadSha = gitHubGateway.getBranchHeadCommit(owner, repoName, defaultBranch);
            } catch (Throwable ex) {
                log.warn("Failed to retrieve branch HEAD commit from GitHub for {}/{} (branch: {}): {}",
                    owner, repoName, defaultBranch, GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage()));
                return;
            }

            if (currentHeadSha == null || !commitSha.equalsIgnoreCase(currentHeadSha.trim())) {
                log.info("Not updating lastIndexedCommit for repository {} ({}/{}): indexed commit {} is not current default-branch HEAD ({})",
                    repositoryId, owner, repoName, commitSha, currentHeadSha);
                return;
            }

            // 2. Concurrency-safe database update inside short isolated transaction
            TransactionTemplate template = new TransactionTemplate(transactionManager);
            template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRES_NEW);

            Boolean updateSuccess = template.execute(status -> {
                RepositoryEntity lockedRepo = repositoryEntityRepository.findByIdForUpdate(repositoryId).orElse(null);
                if (lockedRepo == null) {
                    return true;
                }

                if (commitSha.equalsIgnoreCase(lockedRepo.getLastIndexedCommit())) {
                    return true;
                }

                // Check if pointer changed while validating with GitHub
                if (!Objects.equals(lockedRepo.getLastIndexedCommit(), expectedPreviousCommit)) {
                    log.info("Concurrent pointer update detected for repository {}: expected {} but found {}; re-verifying",
                        repositoryId, expectedPreviousCommit, lockedRepo.getLastIndexedCommit());
                    return false; // trigger retry
                }

                lockedRepo.setLastIndexedCommit(commitSha);
                repositoryEntityRepository.saveAndFlush(lockedRepo);
                log.info("Updated lastIndexedCommit for repository {} ({}) to current default-branch HEAD {}",
                    repositoryId, lockedRepo.getFullName(), commitSha);
                return true;
            });

            if (Boolean.TRUE.equals(updateSuccess)) {
                return;
            }
        }
    }
}
