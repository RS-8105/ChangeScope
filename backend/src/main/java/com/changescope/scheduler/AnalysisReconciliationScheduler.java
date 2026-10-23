package com.changescope.scheduler;

import com.changescope.service.PullRequestAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically triggers reconciliation of abandoned or interrupted pull-request analyses.
 */
@Component
public class AnalysisReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(AnalysisReconciliationScheduler.class);

    private final PullRequestAnalysisService pullRequestAnalysisService;

    public AnalysisReconciliationScheduler(PullRequestAnalysisService pullRequestAnalysisService) {
        this.pullRequestAnalysisService = pullRequestAnalysisService;
    }

    /**
     * Periodically reconciles stale pull-request analyses left in RUNNING status.
     * Executes only a database-level reconciliation query and catches any unexpected
     * errors so future scheduled executions continue unimpeded.
     *
     * @return count of reconciled analyses, or -1 if an error occurred
     */
    @Scheduled(fixedDelayString = "${analysis.reconciliation-interval:5m}")
    public int scheduleReconciliation() {
        try {
            int reconciledCount = pullRequestAnalysisService.reconcileStaleAnalyses();
            if (reconciledCount > 0) {
                log.info("Reconciled {} stale pull-request analyses to FAILED status", reconciledCount);
            } else {
                log.debug("Reconciliation completed; no stale analyses found");
            }
            return reconciledCount;
        } catch (Throwable ex) {
            String sanitizedMessage = PullRequestAnalysisService.sanitizeFailureSummary(ex);
            log.error("Failed to reconcile stale pull-request analyses: {}", sanitizedMessage);
            return -1;
        }
    }
}
