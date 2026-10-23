package com.changescope.service;

import com.changescope.dto.AffectedMethod;
import com.changescope.dto.AffectedType;
import com.changescope.dto.AnalysisConfidence;
import com.changescope.dto.AnalysisSummaryResponse;
import com.changescope.dto.ChangedTypeDetection;
import com.changescope.dto.ConfidenceLevel;
import com.changescope.dto.FileParseError;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.MethodDependency;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestMatchEvidence;
import com.changescope.dto.TypeChangeEvidence;
import com.changescope.dto.TypeDependency;
import com.changescope.dto.VerificationPlan;
import com.changescope.dto.VerificationPriority;
import com.changescope.dto.VerificationRecommendation;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.changescope.config.AnalysisProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({PullRequestAnalysisService.class, ObjectMapper.class, AnalysisProperties.class})
class PullRequestAnalysisPersistenceTest {

    @Autowired
    private PullRequestAnalysisService service;

    @Autowired
    private PullRequestAnalysisRepository analysisRepository;

    @Autowired
    private RepositoryEntityRepository repositoryEntityRepository;

    @Autowired
    private TestEntityManager entityManager;

    private RepositoryEntity repo1;
    private RepositoryEntity repo2;

    @BeforeEach
    void setUp() {
        repo1 = repositoryEntityRepository.save(new RepositoryEntity("changescope-org", "order-service", "main", 101L));
        repo2 = repositoryEntityRepository.save(new RepositoryEntity("changescope-org", "inventory-service", "main", 102L));
        entityManager.flush();
    }

    @Test
    void lifecycle_startAndComplete_persistsCompletedStatusAndSnapshot() {
        PullRequestAnalysisEntity running = service.startAnalysis(repo1.getId(), 42);
        assertThat(running.getId()).isNotNull();
        assertThat(running.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(running.getStartedAt()).isNotNull();
        assertThat(running.getCompletedAt()).isNull();

        entityManager.flush();
        entityManager.clear();

        PullRequestMetadata pr = new PullRequestMetadata(
            "changescope-org", "order-service", 42, "Refactor payment flow",
            "base_sha_123", "head_sha_456", PullRequestState.OPEN
        );
        ImpactReport report = new ImpactReport(List.of("com.example.OrderService"), List.of(), List.of(), List.of());
        PullRequestImpactResult result = new PullRequestImpactResult(pr, report, List.of("warning-1"), List.of());

        Instant completedAt = Instant.now();
        PullRequestImpactResult completed = service.completeAnalysis(running.getId(), result, completedAt);

        assertThat(completed.analysisId()).isEqualTo(running.getId());

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity entity = analysisRepository.findById(running.getId()).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(entity.getHeadCommitSha()).isEqualTo("head_sha_456");
        assertThat(entity.getBaseCommitSha()).isEqualTo("base_sha_123");
        assertThat(entity.getCompletedAt()).isNotNull();
        assertThat(entity.getReportSnapshot()).isNotBlank();
        assertThat(entity.getFailureSummary()).isNull();
    }

    @Test
    void lifecycle_startAndFail_recordsFailureSummaryAndCompletionTime() {
        PullRequestAnalysisEntity running = service.startAnalysis(repo1.getId(), 42);
        Long analysisId = running.getId();

        entityManager.flush();
        entityManager.clear();

        Instant completedAt = Instant.now();
        GitHubRateLimitException error = new GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 429) for repos/changescope-org/order-service/pulls/42");
        service.failAnalysis(analysisId, error, completedAt);

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity failed = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failed.getCompletedAt()).isNotNull();
        assertThat(failed.getFailureSummary()).contains("rate limit exceeded");
        assertThat(failed.getReportSnapshot()).isNull();
    }

    @Test
    void sanitizeFailureSummary_stripsSensitiveTokens() {
        PullRequestAnalysisEntity running = service.startAnalysis(repo1.getId(), 42);
        Long analysisId = running.getId();

        entityManager.flush();
        entityManager.clear();

        RuntimeException sensitiveError = new RuntimeException("Auth failed: Bearer secret_access_token_12345 and token ghp_ABC1234567890XYZ and ghs_16C7e42F292c6912E7710c838347Ae178B4a with secret=my_secret_token");
        service.failAnalysis(analysisId, sensitiveError, Instant.now());

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity failed = analysisRepository.findById(analysisId).orElseThrow();
        assertThat(failed.getFailureSummary()).doesNotContain("secret_access_token_12345");
        assertThat(failed.getFailureSummary()).doesNotContain("ghp_ABC1234567890XYZ");
        assertThat(failed.getFailureSummary()).doesNotContain("16C7e42F292c6912E7710c838347Ae178B4a");
        assertThat(failed.getFailureSummary()).doesNotContain("my_secret_token");
        assertThat(failed.getFailureSummary()).contains("***");
    }

    @Test
    void deterministicHistoryOrdering_identicalTimestampsTieBreakOnId() {
        Instant fixedTimestamp = Instant.parse("2026-10-17T12:00:00Z");

        // Save 4 analyses with the identical startedAt timestamp
        createAndSaveAnalysisWithTimestamp(repo1, 10, "sha1", fixedTimestamp); // id: 1
        createAndSaveAnalysisWithTimestamp(repo1, 20, "sha2", fixedTimestamp); // id: 2
        createAndSaveAnalysisWithTimestamp(repo1, 30, "sha3", fixedTimestamp); // id: 3
        createAndSaveAnalysisWithTimestamp(repo1, 40, "sha4", fixedTimestamp); // id: 4

        entityManager.flush();
        entityManager.clear();

        // Paged retrieval
        Page<AnalysisSummaryResponse> page = service.getHistoricalAnalyses(repo1.getId(), 0, 10);
        assertThat(page.getContent()).hasSize(4);
        assertThat(page.getContent().get(0).pullNumber()).isEqualTo(40);
        assertThat(page.getContent().get(1).pullNumber()).isEqualTo(30);
        assertThat(page.getContent().get(2).pullNumber()).isEqualTo(20);
        assertThat(page.getContent().get(3).pullNumber()).isEqualTo(10);

        // Unpaged retrieval
        List<AnalysisSummaryResponse> unpaged = service.getHistoricalAnalyses(repo1.getId());
        assertThat(unpaged).hasSize(4);
        assertThat(unpaged.get(0).pullNumber()).isEqualTo(40);
        assertThat(unpaged.get(1).pullNumber()).isEqualTo(30);
        assertThat(unpaged.get(2).pullNumber()).isEqualTo(20);
        assertThat(unpaged.get(3).pullNumber()).isEqualTo(10);
    }

    @Test
    void getHistoricalAnalyses_paginationBounds() {
        Instant baseTime = Instant.now().minus(1, ChronoUnit.HOURS);
        for (int i = 1; i <= 5; i++) {
            createAndSaveAnalysisWithTimestamp(repo1, i, "sha" + i, baseTime.plus(i, ChronoUnit.MINUTES));
        }
        entityManager.flush();
        entityManager.clear();

        // Page 0, size 2 (items 5 and 4)
        Page<AnalysisSummaryResponse> page0 = service.getHistoricalAnalyses(repo1.getId(), 0, 2);
        assertThat(page0.getContent()).hasSize(2);
        assertThat(page0.getContent().get(0).pullNumber()).isEqualTo(5);
        assertThat(page0.getContent().get(1).pullNumber()).isEqualTo(4);
        assertThat(page0.getTotalElements()).isEqualTo(5);
        assertThat(page0.getTotalPages()).isEqualTo(3);

        // Page 1, size 2 (items 3 and 2)
        Page<AnalysisSummaryResponse> page1 = service.getHistoricalAnalyses(repo1.getId(), 1, 2);
        assertThat(page1.getContent()).hasSize(2);
        assertThat(page1.getContent().get(0).pullNumber()).isEqualTo(3);
        assertThat(page1.getContent().get(1).pullNumber()).isEqualTo(2);

        // Page 2, size 2 (item 1)
        Page<AnalysisSummaryResponse> page2 = service.getHistoricalAnalyses(repo1.getId(), 2, 2);
        assertThat(page2.getContent()).hasSize(1);
        assertThat(page2.getContent().get(0).pullNumber()).isEqualTo(1);

        // Max page size clamp test: requesting 200 items should clamp to MAX_PAGE_SIZE (100)
        Page<AnalysisSummaryResponse> clampedPage = service.getHistoricalAnalyses(repo1.getId(), 0, 200);
        assertThat(clampedPage.getSize()).isEqualTo(PullRequestAnalysisService.MAX_PAGE_SIZE);

        // Invalid pagination arguments
        assertThatThrownBy(() -> service.getHistoricalAnalyses(repo1.getId(), -1, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Page index must not be negative");

        assertThatThrownBy(() -> service.getHistoricalAnalyses(repo1.getId(), 0, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Page size must be greater than zero");
    }

    @Test
    void getAnalysisSnapshot_snapshotRoundTripping() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "changescope-org", "order-service", 99, "Complex snapshot test",
            "b_sha_789", "h_sha_987", PullRequestState.OPEN
        );

        ImpactReport report = new ImpactReport(
            List.of("com.changescope.Order"),
            List.of(new AffectedType("com.changescope.Invoice", 1)),
            List.of(new TypeDependency("com.changescope.Invoice", "com.changescope.Order", RelationshipKind.FIELD, "src/Invoice.java")),
            List.of(new FileParseError("Broken.java", "Syntax error")),
            List.of("com.changescope.Order#pay()"),
            List.of(new AffectedMethod("com.changescope.Invoice#print()", "com.changescope.Invoice", 1)),
            List.of(new MethodDependency("com.changescope.Invoice#print()", "com.changescope.Invoice", "com.changescope.Order#pay()", "com.changescope.Order", "src/Invoice.java")),
            List.of(),
            List.of(new TestImpactCandidate("OrderTest.java", "OrderTest", "testPay", TestMatchEvidence.METHOD_INVOCATION, "Calls Order#pay", "com.changescope.Order#pay()")),
            new VerificationPlan(
                new AnalysisConfidence(ConfidenceLevel.HIGH, 95, "HIGH", List.of("Clean AST parse"), List.of()),
                List.of(new VerificationRecommendation(VerificationPriority.HIGH, "TEST", "com.changescope.Order", "Run tests", "Order was modified"))
            )
        );

        ChangedTypeDetection detection = new ChangedTypeDetection(
            "com.changescope.Order",
            "src/main/java/com/changescope/Order.java",
            TypeChangeEvidence.PATCH_LINE_MATCH,
            "Lines 10-15 modified"
        );
        List<String> warnings = List.of("Warning: 1 file had no diff");

        PullRequestImpactResult originalResult = new PullRequestImpactResult(pr, report, warnings, List.of(detection));

        PullRequestImpactResult saved = service.saveCompletedAnalysis(repo1.getId(), originalResult, Instant.now(), Instant.now());
        Long analysisId = saved.analysisId();

        entityManager.flush();
        entityManager.clear();

        PullRequestImpactResult roundTripped = service.getAnalysisSnapshot(repo1.getId(), analysisId);

        assertThat(roundTripped.analysisId()).isEqualTo(analysisId);
        assertThat(roundTripped.pullRequest().owner()).isEqualTo("changescope-org");
        assertThat(roundTripped.pullRequest().repo()).isEqualTo("order-service");
        assertThat(roundTripped.pullRequest().number()).isEqualTo(99);
        assertThat(roundTripped.pullRequest().title()).isEqualTo("Complex snapshot test");
        assertThat(roundTripped.pullRequest().state()).isEqualTo(PullRequestState.OPEN);

        assertThat(roundTripped.impactReport().changedTypes()).containsExactly("com.changescope.Order");
        assertThat(roundTripped.impactReport().affectedTypes()).hasSize(1);
        assertThat(roundTripped.impactReport().affectedTypes().get(0).typeName()).isEqualTo("com.changescope.Invoice");
        assertThat(roundTripped.impactReport().dependencies()).hasSize(1);
        assertThat(roundTripped.impactReport().parseErrors()).hasSize(1);
        assertThat(roundTripped.impactReport().changedMethods()).containsExactly("com.changescope.Order#pay()");
        assertThat(roundTripped.impactReport().affectedMethods()).hasSize(1);
        assertThat(roundTripped.impactReport().methodDependencies()).hasSize(1);
        assertThat(roundTripped.impactReport().candidateTests()).hasSize(1);
        assertThat(roundTripped.impactReport().verificationPlan()).isNotNull();
        assertThat(roundTripped.impactReport().verificationPlan().recommendations()).hasSize(1);
        assertThat(roundTripped.impactReport().verificationPlan().confidence().level()).isEqualTo(ConfidenceLevel.HIGH);

        assertThat(roundTripped.extractionWarnings()).containsExactly("Warning: 1 file had no diff");
        assertThat(roundTripped.changedTypeDetections()).hasSize(1);
        assertThat(roundTripped.changedTypeDetections().get(0).fullyQualifiedName()).isEqualTo("com.changescope.Order");
        assertThat(roundTripped.changedTypeDetections().get(0).evidence()).isEqualTo(TypeChangeEvidence.PATCH_LINE_MATCH);
    }

    @Test
    void getAnalysisSnapshot_repositoryOwnershipCheck() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "changescope-org", "order-service", 55, "Test ownership",
            "base", "head", PullRequestState.OPEN
        );
        ImpactReport report = new ImpactReport(List.of("com.example.Order"), List.of(), List.of(), List.of());
        PullRequestImpactResult result = new PullRequestImpactResult(pr, report, List.of(), List.of());

        PullRequestImpactResult savedForRepo1 = service.saveCompletedAnalysis(repo1.getId(), result, Instant.now(), Instant.now());
        Long analysisId = savedForRepo1.analysisId();

        entityManager.flush();
        entityManager.clear();

        // Access via correct repository succeeds
        PullRequestImpactResult fetched = service.getAnalysisSnapshot(repo1.getId(), analysisId);
        assertThat(fetched).isNotNull();

        // Access via different registered repository fails ownership check
        assertThatThrownBy(() -> service.getAnalysisSnapshot(repo2.getId(), analysisId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("does not belong to repository with id " + repo2.getId());

        // Access via non-existent repository fails
        assertThatThrownBy(() -> service.getAnalysisSnapshot(9999L, analysisId))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found with id: 9999");

        // Access non-existent analysis id fails
        assertThatThrownBy(() -> service.getAnalysisSnapshot(repo1.getId(), 8888L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Analysis not found with id: 8888");
    }

    @Test
    void reconcileStaleAnalyses_staleRunningBecomesFailedWithCompletionAndSummary() {
        Instant startTime = Instant.now().minus(45, ChronoUnit.MINUTES);
        PullRequestAnalysisEntity stale = createAndSaveAnalysisWithStatus(
            repo1, 101, AnalysisStatus.RUNNING, startTime, null, null, null
        );
        entityManager.flush();
        entityManager.clear();

        Instant reconcileTime = Instant.now();
        int reconciledCount = service.reconcileStaleAnalyses(Duration.ofMinutes(30), reconcileTime);

        assertThat(reconciledCount).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity updated = analysisRepository.findById(stale.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getCompletedAt().toEpochMilli()).isEqualTo(reconcileTime.toEpochMilli());
        assertThat(updated.getFailureSummary()).isEqualTo(PullRequestAnalysisService.DEFAULT_STALE_FAILURE_SUMMARY);
        assertThat(updated.getStartedAt().toEpochMilli()).isEqualTo(startTime.toEpochMilli());
    }

    @Test
    void reconcileStaleAnalyses_recentRunningRemainsUnchanged() {
        Instant recentStartTime = Instant.now().minus(10, ChronoUnit.MINUTES);
        PullRequestAnalysisEntity recent = createAndSaveAnalysisWithStatus(
            repo1, 102, AnalysisStatus.RUNNING, recentStartTime, null, null, null
        );
        entityManager.flush();
        entityManager.clear();

        int reconciledCount = service.reconcileStaleAnalyses(Duration.ofMinutes(30));

        assertThat(reconciledCount).isEqualTo(0);

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity unchanged = analysisRepository.findById(recent.getId()).orElseThrow();
        assertThat(unchanged.getStatus()).isEqualTo(AnalysisStatus.RUNNING);
        assertThat(unchanged.getCompletedAt()).isNull();
        assertThat(unchanged.getFailureSummary()).isNull();
    }

    @Test
    void reconcileStaleAnalyses_completedAndFailedRemainUnchanged() {
        Instant oldTime = Instant.now().minus(60, ChronoUnit.MINUTES);
        PullRequestAnalysisEntity completed = createAndSaveAnalysisWithStatus(
            repo1, 103, AnalysisStatus.COMPLETED, oldTime, oldTime.plusSeconds(30), null, "{\"mock\":\"report\"}"
        );
        PullRequestAnalysisEntity failed = createAndSaveAnalysisWithStatus(
            repo1, 104, AnalysisStatus.FAILED, oldTime, oldTime.plusSeconds(10), "Explicit failure reason", null
        );
        entityManager.flush();
        entityManager.clear();

        int reconciledCount = service.reconcileStaleAnalyses(Duration.ofMinutes(30));

        assertThat(reconciledCount).isEqualTo(0);

        entityManager.flush();
        entityManager.clear();

        PullRequestAnalysisEntity completedCheck = analysisRepository.findById(completed.getId()).orElseThrow();
        assertThat(completedCheck.getStatus()).isEqualTo(AnalysisStatus.COMPLETED);
        assertThat(completedCheck.getReportSnapshot()).isEqualTo("{\"mock\":\"report\"}");
        assertThat(completedCheck.getFailureSummary()).isNull();

        PullRequestAnalysisEntity failedCheck = analysisRepository.findById(failed.getId()).orElseThrow();
        assertThat(failedCheck.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(failedCheck.getFailureSummary()).isEqualTo("Explicit failure reason");
    }

    @Test
    void reconcileStaleAnalyses_isIdempotent() {
        Instant oldTime = Instant.now().minus(50, ChronoUnit.MINUTES);
        PullRequestAnalysisEntity stale = createAndSaveAnalysisWithStatus(
            repo1, 105, AnalysisStatus.RUNNING, oldTime, null, null, null
        );
        entityManager.flush();
        entityManager.clear();

        // First execution reconciles 1 stale record
        int firstRun = service.reconcileStaleAnalyses(Duration.ofMinutes(30));
        assertThat(firstRun).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();

        // Subsequent runs on the same dataset safely reconcile 0 records
        int secondRun = service.reconcileStaleAnalyses(Duration.ofMinutes(30));
        assertThat(secondRun).isEqualTo(0);

        int thirdRun = service.reconcileStaleAnalyses(Duration.ofMinutes(30));
        assertThat(thirdRun).isEqualTo(0);

        PullRequestAnalysisEntity finalCheck = analysisRepository.findById(stale.getId()).orElseThrow();
        assertThat(finalCheck.getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(finalCheck.getFailureSummary()).isEqualTo(PullRequestAnalysisService.DEFAULT_STALE_FAILURE_SUMMARY);
    }

    @Test
    void reconcileStaleAnalyses_rejectsInvalidTimeout() {
        assertThatThrownBy(() -> service.reconcileStaleAnalyses(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> service.reconcileStaleAnalyses(Duration.ofMinutes(-5)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> service.reconcileStaleAnalyses(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void reconcileStaleAnalyses_usesConfiguredDefaultProperty() {
        Instant oldTime = Instant.now().minus(40, ChronoUnit.MINUTES);
        Instant recentTime = Instant.now().minus(5, ChronoUnit.MINUTES);

        PullRequestAnalysisEntity stale = createAndSaveAnalysisWithStatus(
            repo1, 106, AnalysisStatus.RUNNING, oldTime, null, null, null
        );
        PullRequestAnalysisEntity recent = createAndSaveAnalysisWithStatus(
            repo1, 107, AnalysisStatus.RUNNING, recentTime, null, null, null
        );
        entityManager.flush();
        entityManager.clear();

        // Uses default 30-minute property from AnalysisProperties
        int reconciledCount = service.reconcileStaleAnalyses();
        assertThat(reconciledCount).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();

        assertThat(analysisRepository.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.FAILED);
        assertThat(analysisRepository.findById(recent.getId()).orElseThrow().getStatus()).isEqualTo(AnalysisStatus.RUNNING);
    }

    @Test
    void analysisProperties_validatesPositiveTimeout() {
        AnalysisProperties properties = new AnalysisProperties();
        assertThat(properties.getStaleTimeout()).isEqualTo(Duration.ofMinutes(30));

        properties.setStaleTimeout(Duration.ofMinutes(15));
        assertThat(properties.getStaleTimeout()).isEqualTo(Duration.ofMinutes(15));

        assertThatThrownBy(() -> properties.setStaleTimeout(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> properties.setStaleTimeout(Duration.ofSeconds(-30)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> properties.setStaleTimeout(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void repository_findByStatusAndStartedAtBefore_findsMatchingStaleRecords() {
        Instant cutoff = Instant.now().minus(30, ChronoUnit.MINUTES);
        PullRequestAnalysisEntity stale = createAndSaveAnalysisWithStatus(
            repo1, 108, AnalysisStatus.RUNNING, cutoff.minusSeconds(60), null, null, null
        );
        createAndSaveAnalysisWithStatus(
            repo1, 109, AnalysisStatus.RUNNING, cutoff.plusSeconds(60), null, null, null
        );
        createAndSaveAnalysisWithStatus(
            repo1, 110, AnalysisStatus.COMPLETED, cutoff.minusSeconds(60), cutoff.minusSeconds(30), null, "{}"
        );
        entityManager.flush();
        entityManager.clear();

        List<PullRequestAnalysisEntity> staleList = analysisRepository.findByStatusAndStartedAtBefore(
            AnalysisStatus.RUNNING, cutoff
        );
        assertThat(staleList).extracting(PullRequestAnalysisEntity::getId).containsExactly(stale.getId());
    }

    private PullRequestAnalysisEntity createAndSaveAnalysisWithStatus(
        RepositoryEntity repo,
        int prNumber,
        AnalysisStatus status,
        Instant startedAt,
        Instant completedAt,
        String failureSummary,
        String reportSnapshot
    ) {
        PullRequestAnalysisEntity entity = new PullRequestAnalysisEntity(
            repo,
            prNumber,
            "head_sha_" + prNumber,
            "base_sha_" + prNumber,
            status,
            failureSummary,
            startedAt,
            completedAt,
            reportSnapshot
        );
        return analysisRepository.save(entity);
    }

    private void createAndSaveAnalysisWithTimestamp(RepositoryEntity repo, int prNumber, String headSha, Instant startedAt) {
        PullRequestAnalysisEntity entity = new PullRequestAnalysisEntity(
            repo,
            prNumber,
            headSha,
            "base_" + headSha,
            AnalysisStatus.COMPLETED,
            startedAt,
            startedAt.plus(2, ChronoUnit.SECONDS),
            "{}"
        );
        analysisRepository.save(entity);
    }
}
