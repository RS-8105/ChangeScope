package com.changescope.service;

import com.changescope.dto.RetentionCleanupResult;
import com.changescope.model.AnalysisStatus;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.WebhookDeliveryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Service enforcing fixed data retention and cleanup policies across terminal
 * pull-request analyses and webhook delivery records.
 *
 * Retention policy (fixed, not user-configurable):
 * - COMPLETED pull request analyses: delete after 14 days from completedAt.
 * - FAILED pull request analyses: delete after 7 days from completedAt (or failure/start timestamp).
 * - Webhook delivery records: delete after 7 days from creation/receivedAt timestamp.
 * - Repository dependency indexes: never automatically expire.
 * - Connected repository records: never automatically expire.
 *
 * Active/in-flight analyses (RUNNING, PENDING, IN_PROGRESS) are strictly excluded from cleanup.
 * Executions are bounded into batched transactions and guarded against concurrent overlap.
 */
@Service
public class DataRetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionCleanupService.class);

    public static final Duration COMPLETED_ANALYSIS_RETENTION = Duration.ofDays(14);
    public static final Duration FAILED_ANALYSIS_RETENTION = Duration.ofDays(7);
    public static final Duration WEBHOOK_DELIVERY_RETENTION = Duration.ofDays(7);
    public static final int DEFAULT_BATCH_SIZE = 100;

    private final PullRequestAnalysisRepository pullRequestAnalysisRepository;
    private final WebhookDeliveryRepository webhookDeliveryRepository;
    private final TransactionTemplate transactionTemplate;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Autowired
    public DataRetentionCleanupService(
        PullRequestAnalysisRepository pullRequestAnalysisRepository,
        WebhookDeliveryRepository webhookDeliveryRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.pullRequestAnalysisRepository = pullRequestAnalysisRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    public DataRetentionCleanupService(
        PullRequestAnalysisRepository pullRequestAnalysisRepository,
        WebhookDeliveryRepository webhookDeliveryRepository,
        TransactionTemplate transactionTemplate
    ) {
        this.pullRequestAnalysisRepository = pullRequestAnalysisRepository;
        this.webhookDeliveryRepository = webhookDeliveryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Scheduled entry point running once every 24 hours.
     * Uses a cron expression default of 03:00 AM daily, preventing execution during application startup.
     *
     * @return summary of the cleanup execution
     */
    @Scheduled(cron = "${retention.cleanup.cron:0 0 3 * * *}")
    public RetentionCleanupResult scheduleCleanup() {
        return cleanupExpiredRecords();
    }

    /**
     * Executes data retention cleanup as of the current instant with the default batch size.
     *
     * @return summary of deleted records
     */
    public RetentionCleanupResult cleanupExpiredRecords() {
        return cleanupExpiredRecords(Instant.now(), DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes data retention cleanup as of the specified reference time with the default batch size.
     *
     * @param referenceTime reference point in time for retention window calculation
     * @return summary of deleted records
     */
    public RetentionCleanupResult cleanupExpiredRecords(Instant referenceTime) {
        return cleanupExpiredRecords(referenceTime, DEFAULT_BATCH_SIZE);
    }

    /**
     * Executes data retention cleanup with custom reference time and batch size.
     * Guards against concurrent overlapping executions within the same JVM instance.
     *
     * @param referenceTime reference point in time
     * @param batchSize maximum number of IDs deleted per transaction
     * @return summary of deleted records
     */
    public RetentionCleanupResult cleanupExpiredRecords(Instant referenceTime, int batchSize) {
        Instant now = (referenceTime != null) ? referenceTime : Instant.now();
        int safeBatchSize = (batchSize > 0) ? batchSize : DEFAULT_BATCH_SIZE;

        if (!isRunning.compareAndSet(false, true)) {
            log.info("Data retention cleanup is already running; skipping concurrent execution");
            return RetentionCleanupResult.skipped(now);
        }

        long startTime = System.currentTimeMillis();
        try {
            log.info("Starting scheduled data retention cleanup at {}", now);

            Instant completedCutoff = now.minus(COMPLETED_ANALYSIS_RETENTION);
            Instant failedCutoff = now.minus(FAILED_ANALYSIS_RETENTION);
            Instant webhookCutoff = now.minus(WEBHOOK_DELIVERY_RETENTION);

            int deletedCompleted = deleteBatchedAnalyses(AnalysisStatus.COMPLETED, completedCutoff, safeBatchSize);
            int deletedFailed = deleteBatchedAnalyses(AnalysisStatus.FAILED, failedCutoff, safeBatchSize);
            int deletedWebhooks = deleteBatchedWebhookDeliveries(webhookCutoff, safeBatchSize);

            long durationMs = System.currentTimeMillis() - startTime;
            log.info("Data retention cleanup completed in {}ms: deleted {} completed analyses, {} failed analyses, {} webhook deliveries (total: {})",
                durationMs, deletedCompleted, deletedFailed, deletedWebhooks, deletedCompleted + deletedFailed + deletedWebhooks);

            return RetentionCleanupResult.completed(deletedCompleted, deletedFailed, deletedWebhooks, now);
        } catch (Throwable ex) {
            log.error("Unexpected error during data retention cleanup: {}", ex.getMessage(), ex);
            throw ex;
        } finally {
            isRunning.set(false);
        }
    }

    /**
     * Cleans up expired completed analyses older than 14 days from completedAt.
     *
     * @param referenceTime reference time
     * @param batchSize batch size
     * @return count of deleted completed analyses
     */
    public int cleanupCompletedAnalyses(Instant referenceTime, int batchSize) {
        Instant now = (referenceTime != null) ? referenceTime : Instant.now();
        int safeBatchSize = (batchSize > 0) ? batchSize : DEFAULT_BATCH_SIZE;
        Instant completedCutoff = now.minus(COMPLETED_ANALYSIS_RETENTION);
        return deleteBatchedAnalyses(AnalysisStatus.COMPLETED, completedCutoff, safeBatchSize);
    }

    /**
     * Cleans up expired failed analyses older than 7 days from completedAt (or failure/start timestamp).
     *
     * @param referenceTime reference time
     * @param batchSize batch size
     * @return count of deleted failed analyses
     */
    public int cleanupFailedAnalyses(Instant referenceTime, int batchSize) {
        Instant now = (referenceTime != null) ? referenceTime : Instant.now();
        int safeBatchSize = (batchSize > 0) ? batchSize : DEFAULT_BATCH_SIZE;
        Instant failedCutoff = now.minus(FAILED_ANALYSIS_RETENTION);
        return deleteBatchedAnalyses(AnalysisStatus.FAILED, failedCutoff, safeBatchSize);
    }

    /**
     * Cleans up expired webhook delivery records older than 7 days from receivedAt.
     *
     * @param referenceTime reference time
     * @param batchSize batch size
     * @return count of deleted webhook delivery records
     */
    public int cleanupWebhookDeliveries(Instant referenceTime, int batchSize) {
        Instant now = (referenceTime != null) ? referenceTime : Instant.now();
        int safeBatchSize = (batchSize > 0) ? batchSize : DEFAULT_BATCH_SIZE;
        Instant webhookCutoff = now.minus(WEBHOOK_DELIVERY_RETENTION);
        return deleteBatchedWebhookDeliveries(webhookCutoff, safeBatchSize);
    }

    private int deleteBatchedAnalyses(AnalysisStatus status, Instant cutoff, int batchSize) {
        int totalDeleted = 0;
        while (true) {
            Integer deleted = transactionTemplate.execute(txStatus -> {
                List<Long> ids = pullRequestAnalysisRepository.findExpiredAnalysisIds(
                    status,
                    cutoff,
                    PageRequest.of(0, batchSize)
                );
                if (ids == null || ids.isEmpty()) {
                    return 0;
                }
                return pullRequestAnalysisRepository.deleteByIdIn(ids);
            });
            int count = (deleted != null) ? deleted : 0;
            totalDeleted += count;
            if (count == 0 || count < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }

    private int deleteBatchedWebhookDeliveries(Instant cutoff, int batchSize) {
        int totalDeleted = 0;
        while (true) {
            Integer deleted = transactionTemplate.execute(txStatus -> {
                List<Long> ids = webhookDeliveryRepository.findExpiredDeliveryIds(
                    cutoff,
                    PageRequest.of(0, batchSize)
                );
                if (ids == null || ids.isEmpty()) {
                    return 0;
                }
                return webhookDeliveryRepository.deleteByIdIn(ids);
            });
            int count = (deleted != null) ? deleted : 0;
            totalDeleted += count;
            if (count == 0 || count < batchSize) {
                break;
            }
        }
        return totalDeleted;
    }
}
