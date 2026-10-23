package com.changescope.service.github;

import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.WebhookResponse;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.service.PullRequestAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Set;

/**
 * Service for processing verified GitHub pull_request webhook events.
 * Extracts and validates pull request coordinates, verifies repository registration,
 * and triggers synchronous pull-request impact analysis.
 */
@Service
public class GitHubPullRequestEventProcessor {

    public static final Set<String> SUPPORTED_ACTIONS = Set.of("opened", "synchronize", "reopened");

    private final RepositoryEntityRepository repositoryEntityRepository;
    private final PullRequestAnalysisService pullRequestAnalysisService;
    private final PullRequestImpactService pullRequestImpactService;

    public GitHubPullRequestEventProcessor(
        RepositoryEntityRepository repositoryEntityRepository,
        PullRequestAnalysisService pullRequestAnalysisService,
        PullRequestImpactService pullRequestImpactService
    ) {
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.pullRequestAnalysisService = pullRequestAnalysisService;
        this.pullRequestImpactService = pullRequestImpactService;
    }

    /**
     * Processes a parsed pull_request webhook payload.
     *
     * @param payload    the parsed JSON payload root node
     * @param deliveryId the unique delivery ID associated with this webhook event
     * @return WebhookResponse indicating whether the event was analyzed (accepted) or ignored
     */
    public WebhookResponse processPullRequestEvent(JsonNode payload, String deliveryId) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Malformed pull_request payload");
        }

        JsonNode actionNode = payload.get("action");
        if (actionNode == null || actionNode.isNull() || actionNode.asText().isBlank()) {
            throw new IllegalArgumentException("Missing action in pull_request event payload");
        }

        String action = actionNode.asText().trim().toLowerCase();
        if (!SUPPORTED_ACTIONS.contains(action)) {
            return WebhookResponse.ignored("pull_request", deliveryId, "Pull request action '" + action + "' is ignored");
        }

        // 1. Extract and validate pull request number
        int number = payload.path("number").asInt(0);
        if (number <= 0) {
            number = payload.path("pull_request").path("number").asInt(0);
        }
        if (number <= 0) {
            throw new IllegalArgumentException("Missing or invalid pull request number in webhook payload");
        }

        // 2. Extract and validate head commit SHA (and optional base commit SHA)
        String headSha = payload.path("pull_request").path("head").path("sha").asText(null);
        if (headSha == null || headSha.isBlank()) {
            throw new IllegalArgumentException("Missing or invalid head commit SHA in pull_request payload");
        }

        String baseSha = payload.path("pull_request").path("base").path("sha").asText(null);
        if (baseSha != null && baseSha.isBlank()) {
            baseSha = null;
        }

        // 3. Extract and validate repository coordinates (owner and name)
        JsonNode repoNode = payload.path("repository");
        if (repoNode.isMissingNode() || repoNode.isNull() || !repoNode.isObject()) {
            throw new IllegalArgumentException("Missing repository object in pull_request payload");
        }

        String repoName = repoNode.path("name").asText(null);
        String owner = null;
        JsonNode ownerNode = repoNode.path("owner");
        if (!ownerNode.isMissingNode() && !ownerNode.isNull()) {
            if (ownerNode.hasNonNull("login")) {
                owner = ownerNode.path("login").asText(null);
            } else if (ownerNode.hasNonNull("name")) {
                owner = ownerNode.path("name").asText(null);
            }
        }

        if ((owner == null || owner.isBlank() || repoName == null || repoName.isBlank()) && repoNode.hasNonNull("full_name")) {
            String fullName = repoNode.path("full_name").asText();
            String[] parts = fullName.split("/", 2);
            if (parts.length == 2) {
                if (owner == null || owner.isBlank()) {
                    owner = parts[0];
                }
                if (repoName == null || repoName.isBlank()) {
                    repoName = parts[1];
                }
            }
        }

        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Missing repository owner in pull_request payload");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("Missing repository name in pull_request payload");
        }

        String cleanOwner = owner.trim();
        String cleanRepoName = repoName.trim();

        // 4. Resolve registered repository by owner/name (fail if not registered; never create automatically)
        RepositoryEntity repository = repositoryEntityRepository.findByOwnerAndName(cleanOwner, cleanRepoName)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Repository not found with owner '" + cleanOwner + "' and name '" + cleanRepoName + "'"
            ));

        // 5. Invoke pull-request impact-analysis workflow with exact webhook head SHA
        PullRequestAnalysisEntity analysisRecord = pullRequestAnalysisService.startAnalysis(repository.getId(), number, headSha);
        Long analysisId = analysisRecord.getId();

        PullRequestImpactResult result;
        try {
            result = (baseSha != null)
                ? pullRequestImpactService.analyzePullRequest(
                    repository.getOwner(),
                    repository.getName(),
                    number,
                    headSha,
                    baseSha
                )
                : pullRequestImpactService.analyzePullRequest(
                    repository.getOwner(),
                    repository.getName(),
                    number,
                    headSha
                );
        } catch (Throwable ex) {
            pullRequestAnalysisService.failAnalysis(analysisId, ex, Instant.now());
            throw ex;
        }

        PullRequestImpactResult completed = pullRequestAnalysisService.completeAnalysis(
            analysisId,
            result,
            Instant.now()
        );

        return WebhookResponse.accepted(
            "pull_request",
            deliveryId,
            "Pull request #" + number + " (" + action + ") analyzed successfully (analysisId=" + completed.analysisId() + ")"
        );
    }
}
