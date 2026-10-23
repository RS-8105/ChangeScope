package com.changescope.service.github;

import com.changescope.dto.WebhookResponse;
import com.changescope.exception.GitHubAuthenticationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Service for processing incoming GitHub webhook deliveries.
 * Validates request signatures prior to any payload parsing or event handling.
 */
@Service
public class GitHubWebhookService {

    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final ObjectMapper objectMapper;
    private final GitHubWebhookDeliveryService deliveryService;
    private final GitHubPullRequestEventProcessor pullRequestEventProcessor;

    public GitHubWebhookService(
        GitHubWebhookSignatureVerifier signatureVerifier,
        ObjectMapper objectMapper,
        GitHubWebhookDeliveryService deliveryService,
        GitHubPullRequestEventProcessor pullRequestEventProcessor
    ) {
        this.signatureVerifier = signatureVerifier;
        this.objectMapper = objectMapper;
        this.deliveryService = deliveryService;
        this.pullRequestEventProcessor = pullRequestEventProcessor;
    }

    /**
     * Processes an incoming GitHub webhook request.
     *
     * Verification Order:
     * 1. Signature verification using X-Hub-Signature-256 over exact raw bytes.
     * 2. Header presence validation (X-GitHub-Event and X-GitHub-Delivery).
     * 3. JSON payload parsing.
     * 4. Persistent delivery registration for deduplication.
     * 5. Event processing (pull_request analysis, ping acknowledgment, or ignore).
     *
     * @param rawPayload      the raw request body bytes
     * @param signatureHeader value of X-Hub-Signature-256 header
     * @param event           value of X-GitHub-Event header
     * @param delivery        value of X-GitHub-Delivery header
     * @return WebhookResponse indicating whether the event was accepted, ignored, or duplicate
     */
    public WebhookResponse processWebhook(byte[] rawPayload, String signatureHeader, String event, String delivery) {
        // 1. Signature verification FIRST - fail fast with 401 before any parsing or DB access
        if (signatureHeader == null || !signatureVerifier.verifySignature(rawPayload, signatureHeader)) {
            throw new GitHubAuthenticationException(401, "GitHub webhook signature verification failed");
        }

        // 2. Validate event and delivery headers - reject with 400 if missing or blank
        if (event == null || event.isBlank()) {
            throw new IllegalArgumentException("Missing or blank X-GitHub-Event header");
        }
        if (delivery == null || delivery.isBlank()) {
            throw new IllegalArgumentException("Missing or blank X-GitHub-Delivery header");
        }

        // 3. Parse JSON payload using Jackson - reject with 400 if malformed
        if (rawPayload == null || rawPayload.length == 0) {
            throw new IllegalArgumentException("Webhook payload must not be empty");
        }
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(rawPayload);
            if (rootNode == null || !rootNode.isObject()) {
                throw new IllegalArgumentException("Malformed JSON webhook payload");
            }
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Malformed JSON webhook payload", ex);
        }

        String cleanEvent = event.trim().toLowerCase();
        String cleanDelivery = delivery.trim();

        // 4. Register delivery for deduplication
        boolean isNew = deliveryService.registerDelivery(cleanDelivery, cleanEvent);
        if (!isNew) {
            return WebhookResponse.duplicate(cleanEvent, cleanDelivery, "Duplicate delivery ignored");
        }

        // 5. Event processing with deliberate delivery lifecycle updates
        try {
            WebhookResponse response;
            if ("ping".equals(cleanEvent)) {
                response = WebhookResponse.accepted(cleanEvent, cleanDelivery, "Event 'ping' acknowledged");
            } else if ("pull_request".equals(cleanEvent)) {
                response = pullRequestEventProcessor.processPullRequestEvent(rootNode, cleanDelivery);
            } else {
                response = WebhookResponse.ignored(cleanEvent, cleanDelivery, "Event '" + cleanEvent + "' is currently ignored");
            }

            deliveryService.markProcessed(cleanDelivery);
            return response;
        } catch (Throwable ex) {
            deliveryService.markFailed(cleanDelivery, ex);
            throw ex;
        }
    }
}
