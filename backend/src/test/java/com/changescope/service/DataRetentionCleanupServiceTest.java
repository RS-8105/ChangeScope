package com.changescope.service;

import com.changescope.dto.RetentionCleanupResult;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.WebhookDeliveryEntity;
import com.changescope.model.WebhookDeliveryStatus;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(DataRetentionCleanupService.class)
class DataRetentionCleanupServiceTest {

    @Autowired
    private DataRetentionCleanupService cleanupService;

    @Autowired
    private PullRequestAnalysisRepository analysisRepository;

    @Autowired
    private WebhookDeliveryRepository webhookRepository;

    @Autowired
    private RepositoryEntityRepository repositoryEntityRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RepositoryEntity testRepo;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-31T12:00:00Z");

        testRepo = new RepositoryEntity("changescope", "sample-repo", "main", 12345L, 67890L);
        testRepo = repositoryEntityRepository.saveAndFlush(testRepo);
    }

    private PullRequestAnalysisEntity createAnalysis(
        int pullNumber,
        AnalysisStatus status,
        Instant startedAt,
        Instant completedAt
    ) {
        PullRequestAnalysisEntity entity = new PullRequestAnalysisEntity(
            testRepo,
            pullNumber,
            "head-" + pullNumber,
            "base-" + pullNumber,
            status,
            (status == AnalysisStatus.FAILED ? "Test failure" : null),
            startedAt,
            completedAt,
            (status == AnalysisStatus.COMPLETED ? "{\"status\":\"ok\"}" : null)
        );
        return analysisRepository.saveAndFlush(entity);
    }

    private WebhookDeliveryEntity createWebhookDelivery(
        String deliveryId,
        Instant receivedAt,
        WebhookDeliveryStatus status
    ) {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity(
            deliveryId,
            "pull_request",
            receivedAt,
            status,
            (status == WebhookDeliveryStatus.FAILED ? "Webhook delivery failure" : null),
            false
        );
        return webhookRepository.saveAndFlush(entity);
    }

    @Nested
    @DisplayName("1. Retention Boundaries")
    class RetentionBoundariesTests {

        @Test
        @DisplayName("Completed analyses: delete strictly after 14 days from completedAt")
        void completedAnalyses_retentionBoundary() {
            // Exactly at cutoff (14 days ago) -> retained (retention is "after 14 days")
            PullRequestAnalysisEntity exactly14Days = createAnalysis(
                101,
                AnalysisStatus.COMPLETED,
                now.minus(15, ChronoUnit.DAYS),
                now.minus(14, ChronoUnit.DAYS)
            );

            // Within retention window (13 days ago) -> retained
            PullRequestAnalysisEntity younger13Days = createAnalysis(
                102,
                AnalysisStatus.COMPLETED,
                now.minus(14, ChronoUnit.DAYS),
                now.minus(13, ChronoUnit.DAYS)
            );

            // Expired (14 days + 1 second ago) -> deleted
            PullRequestAnalysisEntity expired14Days1Sec = createAnalysis(
                103,
                AnalysisStatus.COMPLETED,
                now.minus(15, ChronoUnit.DAYS),
                now.minus(14, ChronoUnit.DAYS).minusSeconds(1)
            );

            // Expired (20 days ago) -> deleted
            PullRequestAnalysisEntity expired20Days = createAnalysis(
                104,
                AnalysisStatus.COMPLETED,
                now.minus(21, ChronoUnit.DAYS),
                now.minus(20, ChronoUnit.DAYS)
            );

            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now);

            assertThat(result.deletedCompletedAnalyses()).isEqualTo(2);
            assertThat(result.totalDeleted()).isEqualTo(2);

            assertThat(analysisRepository.findById(exactly14Days.getId())).isPresent();
            assertThat(analysisRepository.findById(younger13Days.getId())).isPresent();
            assertThat(analysisRepository.findById(expired14Days1Sec.getId())).isEmpty();
            assertThat(analysisRepository.findById(expired20Days.getId())).isEmpty();
        }

        @Test
        @DisplayName("Failed analyses: delete strictly after 7 days from completedAt")
        void failedAnalyses_retentionBoundary() {
            // Exactly at cutoff (7 days ago) -> retained
            PullRequestAnalysisEntity exactly7Days = createAnalysis(
                201,
                AnalysisStatus.FAILED,
                now.minus(8, ChronoUnit.DAYS),
                now.minus(7, ChronoUnit.DAYS)
            );

            // Within retention window (6 days ago) -> retained
            PullRequestAnalysisEntity younger6Days = createAnalysis(
                202,
                AnalysisStatus.FAILED,
                now.minus(7, ChronoUnit.DAYS),
                now.minus(6, ChronoUnit.DAYS)
            );

            // Expired (7 days + 1 second ago) -> deleted
            PullRequestAnalysisEntity expired7Days1Sec = createAnalysis(
                203,
                AnalysisStatus.FAILED,
                now.minus(8, ChronoUnit.DAYS),
                now.minus(7, ChronoUnit.DAYS).minusSeconds(1)
            );

            // Expired (10 days ago) -> deleted
            PullRequestAnalysisEntity expired10Days = createAnalysis(
                204,
                AnalysisStatus.FAILED,
                now.minus(11, ChronoUnit.DAYS),
                now.minus(10, ChronoUnit.DAYS)
            );

            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now);

            assertThat(result.deletedFailedAnalyses()).isEqualTo(2);
            assertThat(result.totalDeleted()).isEqualTo(2);

            assertThat(analysisRepository.findById(exactly7Days.getId())).isPresent();
            assertThat(analysisRepository.findById(younger6Days.getId())).isPresent();
            assertThat(analysisRepository.findById(expired7Days1Sec.getId())).isEmpty();
            assertThat(analysisRepository.findById(expired10Days.getId())).isEmpty();
        }

        @Test
        @DisplayName("Webhook deliveries: delete strictly after 7 days from receivedAt")
        void webhookDeliveries_retentionBoundary() {
            // Exactly at cutoff (7 days ago) -> retained
            WebhookDeliveryEntity exactly7Days = createWebhookDelivery(
                "wh-boundary-7d",
                now.minus(7, ChronoUnit.DAYS),
                WebhookDeliveryStatus.PROCESSED
            );

            // Within retention window (6 days ago) -> retained
            WebhookDeliveryEntity younger6Days = createWebhookDelivery(
                "wh-boundary-6d",
                now.minus(6, ChronoUnit.DAYS),
                WebhookDeliveryStatus.PROCESSED
            );

            // Expired (7 days + 1 second ago) -> deleted
            WebhookDeliveryEntity expired7Days1Sec = createWebhookDelivery(
                "wh-boundary-7d-1s",
                now.minus(7, ChronoUnit.DAYS).minusSeconds(1),
                WebhookDeliveryStatus.PROCESSED
            );

            // Expired (12 days ago) -> deleted
            WebhookDeliveryEntity expired12Days = createWebhookDelivery(
                "wh-boundary-12d",
                now.minus(12, ChronoUnit.DAYS),
                WebhookDeliveryStatus.FAILED
            );

            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now);

            assertThat(result.deletedWebhookDeliveries()).isEqualTo(2);
            assertThat(result.totalDeleted()).isEqualTo(2);

            assertThat(webhookRepository.findById(exactly7Days.getId())).isPresent();
            assertThat(webhookRepository.findById(younger6Days.getId())).isPresent();
            assertThat(webhookRepository.findById(expired7Days1Sec.getId())).isEmpty();
            assertThat(webhookRepository.findById(expired12Days.getId())).isEmpty();
        }
    }

    @Nested
    @DisplayName("2. Eligible Terminal Statuses vs Non-Terminal Statuses")
    class EligibleStatusesTests {

        @Test
        @DisplayName("Never delete active or non-terminal analyses (RUNNING, PENDING, IN_PROGRESS) regardless of age")
        void activeAnalyses_neverDeleted() {
            // Active analyses started long ago (e.g. 30 days, 60 days, 100 days)
            PullRequestAnalysisEntity runningOld = createAnalysis(
                301,
                AnalysisStatus.RUNNING,
                now.minus(30, ChronoUnit.DAYS),
                null
            );
            PullRequestAnalysisEntity pendingOld = createAnalysis(
                302,
                AnalysisStatus.PENDING,
                now.minus(60, ChronoUnit.DAYS),
                null
            );
            PullRequestAnalysisEntity inProgressOld = createAnalysis(
                303,
                AnalysisStatus.IN_PROGRESS,
                now.minus(90, ChronoUnit.DAYS),
                null
            );

            // Terminal expired analyses that SHOULD be deleted
            PullRequestAnalysisEntity completedOld = createAnalysis(
                304,
                AnalysisStatus.COMPLETED,
                now.minus(20, ChronoUnit.DAYS),
                now.minus(18, ChronoUnit.DAYS)
            );
            PullRequestAnalysisEntity failedOld = createAnalysis(
                305,
                AnalysisStatus.FAILED,
                now.minus(12, ChronoUnit.DAYS),
                now.minus(10, ChronoUnit.DAYS)
            );

            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now);

            assertThat(result.deletedCompletedAnalyses()).isEqualTo(1);
            assertThat(result.deletedFailedAnalyses()).isEqualTo(1);

            // Active/non-terminal records MUST be preserved untouched
            assertThat(analysisRepository.findById(runningOld.getId())).isPresent();
            assertThat(analysisRepository.findById(pendingOld.getId())).isPresent();
            assertThat(analysisRepository.findById(inProgressOld.getId())).isPresent();

            // Terminal expired records MUST be deleted
            assertThat(analysisRepository.findById(completedOld.getId())).isEmpty();
            assertThat(analysisRepository.findById(failedOld.getId())).isEmpty();
        }

        @Test
        @DisplayName("Failed analyses with null completedAt fallback to startedAt/createdAt")
        void failedAnalyses_nullCompletedAt_usesFallbackTimestamp() {
            // FAILED analysis with null completedAt, started 10 days ago (> 7 days) -> deleted
            PullRequestAnalysisEntity failedNoCompletedAt = createAnalysis(
                401,
                AnalysisStatus.FAILED,
                now.minus(10, ChronoUnit.DAYS),
                null
            );

            // FAILED analysis with null completedAt, started 4 days ago (< 7 days) -> kept
            PullRequestAnalysisEntity failedRecentNoCompletedAt = createAnalysis(
                402,
                AnalysisStatus.FAILED,
                now.minus(4, ChronoUnit.DAYS),
                null
            );

            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now);

            assertThat(result.deletedFailedAnalyses()).isEqualTo(1);
            assertThat(analysisRepository.findById(failedNoCompletedAt.getId())).isEmpty();
            assertThat(analysisRepository.findById(failedRecentNoCompletedAt.getId())).isPresent();
        }
    }

    @Nested
    @DisplayName("3. Foreign-Key Relationships & Entity Preservation")
    class ForeignKeyPreservationTests {

        @Test
        @DisplayName("Deleting expired analyses preserves parent RepositoryEntity and non-expired sibling analyses")
        void deletingExpiredAnalyses_preservesRepositoryAndSiblings() {
            Long repoId = testRepo.getId();

            PullRequestAnalysisEntity expiredAnalysis = createAnalysis(
                501,
                AnalysisStatus.COMPLETED,
                now.minus(30, ChronoUnit.DAYS),
                now.minus(25, ChronoUnit.DAYS)
            );

            PullRequestAnalysisEntity activeAnalysis = createAnalysis(
                502,
                AnalysisStatus.RUNNING,
                now.minus(1, ChronoUnit.HOURS),
                null
            );

            PullRequestAnalysisEntity recentCompletedAnalysis = createAnalysis(
                503,
                AnalysisStatus.COMPLETED,
                now.minus(2, ChronoUnit.DAYS),
                now.minus(2, ChronoUnit.DAYS)
            );

            cleanupService.cleanupExpiredRecords(now);

            // Repository entity must remain intact
            Optional<RepositoryEntity> repository = repositoryEntityRepository.findById(repoId);
            assertThat(repository).isPresent();
            assertThat(repository.get().getOwner()).isEqualTo("changescope");
            assertThat(repository.get().getName()).isEqualTo("sample-repo");

            // Expired analysis deleted
            assertThat(analysisRepository.findById(expiredAnalysis.getId())).isEmpty();

            // Sibling analyses belonging to the repository remain intact and linked
            Optional<PullRequestAnalysisEntity> runningFound = analysisRepository.findById(activeAnalysis.getId());
            assertThat(runningFound).isPresent();
            assertThat(runningFound.get().getRepository().getId()).isEqualTo(repoId);

            Optional<PullRequestAnalysisEntity> recentFound = analysisRepository.findById(recentCompletedAnalysis.getId());
            assertThat(recentFound).isPresent();
            assertThat(recentFound.get().getRepository().getId()).isEqualTo(repoId);
        }
    }

    @Nested
    @DisplayName("4. Batched Deletion Across Multiple Pages")
    class BatchedDeletionTests {

        @Test
        @DisplayName("Bounded batched deletion safely processes large record sets across multiple batches")
        void batchedDeletion_processesAllBatchesCleanly() {
            int totalExpiredCompleted = 25;
            for (int i = 0; i < totalExpiredCompleted; i++) {
                createAnalysis(
                    600 + i,
                    AnalysisStatus.COMPLETED,
                    now.minus(20, ChronoUnit.DAYS),
                    now.minus(18, ChronoUnit.DAYS)
                );
            }

            int totalExpiredWebhooks = 15;
            for (int i = 0; i < totalExpiredWebhooks; i++) {
                createWebhookDelivery(
                    "batch-wh-" + i,
                    now.minus(10, ChronoUnit.DAYS),
                    WebhookDeliveryStatus.PROCESSED
                );
            }

            // Run with small batch size of 10 to force multiple batch iterations
            RetentionCleanupResult result = cleanupService.cleanupExpiredRecords(now, 10);

            assertThat(result.deletedCompletedAnalyses()).isEqualTo(25);
            assertThat(result.deletedWebhookDeliveries()).isEqualTo(15);
            assertThat(result.totalDeleted()).isEqualTo(40);

            // Verify all expired records were deleted
            assertThat(analysisRepository.count()).isZero();
            assertThat(webhookRepository.count()).isZero();
        }
    }

    @Nested
    @DisplayName("5. Idempotency & Repeated Executions")
    class IdempotencyTests {

        @Test
        @DisplayName("Repeated cleanup executions are idempotent and report zero deletions on subsequent runs")
        void repeatedExecutions_areIdempotent() {
            createAnalysis(
                701,
                AnalysisStatus.COMPLETED,
                now.minus(20, ChronoUnit.DAYS),
                now.minus(18, ChronoUnit.DAYS)
            );
            createAnalysis(
                702,
                AnalysisStatus.FAILED,
                now.minus(15, ChronoUnit.DAYS),
                now.minus(12, ChronoUnit.DAYS)
            );
            createWebhookDelivery(
                "wh-idempotent-1",
                now.minus(10, ChronoUnit.DAYS),
                WebhookDeliveryStatus.PROCESSED
            );

            // First execution deletes the 3 expired records
            RetentionCleanupResult firstRun = cleanupService.cleanupExpiredRecords(now);
            assertThat(firstRun.totalDeleted()).isEqualTo(3);
            assertThat(firstRun.skipped()).isFalse();

            // Second execution finds 0 expired records and performs no-op cleanly
            RetentionCleanupResult secondRun = cleanupService.cleanupExpiredRecords(now);
            assertThat(secondRun.totalDeleted()).isZero();
            assertThat(secondRun.deletedCompletedAnalyses()).isZero();
            assertThat(secondRun.deletedFailedAnalyses()).isZero();
            assertThat(secondRun.deletedWebhookDeliveries()).isZero();
            assertThat(secondRun.skipped()).isFalse();

            // Third execution continues to succeed with zero deletions
            RetentionCleanupResult thirdRun = cleanupService.cleanupExpiredRecords(now);
            assertThat(thirdRun.totalDeleted()).isZero();
        }
    }

    @Nested
    @DisplayName("6. Scheduled Configuration Verification")
    class ScheduledConfigurationTests {

        @Test
        @DisplayName("Scheduled cleanup method is annotated with @Scheduled for 24-hour execution")
        void scheduleAnnotation_configuredFor24HourExecution() throws NoSuchMethodException {
            Method scheduledMethod = DataRetentionCleanupService.class.getMethod("scheduleCleanup");

            assertThat(scheduledMethod.isAnnotationPresent(Scheduled.class)).isTrue();

            Scheduled scheduled = scheduledMethod.getAnnotation(Scheduled.class);
            assertThat(scheduled.cron()).isEqualTo("${retention.cleanup.cron:0 0 3 * * *}");
        }

        @Test
        @DisplayName("Fixed retention constants match requirements and are not configurable")
        void fixedRetentionConstants_matchPolicy() {
            assertThat(DataRetentionCleanupService.COMPLETED_ANALYSIS_RETENTION).isEqualTo(Duration.ofDays(14));
            assertThat(DataRetentionCleanupService.FAILED_ANALYSIS_RETENTION).isEqualTo(Duration.ofDays(7));
            assertThat(DataRetentionCleanupService.WEBHOOK_DELIVERY_RETENTION).isEqualTo(Duration.ofDays(7));
        }
    }
}
