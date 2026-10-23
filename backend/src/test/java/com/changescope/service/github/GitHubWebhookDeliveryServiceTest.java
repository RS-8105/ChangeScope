package com.changescope.service.github;

import com.changescope.model.WebhookDeliveryEntity;
import com.changescope.model.WebhookDeliveryStatus;
import com.changescope.repository.WebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import(GitHubWebhookDeliveryService.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class GitHubWebhookDeliveryServiceTest {

    @Autowired
    private GitHubWebhookDeliveryService deliveryService;

    @Autowired
    private WebhookDeliveryRepository deliveryRepository;

    @Test
    void registerDelivery_firstDelivery_succeedsAndPersists() {
        boolean registered = deliveryService.registerDelivery("del-001", "pull_request");

        assertThat(registered).isTrue();
        assertThat(deliveryRepository.existsByDeliveryId("del-001")).isTrue();

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-001").orElseThrow();
        assertThat(entity.getEvent()).isEqualTo("pull_request");
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.RECEIVED);
    }

    @Test
    void registerDelivery_repeatedDeliveryId_returnsFalseWithoutDuplicateEntry() {
        boolean first = deliveryService.registerDelivery("del-002", "pull_request");
        assertThat(first).isTrue();

        boolean second = deliveryService.registerDelivery("del-002", "pull_request");
        assertThat(second).isFalse();

        assertThat(deliveryRepository.findAll().stream()
            .filter(d -> "del-002".equals(d.getDeliveryId()))
            .count()).isEqualTo(1);
    }

    @Test
    void registerDelivery_concurrentAttempts_onlyOneSucceeds() throws Exception {
        String deliveryId = "del-concurrent-test";
        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return deliveryService.registerDelivery(deliveryId, "pull_request");
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Exactly one attempt must succeed and the other must fail as a duplicate
        int successCount = Collections.frequency(results, Boolean.TRUE);
        int duplicateCount = Collections.frequency(results, Boolean.FALSE);

        assertThat(successCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(1);

        // Database must contain exactly one record
        assertThat(deliveryRepository.findAll().stream()
            .filter(d -> deliveryId.equals(d.getDeliveryId()))
            .count()).isEqualTo(1);
    }

    @Test
    void markProcessed_updatesDeliveryStatus() {
        deliveryService.registerDelivery("del-003", "ping");

        deliveryService.markProcessed("del-003");

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-003").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
    }

    @Test
    void markFailed_updatesDeliveryStatus() {
        deliveryService.registerDelivery("del-004", "pull_request");

        deliveryService.markFailed("del-004");

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-004").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    void markFailed_withFailureSummary_sanitizesAndPersistsSummary() {
        deliveryService.registerDelivery("del-006", "pull_request");

        deliveryService.markFailed("del-006", "Failed with token ghp_secret1234567890abcdef and bearer secret_token");

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-006").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(entity.getFailureSummary()).doesNotContain("ghp_secret1234567890abcdef");
        assertThat(entity.getFailureSummary()).contains("***");
    }

    @Test
    void markFailed_withThrowable_sanitizesAndPersistsSummary() {
        deliveryService.registerDelivery("del-007", "pull_request");

        deliveryService.markFailed("del-007", new RuntimeException("Upstream token ghp_secret12345 failed"));

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-007").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(entity.getFailureSummary()).doesNotContain("ghp_secret12345");
        assertThat(entity.getFailureSummary()).contains("***");
    }

    @Test
    void markFailed_withGitHubAppInstallationToken_sanitizesInstallationToken() {
        deliveryService.registerDelivery("del-008", "pull_request");

        deliveryService.markFailed("del-008", "Failed using ghs_16C7e42F292c6912E7710c838347Ae178B4a for repository");

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-008").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(entity.getFailureSummary()).doesNotContain("16C7e42F292c6912E7710c838347Ae178B4a");
        assertThat(entity.getFailureSummary()).contains("gh***");
    }

    @Test
    void markFailed_withPemPrivateKeyAndJwtAndSecret_sanitizesAllCredentials() {
        deliveryService.registerDelivery("del-009", "pull_request");

        String sensitive = "Error with key -----BEGIN RSA PRIVATE KEY-----\nMIIEvgIBADANBgkqhkiG9w0BAQEFAASC\n-----END RSA PRIVATE KEY----- " +
            "jwt eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiIxMjM0NSJ9.abcdef1234567890 and secret=super_secret_webhook_123";
        deliveryService.markFailed("del-009", sensitive);

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-009").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(entity.getFailureSummary()).doesNotContain("MIIEvgIBADANBgkqhkiG9w0BAQEFAASC");
        assertThat(entity.getFailureSummary()).doesNotContain("eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9");
        assertThat(entity.getFailureSummary()).doesNotContain("super_secret_webhook_123");
        assertThat(entity.getFailureSummary()).contains("[PRIVATE_KEY_REDACTED]", "[JWT_REDACTED]", "secret=***");
    }

    @Test
    void registerDelivery_blankOrNullParameters_throwsException() {
        assertThatThrownBy(() -> deliveryService.registerDelivery(null, "pull_request"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveryService.registerDelivery("   ", "pull_request"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveryService.registerDelivery("del-005", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> deliveryService.registerDelivery("del-005", "  "))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerDelivery_processedDelivery_returnsFalse() {
        deliveryService.registerDelivery("del-processed-1", "pull_request");
        deliveryService.markProcessed("del-processed-1");

        boolean secondAttempt = deliveryService.registerDelivery("del-processed-1", "pull_request");
        assertThat(secondAttempt).isFalse();

        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId("del-processed-1").orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.PROCESSED);
    }

    @Test
    void registerDelivery_failedRetryableDelivery_reclaimsAndResetsToReceived() {
        deliveryService.registerDelivery("del-retry-1", "pull_request");
        com.changescope.exception.GitHubApiException transientError =
            new com.changescope.exception.GitHubApiException(502, "BAD_GATEWAY", "GitHub API temporary failure");
        deliveryService.markFailed("del-retry-1", transientError);

        WebhookDeliveryEntity failedEntity = deliveryRepository.findByDeliveryId("del-retry-1").orElseThrow();
        assertThat(failedEntity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failedEntity.isRetryable()).isTrue();
        assertThat(failedEntity.getFailureSummary()).contains("GitHub API temporary failure");

        // Retry arrives: legitimate retry must be allowed
        boolean retryRegistered = deliveryService.registerDelivery("del-retry-1", "pull_request");
        assertThat(retryRegistered).isTrue();

        WebhookDeliveryEntity reclaimedEntity = deliveryRepository.findByDeliveryId("del-retry-1").orElseThrow();
        assertThat(reclaimedEntity.getStatus()).isEqualTo(WebhookDeliveryStatus.RECEIVED);
        assertThat(reclaimedEntity.getFailureSummary()).isNull();
        assertThat(deliveryRepository.findAll().stream()
            .filter(d -> "del-retry-1".equals(d.getDeliveryId()))
            .count()).isEqualTo(1);
    }

    @Test
    void registerDelivery_failedPermanentDelivery_returnsFalseWithoutReclaiming() {
        deliveryService.registerDelivery("del-permanent-1", "pull_request");
        com.changescope.exception.ResourceNotFoundException permanentError =
            new com.changescope.exception.ResourceNotFoundException("Repository not found with owner 'octocat' and name 'repo'");
        deliveryService.markFailed("del-permanent-1", permanentError);

        WebhookDeliveryEntity failedEntity = deliveryRepository.findByDeliveryId("del-permanent-1").orElseThrow();
        assertThat(failedEntity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
        assertThat(failedEntity.isRetryable()).isFalse();

        // Retry arrives for a permanent failure: must be rejected
        boolean retryRegistered = deliveryService.registerDelivery("del-permanent-1", "pull_request");
        assertThat(retryRegistered).isFalse();

        WebhookDeliveryEntity unchangedEntity = deliveryRepository.findByDeliveryId("del-permanent-1").orElseThrow();
        assertThat(unchangedEntity.getStatus()).isEqualTo(WebhookDeliveryStatus.FAILED);
    }

    @Test
    void registerDelivery_failedHistoricalRevisionMismatch_isNotRetryable() {
        deliveryService.registerDelivery("del-hist-1", "pull_request");
        com.changescope.exception.GitHubApiException historicalError =
            new com.changescope.exception.GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED", "PR head revision has advanced");
        deliveryService.markFailed("del-hist-1", historicalError);

        WebhookDeliveryEntity failedEntity = deliveryRepository.findByDeliveryId("del-hist-1").orElseThrow();
        assertThat(failedEntity.isRetryable()).isFalse();

        boolean retryRegistered = deliveryService.registerDelivery("del-hist-1", "pull_request");
        assertThat(retryRegistered).isFalse();
    }

    @Test
    void registerDelivery_concurrentRetriesOfFailedDelivery_onlyOneSucceeds() throws Exception {
        String deliveryId = "del-concurrent-retry-test";
        deliveryService.registerDelivery(deliveryId, "pull_request");
        deliveryService.markFailed(deliveryId, new com.changescope.exception.GitHubRateLimitException("Rate limit exceeded"));

        int threadCount = 2;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);

        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                readyLatch.countDown();
                startLatch.await(5, TimeUnit.SECONDS);
                return deliveryService.registerDelivery(deliveryId, "pull_request");
            }));
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();

        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : futures) {
            results.add(future.get(5, TimeUnit.SECONDS));
        }
        executor.shutdown();

        // Exactly one retry attempt must succeed and the other must be rejected
        int successCount = Collections.frequency(results, Boolean.TRUE);
        int duplicateCount = Collections.frequency(results, Boolean.FALSE);

        assertThat(successCount).isEqualTo(1);
        assertThat(duplicateCount).isEqualTo(1);

        // Database must still have only 1 entity, now in RECEIVED state
        WebhookDeliveryEntity entity = deliveryRepository.findByDeliveryId(deliveryId).orElseThrow();
        assertThat(entity.getStatus()).isEqualTo(WebhookDeliveryStatus.RECEIVED);
    }

    @Test
    void isRetryable_classifiesTransientAndPermanentErrorsCorrectly() {
        // Permanent
        assertThat(GitHubWebhookDeliveryService.isRetryable(new IllegalArgumentException("bad payload"))).isFalse();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.ResourceNotFoundException("missing repo"))).isFalse();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubResourceNotFoundException("PR 404"))).isFalse();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubAuthenticationException(401, "unauthorized"))).isFalse();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED", "advanced"))).isFalse();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubApiException(400, "BAD_REQUEST", "limit"))).isFalse();

        // Retryable
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubRateLimitException("rate limit"))).isTrue();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubApiException(500, "SERVER_ERROR", "500"))).isTrue();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubApiException(502, "BAD_GATEWAY", "502"))).isTrue();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new com.changescope.exception.GitHubApiException(503, "SERVICE_UNAVAILABLE", "503"))).isTrue();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new org.springframework.web.client.ResourceAccessException("timeout"))).isTrue();

        // Nested causes
        assertThat(GitHubWebhookDeliveryService.isRetryable(new RuntimeException("wrap", new java.io.IOException("reset")))).isTrue();
        assertThat(GitHubWebhookDeliveryService.isRetryable(new RuntimeException("wrap", new com.changescope.exception.ResourceNotFoundException("repo")))).isFalse();
    }
}
