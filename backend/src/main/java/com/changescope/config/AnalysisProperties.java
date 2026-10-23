package com.changescope.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Configuration properties for pull-request analysis execution and lifecycle reconciliation.
 */
@Configuration
@ConfigurationProperties(prefix = "analysis")
public class AnalysisProperties {

    /**
     * Duration after which a RUNNING analysis is considered abandoned/stale
     * and eligible for reconciliation to FAILED. Defaults to 30 minutes.
     */
    private Duration staleTimeout = Duration.ofMinutes(30);

    /**
     * Interval between successive reconciliation runs. Defaults to 5 minutes.
     */
    private Duration reconciliationInterval = Duration.ofMinutes(5);

    public Duration getStaleTimeout() {
        return staleTimeout;
    }

    public void setStaleTimeout(Duration staleTimeout) {
        if (staleTimeout == null || staleTimeout.isNegative() || staleTimeout.isZero()) {
            throw new IllegalArgumentException("Stale analysis timeout must be a positive duration");
        }
        this.staleTimeout = staleTimeout;
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        if (reconciliationInterval == null || reconciliationInterval.isNegative() || reconciliationInterval.isZero()) {
            throw new IllegalArgumentException("Reconciliation interval must be a positive duration");
        }
        this.reconciliationInterval = reconciliationInterval;
    }

    @PostConstruct
    public void validate() {
        if (staleTimeout == null || staleTimeout.isNegative() || staleTimeout.isZero()) {
            throw new IllegalArgumentException("Stale analysis timeout must be a positive duration");
        }
        if (reconciliationInterval == null || reconciliationInterval.isNegative() || reconciliationInterval.isZero()) {
            throw new IllegalArgumentException("Reconciliation interval must be a positive duration");
        }
    }
}
