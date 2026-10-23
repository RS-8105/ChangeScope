package com.changescope.controller;

import com.changescope.dto.WebhookResponse;
import com.changescope.service.github.GitHubWebhookService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for receiving and acknowledging GitHub webhook deliveries.
 */
@RestController
@RequestMapping("/api/v1/webhooks/github")
public class GitHubWebhookController {

    private final GitHubWebhookService webhookService;

    public GitHubWebhookController(GitHubWebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    public ResponseEntity<WebhookResponse> handleWebhook(
        @RequestBody(required = false) byte[] payload,
        @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
        @RequestHeader(value = "X-GitHub-Event", required = false) String event,
        @RequestHeader(value = "X-GitHub-Delivery", required = false) String delivery
    ) {
        WebhookResponse response = webhookService.processWebhook(payload, signature, event, delivery);
        if ("accepted".equals(response.status()) || "duplicate".equals(response.status())) {
            return ResponseEntity.ok(response);
        } else {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        }
    }
}
