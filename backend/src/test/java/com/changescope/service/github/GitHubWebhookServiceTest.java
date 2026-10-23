package com.changescope.service.github;

import com.changescope.dto.WebhookResponse;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.ResourceNotFoundException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubWebhookServiceTest {

    private static final String SIGNATURE = "sha256=abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890";
    private static final String DELIVERY_ID = "delivery-service-test-1";

    @Mock
    private GitHubWebhookSignatureVerifier signatureVerifier;

    @Mock
    private GitHubWebhookDeliveryService deliveryService;

    @Mock
    private GitHubPullRequestEventProcessor pullRequestEventProcessor;

    private GitHubWebhookService webhookService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        webhookService = new GitHubWebhookService(
            signatureVerifier,
            objectMapper,
            deliveryService,
            pullRequestEventProcessor
        );
    }

    @Test
    void processWebhook_signatureVerificationFails_throwsAuthenticationExceptionAndNeverRegistersDelivery() {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(false);

        assertThatThrownBy(() -> webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID))
            .isInstanceOf(GitHubAuthenticationException.class)
            .hasMessageContaining("signature verification failed");

        verify(deliveryService, never()).registerDelivery(any(), any());
        verify(pullRequestEventProcessor, never()).processPullRequestEvent(any(), any());
    }

    @Test
    void processWebhook_duplicateDelivery_returnsDuplicateWithoutProcessingEvent() {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(false);

        WebhookResponse response = webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID);

        assertThat(response.status()).isEqualTo("duplicate");
        assertThat(response.duplicate()).isTrue();
        verify(pullRequestEventProcessor, never()).processPullRequestEvent(any(), any());
        verify(deliveryService, never()).markProcessed(any());
        verify(deliveryService, never()).markFailed(any(), any(Throwable.class));
    }

    @Test
    void processWebhook_successfulPullRequestEvent_marksProcessedAndReturnsAccepted() {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);
        when(pullRequestEventProcessor.processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID)))
            .thenReturn(WebhookResponse.accepted("pull_request", DELIVERY_ID, "PR analyzed"));

        WebhookResponse response = webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(pullRequestEventProcessor, times(1)).processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID));
        verify(deliveryService, times(1)).markProcessed(DELIVERY_ID);
        verify(deliveryService, never()).markFailed(any(), any(Throwable.class));
    }

    @Test
    void processWebhook_pullRequestMissingRepository_marksFailedAndRethrows() {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);

        ResourceNotFoundException notFound = new ResourceNotFoundException("Repository not found with owner 'octocat' and name 'repo'");
        when(pullRequestEventProcessor.processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID)))
            .thenThrow(notFound);

        assertThatThrownBy(() -> webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID))
            .isSameAs(notFound);

        verify(deliveryService, times(1)).markFailed(eq(DELIVERY_ID), eq(notFound));
        verify(deliveryService, never()).markProcessed(any());
    }

    @Test
    void processWebhook_pullRequestAnalysisFailure_marksFailedAndRethrows() {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);

        GitHubApiException apiException = new GitHubApiException(502, "BAD_GATEWAY", "GitHub API unavailable");
        when(pullRequestEventProcessor.processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID)))
            .thenThrow(apiException);

        assertThatThrownBy(() -> webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID))
            .isSameAs(apiException);

        verify(deliveryService, times(1)).markFailed(eq(DELIVERY_ID), eq(apiException));
        verify(deliveryService, never()).markProcessed(any());
    }

    @Test
    void processWebhook_unsupportedEvent_marksProcessedAndReturnsIgnored() {
        byte[] payload = "{\"ref\":\"refs/heads/main\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "push")).thenReturn(true);

        WebhookResponse response = webhookService.processWebhook(payload, SIGNATURE, "push", DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        verify(pullRequestEventProcessor, never()).processPullRequestEvent(any(), any());
        verify(deliveryService, times(1)).markProcessed(DELIVERY_ID);
        verify(deliveryService, never()).markFailed(any(), any(Throwable.class));
    }

    @Test
    void processWebhook_retryableDeliveryRetry_succeedsAndMarksProcessed() {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        // Delivery service permits retry by returning true (claimed retry)
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);
        when(pullRequestEventProcessor.processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID)))
            .thenReturn(WebhookResponse.accepted("pull_request", DELIVERY_ID, "PR analyzed on retry"));

        WebhookResponse response = webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(pullRequestEventProcessor).processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID));
        verify(deliveryService).markProcessed(DELIVERY_ID);
        verify(deliveryService, never()).markFailed(any(), any(Throwable.class));
    }

    @Test
    void processWebhook_failedRetry_marksFailedAgainAndRethrows() {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(payload, SIGNATURE)).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);

        GitHubApiException transientError = new GitHubApiException(503, "SERVICE_UNAVAILABLE", "Upstream 503");
        when(pullRequestEventProcessor.processPullRequestEvent(any(JsonNode.class), eq(DELIVERY_ID)))
            .thenThrow(transientError);

        assertThatThrownBy(() -> webhookService.processWebhook(payload, SIGNATURE, "pull_request", DELIVERY_ID))
            .isSameAs(transientError);

        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(deliveryService).markFailed(eq(DELIVERY_ID), eq(transientError));
        verify(deliveryService, never()).markProcessed(any());
    }
}
