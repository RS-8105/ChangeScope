package com.changescope.service.github;

import com.changescope.model.WebhookDeliveryEntity;
import com.changescope.model.WebhookDeliveryStatus;
import com.changescope.repository.WebhookDeliveryRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * Service for persistent deduplication and lifecycle tracking of GitHub webhook deliveries.
 *
 * Uses database-level uniqueness constraints and isolated transactions to guarantee
 * that duplicate deliveries (including concurrent requests) are detected reliably.
 */
@Service
public class GitHubWebhookDeliveryService {

    private final WebhookDeliveryRepository deliveryRepository;
    private final TransactionTemplate transactionTemplate;

    @Autowired
    public GitHubWebhookDeliveryService(
        WebhookDeliveryRepository deliveryRepository,
        PlatformTransactionManager transactionManager
    ) {
        this.deliveryRepository = deliveryRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public GitHubWebhookDeliveryService(
        WebhookDeliveryRepository deliveryRepository,
        TransactionTemplate transactionTemplate
    ) {
        this.deliveryRepository = deliveryRepository;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Atomically registers a delivery before event processing.
     * If the delivery previously failed with a retryable error, claims it for retry.
     *
     * @param deliveryId the unique GitHub delivery ID (from X-GitHub-Delivery)
     * @param event      the GitHub event name (from X-GitHub-Event)
     * @return true if this is a new delivery or a reclaimed retryable delivery; false if it is a duplicate
     */
    public boolean registerDelivery(String deliveryId, String event) {
        if (deliveryId == null || deliveryId.isBlank()) {
            throw new IllegalArgumentException("Delivery ID must not be null or blank");
        }
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Event must not be null or blank");
        }

        String cleanDeliveryId = deliveryId.trim();
        String cleanEvent = event.trim().toLowerCase();

        try {
            Boolean registered = transactionTemplate.execute(status -> {
                // 1. Check if delivery already exists in the database
                Optional<WebhookDeliveryEntity> existing = deliveryRepository.findByDeliveryId(cleanDeliveryId);
                if (existing.isPresent()) {
                    WebhookDeliveryEntity entity = existing.get();
                    // A delivery currently RECEIVED or already PROCESSED must never trigger processing again
                    if (entity.getStatus() == WebhookDeliveryStatus.RECEIVED || entity.getStatus() == WebhookDeliveryStatus.PROCESSED) {
                        return false;
                    }

                    // A FAILED delivery may be retried only when its failure is classified as retryable
                    if (entity.getStatus() == WebhookDeliveryStatus.FAILED && entity.isRetryable()) {
                        // Atomically claim the retry using atomic UPDATE with status check
                        int claimed = deliveryRepository.claimForRetry(
                            cleanDeliveryId,
                            WebhookDeliveryStatus.RECEIVED,
                            WebhookDeliveryStatus.FAILED,
                            Instant.now()
                        );
                        return claimed > 0;
                    }

                    // Permanent failure: not eligible for retry
                    return false;
                }

                // 2. Delivery does not exist yet: atomic insert within isolated transaction
                try {
                    WebhookDeliveryEntity entity = new WebhookDeliveryEntity(
                        cleanDeliveryId,
                        cleanEvent,
                        Instant.now(),
                        WebhookDeliveryStatus.RECEIVED,
                        null,
                        false
                    );
                    deliveryRepository.saveAndFlush(entity);
                    return true;
                } catch (DataIntegrityViolationException ex) {
                    status.setRollbackOnly();
                    return false;
                }
            });
            return Boolean.TRUE.equals(registered);
        } catch (DataIntegrityViolationException | org.springframework.transaction.TransactionException ex) {
            // Concurrent insert or transaction rollback
            return false;
        }
    }

    /**
     * Updates the delivery status to PROCESSED after successful event handling.
     * Clears failure metadata and resets retryable flag.
     *
     * @param deliveryId the unique GitHub delivery ID
     */
    public void markProcessed(String deliveryId) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            deliveryRepository.findByDeliveryId(deliveryId.trim()).ifPresent(entity -> {
                entity.setStatus(WebhookDeliveryStatus.PROCESSED);
                entity.setFailureSummary(null);
                entity.setRetryable(false);
                deliveryRepository.save(entity);
            });
        });
    }

    /**
     * Updates the delivery status to FAILED if event processing fails unexpectedly.
     *
     * @param deliveryId the unique GitHub delivery ID
     */
    public void markFailed(String deliveryId) {
        markFailed(deliveryId, (String) null, false);
    }

    /**
     * Updates the delivery status to FAILED with a sanitized error message and retryability classification from a Throwable.
     *
     * @param deliveryId the unique GitHub delivery ID
     * @param throwable  the error that caused the failure
     */
    public void markFailed(String deliveryId, Throwable throwable) {
        boolean retryable = isRetryable(throwable);
        markFailed(deliveryId, sanitizeFailureSummary(throwable), retryable);
    }

    /**
     * Updates the delivery status to FAILED with a sanitized failure summary (defaults to non-retryable).
     *
     * @param deliveryId     the unique GitHub delivery ID
     * @param failureSummary a sanitized summary of why the delivery failed
     */
    public void markFailed(String deliveryId, String failureSummary) {
        markFailed(deliveryId, failureSummary, false);
    }

    /**
     * Updates the delivery status to FAILED with a sanitized failure summary and explicit retryable flag.
     *
     * @param deliveryId     the unique GitHub delivery ID
     * @param failureSummary a sanitized summary of why the delivery failed
     * @param retryable      whether the failure is eligible for future retry
     */
    public void markFailed(String deliveryId, String failureSummary, boolean retryable) {
        if (deliveryId == null || deliveryId.isBlank()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            deliveryRepository.findByDeliveryId(deliveryId.trim()).ifPresent(entity -> {
                entity.setStatus(WebhookDeliveryStatus.FAILED);
                entity.setRetryable(retryable);
                if (failureSummary != null && !failureSummary.isBlank()) {
                    entity.setFailureSummary(sanitizeFailureSummary(failureSummary));
                }
                deliveryRepository.save(entity);
            });
        });
    }

    /**
     * Classifies whether a failure is retryable (transient) or permanent.
     *
     * Permanent failures (non-retryable):
     * - Bad payload or parameter validation (IllegalArgumentException)
     * - Unregistered repository (ResourceNotFoundException)
     * - Unsupported historical revision / 409 conflict
     * - Permanent client errors (400, 404, auth failures 401/403)
     *
     * Retryable failures:
     * - Rate limiting (GitHubRateLimitException, HTTP 429)
     * - GitHub server errors (500, 502, 503, 504)
     * - Network I/O errors (ResourceAccessException, IOException, TimeoutException)
     *
     * @param throwable the caught exception
     * @return true if retryable, false if permanent
     */
    public static boolean isRetryable(Throwable throwable) {
        if (throwable == null) {
            return false;
        }

        // 1. Permanent: Bad payload or parameter validation errors
        if (throwable instanceof IllegalArgumentException) {
            return false;
        }

        // 2. Permanent: Unregistered repository or local missing resource
        if (throwable instanceof com.changescope.exception.ResourceNotFoundException) {
            return false;
        }

        // 3. GitHub API exceptions
        if (throwable instanceof com.changescope.exception.GitHubRateLimitException) {
            return true;
        }

        if (throwable instanceof com.changescope.exception.GitHubResourceNotFoundException) {
            return false;
        }

        if (throwable instanceof com.changescope.exception.GitHubAuthenticationException) {
            return false;
        }

        if (throwable instanceof com.changescope.exception.GitHubApiException gha) {
            if ("HISTORICAL_REVISION_UNSUPPORTED".equalsIgnoreCase(gha.getErrorCategory())
                || gha.getStatusCode() == 409) {
                return false;
            }
            if (gha.getStatusCode() == 400 || gha.getStatusCode() == 404) {
                return false;
            }
            if (gha.getStatusCode() >= 500 || gha.getStatusCode() == 429) {
                return true;
            }
        }

        // 4. Network and I/O errors
        if (throwable instanceof org.springframework.web.client.ResourceAccessException
            || throwable instanceof java.io.IOException
            || throwable instanceof java.util.concurrent.TimeoutException) {
            return true;
        }

        // 5. Inspect root cause if wrapped
        Throwable cause = throwable.getCause();
        if (cause != null && cause != throwable) {
            return isRetryable(cause);
        }

        return false;
    }

    /**
     * Sanitizes a failure summary message by stripping sensitive patterns such as tokens, secrets,
     * and bounding length to prevent storage exhaustion.
     *
     * @param message the raw error message
     * @return sanitized and bounded error message safe for persistence
     */
    public static String sanitizeFailureSummary(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }
        String sanitized = message
            .replaceAll("-----BEGIN[A-Z0-9 -]+KEY-----[\\s\\S]*?-----END[A-Z0-9 -]+KEY-----", "[PRIVATE_KEY_REDACTED]")
            .replaceAll("(?i)(bearer\\s+|token\\s+|secret\\s*[:=]\\s*|password\\s*[:=]\\s*)[A-Za-z0-9_.-]+", "$1***")
            .replaceAll("gh[pousr]_[A-Za-z0-9_]+", "gh***")
            .replaceAll("github_pat_[A-Za-z0-9_]+", "github_pat_***")
            .replaceAll("ey[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}", "[JWT_REDACTED]");
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 497) + "...";
        }
        return sanitized;
    }

    /**
     * Sanitizes a throwable into a safe failure summary.
     *
     * @param throwable the caught exception
     * @return sanitized error summary
     */
    public static String sanitizeFailureSummary(Throwable throwable) {
        if (throwable == null) {
            return "Unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return sanitizeFailureSummary(message);
    }
}
