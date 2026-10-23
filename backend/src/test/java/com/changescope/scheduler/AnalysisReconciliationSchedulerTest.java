package com.changescope.scheduler;

import com.changescope.ChangeScopeApplication;
import com.changescope.config.AnalysisProperties;
import com.changescope.service.PullRequestAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalysisReconciliationSchedulerTest {

    @Mock
    private PullRequestAnalysisService pullRequestAnalysisService;

    private AnalysisReconciliationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AnalysisReconciliationScheduler(pullRequestAnalysisService);
    }

    @Test
    void scheduleReconciliation_invokesReconciliationServiceAndReturnsCount() {
        when(pullRequestAnalysisService.reconcileStaleAnalyses()).thenReturn(4);

        int result = scheduler.scheduleReconciliation();

        assertThat(result).isEqualTo(4);
        verify(pullRequestAnalysisService, times(1)).reconcileStaleAnalyses();
    }

    @Test
    void scheduleReconciliation_whenReconciliationThrows_containsFailureAndAllowsSubsequentInvocations() {
        // First invocation throws an exception (e.g. transient DB error with potential secrets)
        when(pullRequestAnalysisService.reconcileStaleAnalyses())
            .thenThrow(new RuntimeException("Transient connection error: Bearer secret_token_xyz"))
            .thenReturn(2);

        // First run catches exception and returns -1 without throwing
        int firstResult = scheduler.scheduleReconciliation();
        assertThat(firstResult).isEqualTo(-1);

        // Subsequent run proceeds normally and executes reconciliation
        int secondResult = scheduler.scheduleReconciliation();
        assertThat(secondResult).isEqualTo(2);

        verify(pullRequestAnalysisService, times(2)).reconcileStaleAnalyses();
    }

    @Test
    void scheduledAnnotation_isConfiguredWithConfigurableFixedDelay() throws NoSuchMethodException {
        Method method = AnalysisReconciliationScheduler.class.getMethod("scheduleReconciliation");
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString()).isEqualTo("${analysis.reconciliation-interval:5m}");
    }

    @Test
    void enableSchedulingAnnotation_isPresentOnApplication() {
        EnableScheduling annotation = ChangeScopeApplication.class.getAnnotation(EnableScheduling.class);
        assertThat(annotation).isNotNull();
    }

    @Test
    void analysisProperties_defaultValues_areSensible() {
        AnalysisProperties properties = new AnalysisProperties();

        assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.getStaleTimeout()).isEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void analysisProperties_reconciliationInterval_bindsAndValidates() {
        AnalysisProperties properties = new AnalysisProperties();

        properties.setReconciliationInterval(Duration.ofMinutes(10));
        assertThat(properties.getReconciliationInterval()).isEqualTo(Duration.ofMinutes(10));

        assertThatThrownBy(() -> properties.setReconciliationInterval(Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> properties.setReconciliationInterval(Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");

        assertThatThrownBy(() -> properties.setReconciliationInterval(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("positive");
    }

    @Test
    void analysisProperties_existingStaleTimeoutValidation_remainsIntact() {
        AnalysisProperties properties = new AnalysisProperties();

        properties.setStaleTimeout(Duration.ofMinutes(45));
        assertThat(properties.getStaleTimeout()).isEqualTo(Duration.ofMinutes(45));

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

    @Configuration
    @EnableConfigurationProperties(AnalysisProperties.class)
    static class TestPropertiesConfig {}

    @Test
    void springContextBinding_bindsConfiguredReconciliationIntervalAndStaleTimeout() {
        new ApplicationContextRunner()
            .withUserConfiguration(TestPropertiesConfig.class)
            .withPropertyValues(
                "analysis.reconciliation-interval=15m",
                "analysis.stale-timeout=45m"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                AnalysisProperties props = context.getBean(AnalysisProperties.class);
                assertThat(props.getReconciliationInterval()).isEqualTo(Duration.ofMinutes(15));
                assertThat(props.getStaleTimeout()).isEqualTo(Duration.ofMinutes(45));
            });
    }

    @Test
    void springContextBinding_rejectsZeroOrNegativeReconciliationInterval() {
        new ApplicationContextRunner()
            .withUserConfiguration(TestPropertiesConfig.class)
            .withPropertyValues("analysis.reconciliation-interval=0s")
            .run(context -> assertThat(context).hasFailed());

        new ApplicationContextRunner()
            .withUserConfiguration(TestPropertiesConfig.class)
            .withPropertyValues("analysis.reconciliation-interval=-5m")
            .run(context -> assertThat(context).hasFailed());
    }
}
