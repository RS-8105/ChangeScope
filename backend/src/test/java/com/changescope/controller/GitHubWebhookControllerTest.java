package com.changescope.controller;

import com.changescope.dto.WebhookResponse;
import com.changescope.exception.GlobalExceptionHandler;
import com.changescope.service.github.GitHubPullRequestEventProcessor;
import com.changescope.service.github.GitHubWebhookDeliveryService;
import com.changescope.service.github.GitHubWebhookService;
import com.changescope.service.github.GitHubWebhookSignatureVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GitHubWebhookController.class)
@Import({GlobalExceptionHandler.class, GitHubWebhookService.class})
class GitHubWebhookControllerTest {

    private static final String VALID_SIGNATURE = "sha256=1234567890abcdef1234567890abcdef1234567890abcdef1234567890abcdef";
    private static final String INVALID_SIGNATURE = "sha256=badsignature0000000000000000000000000000000000000000000000000000";
    private static final String DELIVERY_ID = "72d3162e-cc78-11e3-81ab-4c9367dc09d7";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private GitHubWebhookSignatureVerifier signatureVerifier;

    @MockBean
    private GitHubWebhookDeliveryService deliveryService;

    @MockBean
    private GitHubPullRequestEventProcessor pullRequestEventProcessor;

    @SpyBean
    private ObjectMapper objectMapper;

    @Test
    void handleWebhook_validPingEvent_returns200Accepted() throws Exception {
        byte[] payload = "{\"zen\":\"Design for failure.\",\"hook_id\":123}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "ping")).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "ping")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.event").value("ping"))
            .andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
            .andExpect(jsonPath("$.duplicate").value(false))
            .andExpect(jsonPath("$.message").exists());

        verify(signatureVerifier).verifySignature(any(byte[].class), eq(VALID_SIGNATURE));
        verify(objectMapper).readTree(any(byte[].class));
        verify(deliveryService).registerDelivery(DELIVERY_ID, "ping");
        verify(deliveryService).markProcessed(DELIVERY_ID);
    }

    @Test
    void handleWebhook_duplicateDelivery_returns200DuplicateWithoutReprocessing() throws Exception {
        byte[] payload = "{\"action\":\"opened\",\"number\":42}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        // Delivery registration returns false, indicating duplicate
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("duplicate"))
            .andExpect(jsonPath("$.event").value("pull_request"))
            .andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
            .andExpect(jsonPath("$.duplicate").value(true))
            .andExpect(jsonPath("$.message").value("Duplicate delivery ignored"));

        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(deliveryService, never()).markProcessed(any());
        verify(pullRequestEventProcessor, never()).processPullRequestEvent(any(), any());
    }

    @Test
    void handleWebhook_validPullRequestEvent_returns200Accepted() throws Exception {
        byte[] payload = "{\"action\":\"opened\",\"number\":42,\"pull_request\":{\"id\":101}}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);
        when(pullRequestEventProcessor.processPullRequestEvent(any(), eq(DELIVERY_ID)))
            .thenReturn(WebhookResponse.accepted("pull_request", DELIVERY_ID, "Pull request #42 (opened) analyzed successfully"));

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("accepted"))
            .andExpect(jsonPath("$.event").value("pull_request"))
            .andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
            .andExpect(jsonPath("$.duplicate").value(false));

        verify(signatureVerifier).verifySignature(any(byte[].class), eq(VALID_SIGNATURE));
        verify(objectMapper).readTree(any(byte[].class));
        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(pullRequestEventProcessor).processPullRequestEvent(any(), eq(DELIVERY_ID));
        verify(deliveryService).markProcessed(DELIVERY_ID);
    }

    @Test
    void handleWebhook_validUnsupportedEvent_returns202Ignored() throws Exception {
        byte[] payload = "{\"ref\":\"refs/heads/main\",\"commits\":[]}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "push")).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "push")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.status").value("ignored"))
            .andExpect(jsonPath("$.event").value("push"))
            .andExpect(jsonPath("$.deliveryId").value(DELIVERY_ID))
            .andExpect(jsonPath("$.duplicate").value(false))
            .andExpect(jsonPath("$.message").value("Event 'push' is currently ignored"));

        verify(signatureVerifier).verifySignature(any(byte[].class), eq(VALID_SIGNATURE));
        verify(objectMapper).readTree(any(byte[].class));
        verify(deliveryService).registerDelivery(DELIVERY_ID, "push");
        verify(deliveryService).markProcessed(DELIVERY_ID);
    }

    @Test
    void handleWebhook_invalidSignature_returns401AndNeverParsesJsonOrAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(INVALID_SIGNATURE))).thenReturn(false);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, INVALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.message").value("GitHub webhook signature verification failed"));

        // Confirm invalid signatures never reach JSON parsing or delivery database registration
        verify(signatureVerifier).verifySignature(any(byte[].class), eq(INVALID_SIGNATURE));
        verify(objectMapper, never()).readTree(any(byte[].class));
        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_missingSignature_returns401AndNeverParsesJsonOrAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.message").value("GitHub webhook signature verification failed"));

        // Confirm missing signature never calls signatureVerifier, JSON parsing, or delivery service
        verify(signatureVerifier, never()).verifySignature(any(), any());
        verify(objectMapper, never()).readTree(any(byte[].class));
        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_missingEventHeader_returns400AndNeverAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Missing or blank X-GitHub-Event header"));

        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_blankEventHeader_returns400AndNeverAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "   ")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Missing or blank X-GitHub-Event header"));

        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_missingDeliveryHeader_returns400AndNeverAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Missing or blank X-GitHub-Delivery header"));

        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_blankDeliveryHeader_returns400AndNeverAccessesDatabase() throws Exception {
        byte[] payload = "{\"action\":\"opened\"}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", "   "))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Missing or blank X-GitHub-Delivery header"));

        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_malformedJsonWithValidSignature_returns400AndNeverRegistersDelivery() throws Exception {
        byte[] malformedPayload = "{not a valid json string".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(malformedPayload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("Malformed JSON webhook payload"));

        // Verify JSON parsing was attempted because signature was valid, but delivery was never registered
        verify(objectMapper).readTree(any(byte[].class));
        verify(deliveryService, never()).registerDelivery(any(), any());
    }

    @Test
    void handleWebhook_pullRequestMissingRepository_returns404AndMarksDeliveryFailed() throws Exception {
        byte[] payload = "{\"action\":\"opened\",\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abc\"}},\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}}}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);
        when(pullRequestEventProcessor.processPullRequestEvent(any(), eq(DELIVERY_ID)))
            .thenThrow(new com.changescope.exception.ResourceNotFoundException("Repository not found with owner 'owner' and name 'repo'"));

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value("Repository not found with owner 'owner' and name 'repo'"));

        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(pullRequestEventProcessor).processPullRequestEvent(any(), eq(DELIVERY_ID));
        verify(deliveryService).markFailed(eq(DELIVERY_ID), any(Throwable.class));
        verify(deliveryService, never()).markProcessed(any());
    }

    @Test
    void handleWebhook_pullRequestAnalysisFailure_returnsErrorAndMarksDeliveryFailed() throws Exception {
        byte[] payload = "{\"action\":\"opened\",\"number\":42,\"pull_request\":{\"head\":{\"sha\":\"abc\"}},\"repository\":{\"name\":\"repo\",\"owner\":{\"login\":\"owner\"}}}".getBytes(StandardCharsets.UTF_8);
        when(signatureVerifier.verifySignature(any(byte[].class), eq(VALID_SIGNATURE))).thenReturn(true);
        when(deliveryService.registerDelivery(DELIVERY_ID, "pull_request")).thenReturn(true);
        when(pullRequestEventProcessor.processPullRequestEvent(any(), eq(DELIVERY_ID)))
            .thenThrow(new com.changescope.exception.GitHubApiException(502, "BAD_GATEWAY", "GitHub API unreachable"));

        mockMvc.perform(post("/api/v1/webhooks/github")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header(GitHubWebhookSignatureVerifier.SIGNATURE_HEADER, VALID_SIGNATURE)
                .header("X-GitHub-Event", "pull_request")
                .header("X-GitHub-Delivery", DELIVERY_ID))
            .andExpect(status().isBadGateway())
            .andExpect(jsonPath("$.error").value("BAD_GATEWAY"))
            .andExpect(jsonPath("$.message").value("GitHub API unreachable"));

        verify(deliveryService).registerDelivery(DELIVERY_ID, "pull_request");
        verify(pullRequestEventProcessor).processPullRequestEvent(any(), eq(DELIVERY_ID));
        verify(deliveryService).markFailed(eq(DELIVERY_ID), any(Throwable.class));
        verify(deliveryService, never()).markProcessed(any());
    }
}
