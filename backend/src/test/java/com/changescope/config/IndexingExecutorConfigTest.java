package com.changescope.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IndexingExecutorConfigTest {

    @Test
    @DisplayName("Default properties and bean configuration are initialized properly")
    void defaultConfiguration_createsConfiguredExecutor() {
        IndexingExecutorProperties properties = new IndexingExecutorProperties();
        assertThat(properties.getCorePoolSize()).isEqualTo(2);
        assertThat(properties.getMaxPoolSize()).isEqualTo(4);
        assertThat(properties.getQueueCapacity()).isEqualTo(50);
        assertThat(properties.getThreadNamePrefix()).isEqualTo("indexing-");
        assertThat(properties.getAwaitTerminationSeconds()).isEqualTo(30);
        assertThat(properties.isWaitForTasksToCompleteOnShutdown()).isTrue();

        IndexingExecutorConfig config = new IndexingExecutorConfig();
        ThreadPoolTaskExecutor executor = config.repositoryIndexingExecutor(properties);

        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(4);
        assertThat(executor.getQueueCapacity()).isEqualTo(50);
        assertThat(executor.getThreadNamePrefix()).isEqualTo("indexing-");

        executor.destroy();
    }

    @Test
    @DisplayName("Property validation fails on non-positive corePoolSize")
    void validation_nonPositiveCorePoolSize_throwsException() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        assertThatThrownBy(() -> props.setCorePoolSize(0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("corePoolSize must be greater than zero");
    }

    @Test
    @DisplayName("Property validation fails when maxPoolSize is less than corePoolSize")
    void validation_maxPoolSizeLessThanCore_throwsException() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        props.setCorePoolSize(4);
        props.setMaxPoolSize(2);
        assertThatThrownBy(props::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxPoolSize must be greater than or equal to corePoolSize");
    }

    @Test
    @DisplayName("Property validation fails on negative queueCapacity")
    void validation_negativeQueueCapacity_throwsException() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        assertThatThrownBy(() -> props.setQueueCapacity(-1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("queueCapacity must be greater than or equal to zero");
    }

    @Test
    @DisplayName("Property validation fails on blank threadNamePrefix")
    void validation_blankThreadNamePrefix_throwsException() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        assertThatThrownBy(() -> props.setThreadNamePrefix("   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("threadNamePrefix must not be null or blank");
    }

    @Test
    @DisplayName("Property validation fails on negative awaitTerminationSeconds")
    void validation_negativeAwaitTerminationSeconds_throwsException() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        assertThatThrownBy(() -> props.setAwaitTerminationSeconds(-5))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("awaitTerminationSeconds must be greater than or equal to zero");
    }

    @Test
    @DisplayName("PostConstruct validate succeeds with defaults and fails on non-positive corePoolSize")
    void postConstructValidate_validAndInvalidState() {
        IndexingExecutorProperties props = new IndexingExecutorProperties();
        props.validate(); // default valid

        props.setCorePoolSize(10);
        assertThatThrownBy(props::validate)
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maxPoolSize must be greater than or equal to corePoolSize");
    }

    @Test
    @DisplayName("Queue saturation triggers RejectedExecutionException via AbortPolicy")
    void queueSaturation_triggersRejectionException() throws Exception {
        IndexingExecutorProperties properties = new IndexingExecutorProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(1);
        properties.setThreadNamePrefix("test-indexing-");

        IndexingExecutorConfig config = new IndexingExecutorConfig();
        ThreadPoolTaskExecutor executor = config.repositoryIndexingExecutor(properties);

        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch releaseTask = new CountDownLatch(1);

        try {
            // Task 1: occupies the 1 core thread
            executor.execute(() -> {
                taskStarted.countDown();
                try {
                    releaseTask.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });

            assertThat(taskStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // Task 2: fills the queue of capacity 1
            executor.execute(() -> {});

            // Task 3: pool is at max (1) and queue is full (1) -> must be rejected
            assertThatThrownBy(() -> executor.execute(() -> {}))
                .isInstanceOf(RejectedExecutionException.class);
        } finally {
            releaseTask.countDown();
            executor.destroy();
        }
    }

    @Test
    @DisplayName("Executor shutdown gracefully waits for running task to complete")
    void executorShutdown_waitsForRunningTasksToComplete() throws Exception {
        IndexingExecutorProperties properties = new IndexingExecutorProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(1);
        properties.setQueueCapacity(5);
        properties.setWaitForTasksToCompleteOnShutdown(true);
        properties.setAwaitTerminationSeconds(5);
        properties.setThreadNamePrefix("test-shutdown-");

        IndexingExecutorConfig config = new IndexingExecutorConfig();
        ThreadPoolTaskExecutor executor = config.repositoryIndexingExecutor(properties);

        CountDownLatch runningLatch = new CountDownLatch(1);
        AtomicBoolean taskCompleted = new AtomicBoolean(false);

        executor.execute(() -> {
            runningLatch.countDown();
            try {
                Thread.sleep(200);
                taskCompleted.set(true);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(runningLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // Destroy initiates graceful shutdown and awaits termination
        executor.destroy();

        assertThat(taskCompleted.get()).isTrue();
    }
}
