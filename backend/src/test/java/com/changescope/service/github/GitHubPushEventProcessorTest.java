package com.changescope.service.github;

import com.changescope.dto.WebhookResponse;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.service.RepositoryIndexingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GitHubPushEventProcessorTest {

    private static final String DELIVERY_ID = "delivery-push-test-1";

    @Mock
    private RepositoryIndexingService indexingService;

    @Mock
    private RepositoryEntityRepository repositoryEntityRepository;

    private ObjectMapper objectMapper;
    private GitHubPushEventProcessor processor;
    private RepositoryEntity repo;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        // Synchronous executor for deterministic testing
        Executor directExecutor = Runnable::run;
        processor = new GitHubPushEventProcessor(indexingService, repositoryEntityRepository, directExecutor);

        repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
    }

    @Test
    @DisplayName("Default-branch push triggers asynchronous indexing and returns accepted")
    void processPushEvent_defaultBranch_triggersIndexingAndReturnsAccepted() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        WebhookResponse response = processor.processPushEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.event()).isEqualTo("push");
        assertThat(response.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(response.message()).contains("accepted for indexing");

        verify(indexingService).indexRepository(42L, "octocat", "hello-world", "6dcb09b5b57875f334f61aebed695e2e4193db5e", true);
    }

    @Test
    @DisplayName("Feature-branch push is ignored and does not trigger indexing")
    void processPushEvent_featureBranch_isIgnored() throws Exception {
        String json = """
            {
              "ref": "refs/heads/feature/login",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        WebhookResponse response = processor.processPushEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        assertThat(response.message()).contains("non-default ref");

        verify(indexingService, never()).indexRepository(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Deleted-branch push with all zero commit SHA is ignored")
    void processPushEvent_deletedBranchWithZeroSha_isIgnored() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "after": "0000000000000000000000000000000000000000",
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        WebhookResponse response = processor.processPushEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        assertThat(response.message()).contains("deleted ref");

        verify(repositoryEntityRepository, never()).findByOwnerAndName(any(), any());
        verify(indexingService, never()).indexRepository(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Deleted-branch push with deleted=true flag is ignored")
    void processPushEvent_deletedFlagTrue_isIgnored() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "deleted": true,
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        WebhookResponse response = processor.processPushEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        assertThat(response.message()).contains("deleted ref");

        verify(indexingService, never()).indexRepository(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Unregistered repository throws ResourceNotFoundException and never creates repository")
    void processPushEvent_unregisteredRepository_throwsResourceNotFound() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": {
                "name": "unknown-repo",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "unknown-repo"))
            .thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.processPushEvent(payload, DELIVERY_ID))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found with owner 'octocat' and name 'unknown-repo'");

        verify(repositoryEntityRepository, never()).save(any());
        verify(indexingService, never()).indexRepository(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Missing ref in payload throws IllegalArgumentException")
    void processPushEvent_missingRef_throwsIllegalArgumentException() throws Exception {
        String json = """
            {
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": { "name": "hello-world", "owner": { "login": "octocat" } }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        assertThatThrownBy(() -> processor.processPushEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing ref in push event payload");
    }

    @Test
    @DisplayName("Missing after commit SHA in payload throws IllegalArgumentException")
    void processPushEvent_missingAfter_throwsIllegalArgumentException() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "repository": { "name": "hello-world", "owner": { "login": "octocat" } }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        assertThatThrownBy(() -> processor.processPushEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing after commit SHA in push event payload");
    }

    @Test
    @DisplayName("Task rejection by executor throws RejectedExecutionException and does not accept task")
    void processPushEvent_executorRejection_throwsRejectedExecutionException() throws Exception {
        Executor rejectingExecutor = r -> {
            throw new java.util.concurrent.RejectedExecutionException("Executor queue is full");
        };
        GitHubPushEventProcessor rejectingProcessor = new GitHubPushEventProcessor(
            indexingService,
            repositoryEntityRepository,
            rejectingExecutor
        );

        String json = """
            {
              "ref": "refs/heads/main",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        assertThatThrownBy(() -> rejectingProcessor.processPushEvent(payload, DELIVERY_ID))
            .isInstanceOf(java.util.concurrent.RejectedExecutionException.class)
            .hasMessageContaining("queue capacity saturated or executor shutting down")
            .hasCauseInstanceOf(java.util.concurrent.RejectedExecutionException.class);

        verify(indexingService).recordTaskRejection(eq(42L), eq("octocat"), eq("hello-world"), eq("6dcb09b5b57875f334f61aebed695e2e4193db5e"), any());
        verify(indexingService, never()).indexRepository(anyLong(), anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("Asynchronous task failure catches error and safely marks snapshot failed")
    void processPushEvent_asynchronousFailure_marksSnapshotFailedSafely() throws Exception {
        String json = """
            {
              "ref": "refs/heads/main",
              "after": "6dcb09b5b57875f334f61aebed695e2e4193db5e",
              "repository": {
                "name": "hello-world",
                "owner": {
                  "login": "octocat"
                }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        when(indexingService.indexRepository(42L, "octocat", "hello-world", "6dcb09b5b57875f334f61aebed695e2e4193db5e", true))
            .thenThrow(new RuntimeException("GitHub API rate limit exceeded with token ghp_secret123456789"));

        WebhookResponse response = processor.processPushEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(indexingService).indexRepository(42L, "octocat", "hello-world", "6dcb09b5b57875f334f61aebed695e2e4193db5e", true);
        verify(indexingService).failSnapshotIfIndexing(eq(42L), eq("6dcb09b5b57875f334f61aebed695e2e4193db5e"), anyString(), any());
    }
}
