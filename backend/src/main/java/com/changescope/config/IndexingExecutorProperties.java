package com.changescope.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for the dedicated repository indexing ThreadPoolTaskExecutor.
 */
@Configuration
@ConfigurationProperties(prefix = "indexing.executor")
public class IndexingExecutorProperties {

    /**
     * Core number of worker threads. Defaults to 2.
     */
    private int corePoolSize = 2;

    /**
     * Maximum number of worker threads under peak load. Defaults to 4.
     */
    private int maxPoolSize = 4;

    /**
     * Capacity of the bounded work queue. Defaults to 50 tasks.
     */
    private int queueCapacity = 50;

    /**
     * Prefix for naming executor worker threads. Defaults to 'indexing-'.
     */
    private String threadNamePrefix = "indexing-";

    /**
     * Whether to wait for remaining tasks to finish on application shutdown. Defaults to true.
     */
    private boolean waitForTasksToCompleteOnShutdown = true;

    /**
     * Maximum time in seconds to wait for active tasks to terminate during graceful shutdown. Defaults to 30 seconds.
     */
    private int awaitTerminationSeconds = 30;

    public int getCorePoolSize() {
        return corePoolSize;
    }

    public void setCorePoolSize(int corePoolSize) {
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize must be greater than zero");
        }
        this.corePoolSize = corePoolSize;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        if (maxPoolSize <= 0) {
            throw new IllegalArgumentException("maxPoolSize must be greater than zero");
        }
        this.maxPoolSize = maxPoolSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must be greater than or equal to zero");
        }
        this.queueCapacity = queueCapacity;
    }

    public String getThreadNamePrefix() {
        return threadNamePrefix;
    }

    public void setThreadNamePrefix(String threadNamePrefix) {
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be null or blank");
        }
        this.threadNamePrefix = threadNamePrefix;
    }

    public boolean isWaitForTasksToCompleteOnShutdown() {
        return waitForTasksToCompleteOnShutdown;
    }

    public void setWaitForTasksToCompleteOnShutdown(boolean waitForTasksToCompleteOnShutdown) {
        this.waitForTasksToCompleteOnShutdown = waitForTasksToCompleteOnShutdown;
    }

    public int getAwaitTerminationSeconds() {
        return awaitTerminationSeconds;
    }

    public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
        if (awaitTerminationSeconds < 0) {
            throw new IllegalArgumentException("awaitTerminationSeconds must be greater than or equal to zero");
        }
        this.awaitTerminationSeconds = awaitTerminationSeconds;
    }

    @PostConstruct
    public void validate() {
        if (corePoolSize <= 0) {
            throw new IllegalArgumentException("corePoolSize must be greater than zero");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maxPoolSize must be greater than or equal to corePoolSize");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("queueCapacity must be greater than or equal to zero");
        }
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalArgumentException("threadNamePrefix must not be null or blank");
        }
        if (awaitTerminationSeconds < 0) {
            throw new IllegalArgumentException("awaitTerminationSeconds must be greater than or equal to zero");
        }
    }
}
