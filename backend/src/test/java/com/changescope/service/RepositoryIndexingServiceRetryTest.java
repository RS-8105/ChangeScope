package com.changescope.service;

import com.changescope.config.AnalysisProperties;
import com.changescope.config.IndexingRetryProperties;
import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import com.changescope.service.github.GitHubGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryIndexingServiceRetryTest {

    @Mock
    private GitHubGateway gitHubGateway;

    @Mock
    private JavaDependencyAnalysisService dependencyAnalysisService;

    @Mock
    private JavaSourceParserService parserService;

    @Mock
    private RepositoryEntityRepository repositoryEntityRepository;

    @Mock
    private RepositoryIndexRepository repositoryIndexRepository;

    @Mock
    private IndexedTypeRepository indexedTypeRepository;

    @Mock
    private IndexedTypeDependencyRepository indexedTypeDependencyRepository;

    @Mock
    private Executor taskExecutor;

    private PlatformTransactionManager transactionManager;
    private AnalysisProperties analysisProperties;
    private IndexingRetryProperties retryProperties;
    private RepositoryIndexingService indexingService;
    private RepositoryEntity repo;

    @BeforeEach
    void setUp() {
        transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
            }
        };

        analysisProperties = new AnalysisProperties();
        retryProperties = new IndexingRetryProperties();
        retryProperties.setInitialDelay(Duration.ofSeconds(10));
        retryProperties.setMultiplier(2.0);
        retryProperties.setMaxDelay(Duration.ofMinutes(1));
        retryProperties.setJitterFactor(0.0); // deterministic

        repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);

        indexingService = new RepositoryIndexingService(
            gitHubGateway,
            dependencyAnalysisService,
            parserService,
            repositoryEntityRepository,
            repositoryIndexRepository,
            indexedTypeRepository,
            indexedTypeDependencyRepository,
            transactionManager,
            analysisProperties,
            retryProperties,
            taskExecutor
        );
    }

    @Test
    @DisplayName("Transient 5xx failure schedules delayed retry with incremented retryCount and nextRetryAt")
    void recordFailure_transient5xx_schedulesDelayedRetry() {
        String commitSha = "transient5xx111111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(101L);
        indexing.setRetryCount(0);

        when(repositoryIndexRepository.findById(101L)).thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        RepositoryIndexEntity failed = indexingService.recordFailure(
            101L, 42L, commitSha, new GitHubApiException(503, "UNAVAILABLE", "Upstream 503")
        );

        assertThat(failed.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(failed.isRetryable()).isTrue();
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getNextRetryAt()).isNotNull();
        assertThat(failed.getNextRetryAt()).isAfterOrEqualTo(before.plusSeconds(10));
        assertThat(failed.getFailureReason()).contains("Retryable error (attempt 1/3)");
    }

    @Test
    @DisplayName("Rate limit exception schedules next retry at exact resetAt timestamp")
    void recordFailure_rateLimit_schedulesAtResetAt() {
        String commitSha = "ratelimit11111111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(102L);

        when(repositoryIndexRepository.findById(102L)).thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Instant resetTime = Instant.now().plusSeconds(75);
        GitHubRateLimitException rle = new GitHubRateLimitException("Rate limit", resetTime, null);

        RepositoryIndexEntity failed = indexingService.recordFailure(102L, 42L, commitSha, rle);

        assertThat(failed.isRetryable()).isTrue();
        assertThat(failed.getNextRetryAt()).isEqualTo(resetTime);
    }

    @Test
    @DisplayName("Exception with Retry-After header schedules next retry after specified duration")
    void recordFailure_withRetryAfter_schedulesAfterDuration() {
        String commitSha = "retryafter111111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(103L);

        when(repositoryIndexRepository.findById(103L)).thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        Instant before = Instant.now();
        Duration retryAfter = Duration.ofSeconds(45);
        GitHubApiException ex = new GitHubApiException(429, "TOO_MANY_REQUESTS", "Too Many Requests", null, retryAfter);

        RepositoryIndexEntity failed = indexingService.recordFailure(103L, 42L, commitSha, ex);

        assertThat(failed.isRetryable()).isTrue();
        assertThat(failed.getNextRetryAt()).isAfterOrEqualTo(before.plusSeconds(45));
    }

    @Test
    @DisplayName("Permanent 4xx failure (e.g. 401 auth or 404 missing) is not retried")
    void recordFailure_permanentError_isNotRetryable() {
        String commitSha = "permanent1111111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(104L);

        when(repositoryIndexRepository.findById(104L)).thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RepositoryIndexEntity failed = indexingService.recordFailure(
            104L, 42L, commitSha, new GitHubAuthenticationException(401, "Invalid token")
        );

        assertThat(failed.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(failed.isRetryable()).isFalse();
        assertThat(failed.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("Successive failures exceed maxAttempts and transition to permanent non-retryable failure")
    void recordFailure_exceedingMaxAttempts_becomesNonRetryable() {
        String commitSha = "maxattempts11111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(105L);
        indexing.setRetryCount(3); // Already attempted 3 times

        when(repositoryIndexRepository.findById(105L)).thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RepositoryIndexEntity failed = indexingService.recordFailure(
            105L, 42L, commitSha, new GitHubApiException(502, "BAD_GATEWAY", "Bad gateway")
        );

        assertThat(failed.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(failed.isRetryable()).isFalse();
        assertThat(failed.getNextRetryAt()).isNull();
        assertThat(failed.getFailureReason()).contains("Exceeded maximum retry attempts (3)");
    }

    @Test
    @DisplayName("COMPLETED and PARTIAL snapshots are never overwritten by failure recording")
    void recordFailure_completedSnapshot_isNeverOverwritten() {
        String commitSha = "completed11111111111111111111111111111";
        RepositoryIndexEntity completed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.COMPLETED);
        completed.setId(106L);

        when(repositoryIndexRepository.findById(106L)).thenReturn(Optional.of(completed));

        RepositoryIndexEntity result = indexingService.recordFailure(
            106L, 42L, commitSha, new GitHubApiException(500, "ERROR", "Server error")
        );

        assertThat(result).isSameAs(completed);
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("reconcileDueRetries claims due snapshot and dispatches to executor")
    void reconcileDueRetries_dueSnapshot_claimsAndDispatches() {
        String commitSha = "dueretry11111111111111111111111111111";
        RepositoryIndexEntity due = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        due.setId(201L);
        due.setRetryable(true);
        due.setNextRetryAt(Instant.now().minusSeconds(5));

        when(repositoryIndexRepository.findDueRetries(eq(RepositoryIndexStatus.FAILED), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(due));
        when(repositoryIndexRepository.claimDueRetry(eq(201L), eq(RepositoryIndexStatus.FAILED), eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(String.class)))
            .thenReturn(1);

        int dispatched = indexingService.reconcileDueRetries();

        assertThat(dispatched).isEqualTo(1);
        verify(repositoryIndexRepository).claimDueRetry(eq(201L), eq(RepositoryIndexStatus.FAILED), eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(String.class));
        verify(taskExecutor).execute(any(Runnable.class));
    }

    @Test
    @DisplayName("reconcileDueRetries skips snapshot when another instance won the claim race")
    void reconcileDueRetries_lostClaimRace_skipsDispatch() {
        String commitSha = "lostrace11111111111111111111111111111";
        RepositoryIndexEntity due = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        due.setId(202L);
        due.setRetryable(true);
        due.setNextRetryAt(Instant.now().minusSeconds(5));

        when(repositoryIndexRepository.findDueRetries(eq(RepositoryIndexStatus.FAILED), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(due));
        // Another instance updated it first -> returns 0
        when(repositoryIndexRepository.claimDueRetry(eq(202L), eq(RepositoryIndexStatus.FAILED), eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(String.class)))
            .thenReturn(0);

        int dispatched = indexingService.reconcileDueRetries();

        assertThat(dispatched).isEqualTo(0);
        verify(taskExecutor, never()).execute(any());
    }

    @Test
    @DisplayName("CS-1: A scheduled retry actually executes indexing rather than returning as already INDEXING")
    void reconcileDueRetries_actuallyExecutesIndexing_withoutAbortingAsActivelyIndexing() {
        String commitSha = "actualretry111111111111111111111111111";
        RepositoryIndexEntity due = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        due.setId(205L);
        due.setRetryable(true);
        due.setNextRetryAt(Instant.now().minusSeconds(5));

        when(repositoryIndexRepository.findDueRetries(eq(RepositoryIndexStatus.FAILED), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(due));
        when(repositoryIndexRepository.claimDueRetry(eq(205L), eq(RepositoryIndexStatus.FAILED), eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(String.class)))
            .thenReturn(1);

        // Run worker synchronously when taskExecutor.execute is called
        org.mockito.Mockito.doAnswer(invocation -> {
            Runnable task = invocation.getArgument(0);
            task.run();
            return null;
        }).when(taskExecutor).execute(any(Runnable.class));

        // When performIndexing runs, gitHubGateway.getGitTree must be called!
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new com.changescope.dto.GitTreeResult(List.of(), false));
        when(dependencyAnalysisService.analyze(org.mockito.ArgumentMatchers.<List<JavaSourceFileInput>>any())).thenReturn(
            new DependencyAnalysisResult(List.of(), List.of())
        );

        RepositoryIndexEntity claimedInDb = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimedInDb.setId(205L);
        when(repositoryIndexRepository.findById(205L)).thenReturn(Optional.of(claimedInDb));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        int dispatched = indexingService.reconcileDueRetries();

        assertThat(dispatched).isEqualTo(1);
        // Verify gitHubGateway.getGitTree was actually invoked!
        verify(gitHubGateway).getGitTree("octocat", "hello-world", commitSha);
    }

    @Test
    @DisplayName("CS-2: Stale worker with outdated attemptToken cannot fail a newer attempt")
    void recordFailure_staleWorkerWithOldToken_doesNotFailNewerAttempt() {
        String commitSha = "stalefail11111111111111111111111111111";
        RepositoryIndexEntity activeAttempt = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        activeAttempt.setId(301L);
        activeAttempt.setAttemptToken("token-attempt-2");
        activeAttempt.setRetryCount(1);

        when(repositoryIndexRepository.findById(301L)).thenReturn(Optional.of(activeAttempt));

        // Stale worker from attempt 1 fails
        RepositoryIndexEntity result = indexingService.recordFailure(
            301L,
            "token-attempt-1", // outdated token
            42L,
            commitSha,
            new GitHubApiException(503, "UNAVAILABLE", "Late timeout from attempt 1")
        );

        // Active attempt remains INDEXING with token-attempt-2
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.INDEXING);
        assertThat(result.getAttemptToken()).isEqualTo("token-attempt-2");
        assertThat(result.getRetryCount()).isEqualTo(1);
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("CS-2: Stale worker with outdated attemptToken cannot finalize or overwrite a newer attempt")
    void performIndexing_staleWorkerWithOldToken_cannotFinalizeNewerAttempt() {
        String commitSha = "stalecomplete1111111111111111111111111";
        RepositoryIndexEntity newerAttempt = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        newerAttempt.setId(302L);
        newerAttempt.setAttemptToken("token-newer-attempt");

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new com.changescope.dto.GitTreeResult(List.of(), false));
        when(dependencyAnalysisService.analyze(org.mockito.ArgumentMatchers.<List<JavaSourceFileInput>>any())).thenReturn(
            new DependencyAnalysisResult(List.of(), List.of())
        );
        when(repositoryIndexRepository.findById(302L)).thenReturn(Optional.of(newerAttempt));

        // Stale worker runs with old token
        RepositoryIndexEntity result = indexingService.performIndexing(
            302L,
            "token-old-attempt",
            42L,
            "octocat",
            "hello-world",
            commitSha,
            false
        );

        // Newer attempt remains INDEXING with token-newer-attempt
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.INDEXING);
        assertThat(result.getAttemptToken()).isEqualTo("token-newer-attempt");
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("CS-3: Stale reconciliation schedules a retry when attempts remain")
    void reconcileStaleIndexes_schedulesRetry_whenAttemptsRemain() {
        Instant executionTime = Instant.now();
        Duration timeout = Duration.ofMinutes(30);
        Instant cutoff = executionTime.minus(timeout);

        String commitSha = "stalerecovery111111111111111111111111";
        RepositoryIndexEntity stale = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        stale.setId(401L);
        stale.setRetryCount(1); // 1 attempt made, 2 remaining

        when(repositoryIndexRepository.findStaleIndexes(eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(stale));
        when(repositoryIndexRepository.reconcileStaleIndexById(
            eq(401L),
            eq(RepositoryIndexStatus.INDEXING),
            any(Instant.class),
            eq(RepositoryIndexStatus.FAILED),
            any(Instant.class),
            any(String.class),
            eq(true), // retryable
            eq(2),    // incremented retryCount
            any(Instant.class) // nextRetryAt scheduled
        )).thenReturn(1);

        int count = indexingService.reconcileStaleIndexes(timeout, executionTime);

        assertThat(count).isEqualTo(1);
        verify(repositoryIndexRepository).reconcileStaleIndexById(
            eq(401L),
            eq(RepositoryIndexStatus.INDEXING),
            any(Instant.class),
            eq(RepositoryIndexStatus.FAILED),
            any(Instant.class),
            any(String.class),
            eq(true),
            eq(2),
            any(Instant.class)
        );
    }

    @Test
    @DisplayName("CS-3: Stale reconciliation permanently fails when retry budget is exhausted")
    void reconcileStaleIndexes_permanentlyFails_whenRetryBudgetExhausted() {
        Instant executionTime = Instant.now();
        Duration timeout = Duration.ofMinutes(30);

        String commitSha = "staleexhausted11111111111111111111111";
        RepositoryIndexEntity stale = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        stale.setId(402L);
        stale.setRetryCount(3); // All 3 attempts exhausted

        when(repositoryIndexRepository.findStaleIndexes(eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(stale));
        when(repositoryIndexRepository.reconcileStaleIndexById(
            eq(402L),
            eq(RepositoryIndexStatus.INDEXING),
            any(Instant.class),
            eq(RepositoryIndexStatus.FAILED),
            any(Instant.class),
            any(String.class),
            eq(false), // NOT retryable
            eq(3),     // retryCount unchanged
            org.mockito.ArgumentMatchers.isNull() // nextRetryAt is null
        )).thenReturn(1);

        int count = indexingService.reconcileStaleIndexes(timeout, executionTime);

        assertThat(count).isEqualTo(1);
        verify(repositoryIndexRepository).reconcileStaleIndexById(
            eq(402L),
            eq(RepositoryIndexStatus.INDEXING),
            any(Instant.class),
            eq(RepositoryIndexStatus.FAILED),
            any(Instant.class),
            any(String.class),
            eq(false),
            eq(3),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    @DisplayName("CS-3: Stale reconciliation is idempotent under repeated execution")
    void reconcileStaleIndexes_isIdempotent_whenConcurrentOrRepeated() {
        Instant executionTime = Instant.now();
        Duration timeout = Duration.ofMinutes(30);

        String commitSha = "idempotent11111111111111111111111111";
        RepositoryIndexEntity stale = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        stale.setId(403L);

        when(repositoryIndexRepository.findStaleIndexes(eq(RepositoryIndexStatus.INDEXING), any(Instant.class), any(Pageable.class)))
            .thenReturn(List.of(stale));
        // Another instance claimed it first -> returns 0
        when(repositoryIndexRepository.reconcileStaleIndexById(
            eq(403L), any(), any(), any(), any(), any(), any(Boolean.class), any(Integer.class), any()
        )).thenReturn(0);

        int count = indexingService.reconcileStaleIndexes(timeout, executionTime);

        assertThat(count).isEqualTo(0);
    }

    @Test
    @DisplayName("CS-4: Concurrent task-rejection persistence handles duplicate snapshot creation safely")
    void recordTaskRejection_handlesConcurrentInsertRace_safely() {
        String commitSha = "concurrency11111111111111111111111111";

        RepositoryIndexEntity existing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        existing.setId(501L);
        existing.setRetryCount(1);
        existing.setRetryable(true);

        // First attempt in executeRecordTaskRejection throws DataIntegrityViolationException (simulating race)
        // Second attempt finds the committed row and updates it cleanly
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty()) // first attempt sees empty
            .thenReturn(Optional.of(existing)); // retry attempt sees committed entity
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("Duplicate key")) // first attempt throws
            .thenAnswer(inv -> inv.getArgument(0)); // retry attempt succeeds

        RepositoryIndexEntity result = indexingService.recordTaskRejection(
            42L, "octocat", "hello-world", commitSha, new RejectedExecutionException("Queue saturated")
        );

        assertThat(result).isNotNull();
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(result.isRetryable()).isTrue();
    }

    @Test
    @DisplayName("recordTaskRejection durably saves rejected push event as retryable in database")
    void recordTaskRejection_persistsRetryableSnapshot() {
        String commitSha = "rejection1111111111111111111111111111";

        when(repositoryIndexRepository.findForUpdate(42L, commitSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RepositoryIndexEntity saved = indexingService.recordTaskRejection(
            42L, "octocat", "hello-world", commitSha, new RejectedExecutionException("Queue full")
        );

        assertThat(saved.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(saved.isRetryable()).isTrue();
        assertThat(saved.getRetryCount()).isEqualTo(1);
        assertThat(saved.getNextRetryAt()).isNotNull();
        assertThat(saved.getFailureReason()).contains("Task rejected by executor");
    }

    @Test
    @DisplayName("CS-5: Caller supplying null token cannot mutate entity that contains a valid attemptToken")
    void ownershipVerification_callerSuppliesNull_whenDatabaseContainsValidToken_noStateMutation() {
        String commitSha = "cs5validtoken111111111111111111111111111";
        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(601L);
        claimed.setAttemptToken("claimed-attempt-token-12345");
        claimed.setStartedAt(Instant.now());

        when(repositoryIndexRepository.findForUpdate(42L, commitSha)).thenReturn(Optional.of(claimed));
        when(repositoryIndexRepository.findById(601L)).thenReturn(Optional.of(claimed));

        // 1. failSnapshotIfIndexing with null token
        RepositoryIndexEntity failResult = indexingService.failSnapshotIfIndexing(
            42L, commitSha, null, "Caller with null token", new RuntimeException("Error")
        );
        assertThat(failResult).isSameAs(claimed);
        assertThat(failResult.getStatus()).isEqualTo(RepositoryIndexStatus.INDEXING);
        assertThat(failResult.getFailureReason()).isNull();

        // 2. recordFailure with null token
        RepositoryIndexEntity recordResult = indexingService.recordFailure(
            601L, null, 42L, commitSha, new RuntimeException("Error")
        );
        assertThat(recordResult).isSameAs(claimed);
        assertThat(recordResult.getStatus()).isEqualTo(RepositoryIndexStatus.INDEXING);

        // Verify saveAndFlush was never invoked on repositoryIndexRepository
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
        verify(indexedTypeRepository, never()).saveAll(any());
        verify(indexedTypeDependencyRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("CS-6: Concurrent initial claims recover cleanly in a fresh transaction after DataIntegrityViolationException")
    void claimIndexingSnapshot_concurrentInitialClaims_recoversCleanlyInFreshTransaction() {
        String commitSha = "cs6initial111111111111111111111111111111";

        // Winning worker already committed an active INDEXING record
        RepositoryIndexEntity winningEntity = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        winningEntity.setId(701L);
        winningEntity.setAttemptToken("winning-worker-token");
        winningEntity.setStartedAt(Instant.now());

        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        // First findForUpdate (in executeClaimIndexingSnapshot): sees empty
        // Second findForUpdate (in resolveClaimConflict): sees winningEntity
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(winningEntity));

        // executeClaimIndexingSnapshot tries to save new entity, but hits unique constraint race
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("duplicate key value violates unique constraint uk_repo_indices_repo_commit"));

        // Worker 2 calls indexRepository
        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(701L);
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.INDEXING);
        assertThat(result.getAttemptToken()).isEqualTo("winning-worker-token");

        // Worker 2 detected actively indexing and did NOT fetch git tree or re-execute ingestion
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("CS-7: Old token cannot update record after stale reconciliation has cleared attemptToken")
    void staleReconciliation_fencesWorkerImmediately_oldTokenCannotUpdateRecord() {
        String commitSha = "cs7stalefence111111111111111111111111111";
        String oldToken = "stale-worker-token-xyz";

        // Entity after stale reconciliation: status is FAILED, attemptToken is null, retry is scheduled
        RepositoryIndexEntity reconciled = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        reconciled.setId(801L);
        reconciled.setAttemptToken(null); // Cleared by reconcileStaleIndexById
        reconciled.setRetryable(true);
        reconciled.setRetryCount(1);
        Instant scheduledRetryTime = Instant.now().plusSeconds(60);
        reconciled.setNextRetryAt(scheduledRetryTime);
        reconciled.setFailureReason("Stale indexing operation timed out");

        when(repositoryIndexRepository.findById(801L)).thenReturn(Optional.of(reconciled));
        when(repositoryIndexRepository.findForUpdate(42L, commitSha)).thenReturn(Optional.of(reconciled));

        // Old worker tries to call recordFailure with its oldToken
        RepositoryIndexEntity failResult = indexingService.recordFailure(
            801L, oldToken, 42L, commitSha, new RuntimeException("Late network timeout from old worker")
        );
        assertThat(failResult).isSameAs(reconciled);
        assertThat(failResult.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(failResult.getNextRetryAt()).isEqualTo(scheduledRetryTime);
        assertThat(failResult.getFailureReason()).isEqualTo("Stale indexing operation timed out");

        // Old worker tries to call failSnapshotIfIndexing with oldToken
        RepositoryIndexEntity snapshotFailResult = indexingService.failSnapshotIfIndexing(
            42L, commitSha, oldToken, "Late failure", new RuntimeException("Old failure")
        );
        assertThat(snapshotFailResult).isSameAs(reconciled);
        assertThat(snapshotFailResult.getNextRetryAt()).isEqualTo(scheduledRetryTime);

        // Verify database entity was never mutated by old worker
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
        verify(indexedTypeRepository, never()).deleteByIndexId(any());
    }
}
