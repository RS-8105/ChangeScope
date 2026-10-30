package com.changescope.dto;

import java.time.Instant;

/**
 * Summary record capturing metrics from a data retention cleanup execution.
 *
 * @param deletedCompletedAnalyses count of COMPLETED analyses deleted (older than 14 days)
 * @param deletedFailedAnalyses count of FAILED analyses deleted (older than 7 days)
 * @param deletedWebhookDeliveries count of webhook deliveries deleted (older than 7 days)
 * @param totalDeleted sum of all deleted records in this execution
 * @param executedAt reference timestamp of the cleanup execution
 * @param skipped true if cleanup execution was skipped (e.g. concurrent execution guard)
 */
public record RetentionCleanupResult(
    int deletedCompletedAnalyses,
    int deletedFailedAnalyses,
    int deletedWebhookDeliveries,
    int totalDeleted,
    Instant executedAt,
    boolean skipped
) {
    public static RetentionCleanupResult skipped(Instant executedAt) {
        return new RetentionCleanupResult(0, 0, 0, 0, executedAt, true);
    }

    public static RetentionCleanupResult completed(
        int deletedCompletedAnalyses,
        int deletedFailedAnalyses,
        int deletedWebhookDeliveries,
        Instant executedAt
    ) {
        int total = deletedCompletedAnalyses + deletedFailedAnalyses + deletedWebhookDeliveries;
        return new RetentionCleanupResult(
            deletedCompletedAnalyses,
            deletedFailedAnalyses,
            deletedWebhookDeliveries,
            total,
            executedAt,
            false
        );
    }
}
