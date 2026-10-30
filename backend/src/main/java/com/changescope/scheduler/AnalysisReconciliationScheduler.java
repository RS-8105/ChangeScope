package com.changescope.scheduler;

import com.changescope.service.PullRequestAnalysisService;
import com.changescope.service.RepositoryIndexingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers reconciliation of abandoned or interrupted pull-request analyses
 * and repository indexing operations.
 */
@Component
public class AnalysisReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReconciliationScheduler.class);

    private final PullRequestAnalysisService pullRequestAnalysisService;
    private final RepositoryIndexingService repositoryIndexingService;

    public AnalysisReconciliationScheduler(PullRequestAnalysisService pullRequestAnalysisService) {
        this(pullRequestAnalysisService, null);
    }

    @Autowired
    public AnalysisReconciliationScheduler(
            PullRequestAnalysisService pullRequestAnalysisService,
            @Autowired(required = false) RepositoryIndexingService repositoryIndexingService) {
        this.pullRequestAnalysisService = pullRequestAnalysisService;
        this.repositoryIndexingService = repositoryIndexingService;
    }

    /**
     * Periodically reconciles stale pull-request analyses and repository indexes left in RUNNING/INDEXING status.
     * Executes only database-level reconciliation queries and catches any unexpected
     * errors so future scheduled executions continue unimpeded.
     *
     * @return count of reconciled records, or -1 if an error occurred
     */
    @Scheduled(fixedDelayString = "${analysis.reconciliation-interval:5m}")
    public int scheduleReconciliation() {
        int totalReconciled = 0;
        boolean errorEncountered = false;

        try {
            int reconciledAnalyses = pullRequestAnalysisService.reconcileStaleAnalyses();
            if (reconciledAnalyses > 0) {
                log.info("Reconciled {} stale pull-request analyses to FAILED status", reconciledAnalyses);
            } else {
                log.debug("Reconciliation completed; no stale analyses found");
            }
            totalReconciled += reconciledAnalyses;
        } catch (Throwable ex) {
            String sanitizedMessage = PullRequestAnalysisService.sanitizeFailureSummary(ex);
            log.error("Failed to reconcile stale pull-request analyses: {}", sanitizedMessage);
            errorEncountered = true;
        }

        if (repositoryIndexingService != null) {
            try {
                int reconciledIndexes = repositoryIndexingService.reconcileStaleIndexes();
                if (reconciledIndexes > 0) {
                    log.info("Reconciled {} stale repository indexes to FAILED status", reconciledIndexes);
                } else {
                    log.debug("Reconciliation completed; no stale repository indexes found");
                }
                totalReconciled += reconciledIndexes;
            } catch (Throwable ex) {
                String sanitizedMessage = PullRequestAnalysisService.sanitizeFailureSummary(ex);
                log.error("Failed to reconcile stale repository indexes: {}", sanitizedMessage);
                errorEncountered = true;
            }

            try {
                int dispatchedRetries = repositoryIndexingService.reconcileDueRetries();
                if (dispatchedRetries > 0) {
                    log.info("Dispatched {} due repository indexing retries", dispatchedRetries);
                }
                totalReconciled += dispatchedRetries;
            } catch (Throwable ex) {
                String sanitizedMessage = PullRequestAnalysisService.sanitizeFailureSummary(ex);
                log.error("Failed to reconcile and dispatch due repository indexing retries: {}", sanitizedMessage);
                errorEncountered = true;
            }
        }

        return errorEncountered ? -1 : totalReconciled;
    }

    /**
     * Periodically polls for and dispatches due repository indexing retries based on configurable check interval.
     *
     * @return count of dispatched retries, or -1 if an error occurred
     */
    @Scheduled(fixedDelayString = "${indexing.retry.check-interval:PT30S}")
    public int scheduleDueRetryReconciliation() {
        if (repositoryIndexingService == null) {
            return 0;
        }
        try {
            int dispatched = repositoryIndexingService.reconcileDueRetries();
            if (dispatched > 0) {
                log.info("Dispatched {} due repository indexing retries", dispatched);
            }
            return dispatched;
        } catch (Throwable ex) {
            String sanitizedMessage = PullRequestAnalysisService.sanitizeFailureSummary(ex);
            log.error("Failed to reconcile and dispatch due repository indexing retries: {}", sanitizedMessage);
            return -1;
        }
    }

}
