package com.changescope.service.github;

import com.changescope.dto.WebhookResponse;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.service.RepositoryIndexingService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.changescope.config.IndexingExecutorConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Service for processing verified GitHub push webhook events.
 * Validates branch ref and commit SHA, ensures repository is registered,
 * and asynchronously dispatches indexing for default-branch pushes.
 */
@Service
public class GitHubPushEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(GitHubPushEventProcessor.class);

    private final RepositoryIndexingService indexingService;
    private final RepositoryEntityRepository repositoryEntityRepository;
    private final Executor taskExecutor;

    public GitHubPushEventProcessor(
        RepositoryIndexingService indexingService,
        RepositoryEntityRepository repositoryEntityRepository
    ) {
        this(indexingService, repositoryEntityRepository, createDefaultExecutor());
    }

    @Autowired
    public GitHubPushEventProcessor(
        RepositoryIndexingService indexingService,
        RepositoryEntityRepository repositoryEntityRepository,
        @Qualifier(IndexingExecutorConfig.EXECUTOR_BEAN_NAME) Executor taskExecutor
    ) {
        this.indexingService = indexingService;
        this.repositoryEntityRepository = repositoryEntityRepository;
        this.taskExecutor = (taskExecutor != null) ? taskExecutor : createDefaultExecutor();
    }

    private static Executor createDefaultExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("indexing-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /**
     * Processes a parsed push webhook payload.
     *
     * @param payload    the parsed JSON payload root node
     * @param deliveryId the unique delivery ID associated with this webhook event
     * @return WebhookResponse indicating whether the event was accepted or ignored
     */
    public WebhookResponse processPushEvent(JsonNode payload, String deliveryId) {
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("Malformed push payload");
        }

        // 1. Extract and validate git ref
        String ref = payload.path("ref").asText(null);
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("Missing ref in push event payload");
        }

        // 2. Extract and validate after commit SHA
        String after = payload.path("after").asText(null);
        if (after == null || after.isBlank()) {
            throw new IllegalArgumentException("Missing after commit SHA in push event payload");
        }

        String cleanRef = ref.trim();
        String cleanAfter = after.trim();

        // 3. Ignore deleted-branch pushes where after is all zeros or deleted flag is set
        boolean isDeleted = payload.path("deleted").asBoolean(false) || cleanAfter.matches("^0+$");
        if (isDeleted) {
            return WebhookResponse.ignored("push", deliveryId, "Push event for deleted ref '" + cleanRef + "' is ignored");
        }

        // 4. Extract repository coordinates
        JsonNode repoNode = payload.path("repository");
        if (repoNode.isMissingNode() || repoNode.isNull() || !repoNode.isObject()) {
            throw new IllegalArgumentException("Missing repository object in push event payload");
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
            throw new IllegalArgumentException("Missing repository owner in push payload");
        }
        if (repoName == null || repoName.isBlank()) {
            throw new IllegalArgumentException("Missing repository name in push payload");
        }

        String cleanOwner = owner.trim();
        String cleanRepoName = repoName.trim();

        // 5. Resolve registered repository by owner/name (fail if not registered; never create automatically)
        RepositoryEntity repository = repositoryEntityRepository.findByOwnerAndName(cleanOwner, cleanRepoName)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Repository not found with owner '" + cleanOwner + "' and name '" + cleanRepoName + "'"
            ));

        // 6. Only process default branch
        String expectedRef = "refs/heads/" + repository.getDefaultBranch();
        if (!expectedRef.equals(cleanRef)) {
            return WebhookResponse.ignored("push", deliveryId, "Push event for non-default ref '" + cleanRef + "' is ignored");
        }

        // 7. Dispatch indexing asynchronously without blocking webhook delivery
        Long repositoryId = repository.getId();
        try {
            taskExecutor.execute(() -> {
                try {
                    indexingService.indexRepository(repositoryId, cleanOwner, cleanRepoName, cleanAfter, true);
                } catch (Throwable ex) {
                    String sanitized = GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage());
                    log.error("Asynchronous indexing failed for repositoryId {} at commit {}: {}",
                        repositoryId, cleanAfter, sanitized);
                    try {
                        indexingService.failSnapshotIfIndexing(repositoryId, cleanAfter, "Asynchronous indexing failed: " + sanitized, ex);
                    } catch (Throwable failEx) {
                        log.error("Failed to transition snapshot to FAILED for repositoryId {} at commit {}: {}",
                            repositoryId, cleanAfter, GitHubWebhookDeliveryService.sanitizeFailureSummary(failEx.getMessage()));
                    }
                }
            });
        } catch (RejectedExecutionException ex) {
            String sanitized = GitHubWebhookDeliveryService.sanitizeFailureSummary(ex.getMessage());
            log.error("Indexing task rejected by executor for repositoryId {} at commit {}: {}",
                repositoryId, cleanAfter, sanitized);
            try {
                indexingService.recordTaskRejection(repositoryId, cleanOwner, cleanRepoName, cleanAfter, ex);
            } catch (Throwable recEx) {
                log.error("Failed to record task rejection for repositoryId {} at commit {}: {}",
                    repositoryId, cleanAfter, GitHubWebhookDeliveryService.sanitizeFailureSummary(recEx.getMessage()));
            }
            throw new RejectedExecutionException("Indexing task rejected by executor for " + cleanOwner + "/" + cleanRepoName + "@" + cleanAfter + ": queue capacity saturated or executor shutting down", ex);
        }

        return WebhookResponse.accepted(
            "push",
            deliveryId,
            "Push event for " + cleanOwner + "/" + cleanRepoName + "@" + cleanAfter + " on default branch accepted for indexing"
        );
    }
}
