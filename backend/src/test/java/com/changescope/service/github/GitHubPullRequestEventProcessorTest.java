package com.changescope.service.github;

import com.changescope.dto.ImpactReport;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.WebhookResponse;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.service.PullRequestAnalysisService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubPullRequestEventProcessorTest {

    private static final String DELIVERY_ID = "delivery-12345";
    private static final String OWNER = "octocat";
    private static final String REPO = "ChangeScope";
    private static final int PR_NUMBER = 42;
    private static final String HEAD_SHA = "6dcb09b5b57875f334f61aebed695e2e4193db5e";

    @Mock
    private RepositoryEntityRepository repositoryEntityRepository;

    @Mock
    private PullRequestAnalysisService pullRequestAnalysisService;

    @Mock
    private PullRequestImpactService pullRequestImpactService;

    private GitHubPullRequestEventProcessor processor;
    private ObjectMapper objectMapper;
    private RepositoryEntity repositoryEntity;

    @BeforeEach
    void setUp() {
        processor = new GitHubPullRequestEventProcessor(
            repositoryEntityRepository,
            pullRequestAnalysisService,
            pullRequestImpactService
        );
        objectMapper = new ObjectMapper();

        repositoryEntity = new RepositoryEntity(OWNER, REPO, "main", 123456L, 789L);
        repositoryEntity.setId(10L);
    }

    private JsonNode createPayload(String action, int number, String headSha, String owner, String repoName) throws Exception {
        String json = """
            {
              "action": "%s",
              "number": %d,
              "pull_request": {
                "number": %d,
                "head": {
                  "sha": "%s"
                }
              },
              "repository": {
                "name": "%s",
                "owner": {
                  "login": "%s"
                }
              }
            }
            """.formatted(action, number, number, headSha != null ? headSha : "", repoName != null ? repoName : "", owner != null ? owner : "");
        return objectMapper.readTree(json);
    }

    private PullRequestImpactResult createMockImpactResult(Long analysisId) {
        PullRequestMetadata metadata = new PullRequestMetadata(
            OWNER,
            REPO,
            PR_NUMBER,
            "Add feature",
            "base_sha_123",
            HEAD_SHA,
            PullRequestState.OPEN
        );
        ImpactReport report = new ImpactReport(List.of(), List.of(), List.of(), List.of());
        PullRequestImpactResult base = new PullRequestImpactResult(metadata, report, List.of(), List.of());
        return analysisId != null ? base.withAnalysisId(analysisId) : base;
    }

    @Test
    void processPullRequestEvent_supportedActionOpened_invokesAnalysisOnce() throws Exception {
        JsonNode payload = createPayload("opened", PR_NUMBER, HEAD_SHA, OWNER, REPO);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, HEAD_SHA, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(99L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, HEAD_SHA)).thenReturn(analysisEntity);

        PullRequestImpactResult rawResult = createMockImpactResult(null);
        PullRequestImpactResult completedResult = createMockImpactResult(99L);
        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA)).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(99L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        assertThat(response.event()).isEqualTo("pull_request");
        assertThat(response.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(response.message()).contains("#42", "opened", "analysisId=99");

        verify(pullRequestImpactService, times(1)).analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA);
        verify(pullRequestAnalysisService, times(1)).startAnalysis(10L, PR_NUMBER, HEAD_SHA);
        verify(pullRequestAnalysisService, times(1)).completeAnalysis(eq(99L), eq(rawResult), any());
        verify(pullRequestAnalysisService, never()).failAnalysis(any(), any(), any());
    }

    @Test
    void processPullRequestEvent_supportedActionSynchronize_invokesAnalysisOnce() throws Exception {
        JsonNode payload = createPayload("synchronize", PR_NUMBER, HEAD_SHA, OWNER, REPO);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, HEAD_SHA, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(100L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, HEAD_SHA)).thenReturn(analysisEntity);

        PullRequestImpactResult rawResult = createMockImpactResult(null);
        PullRequestImpactResult completedResult = createMockImpactResult(100L);
        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA)).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(100L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(pullRequestImpactService, times(1)).analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA);
    }

    @Test
    void processPullRequestEvent_supportedActionReopened_invokesAnalysisOnce() throws Exception {
        JsonNode payload = createPayload("reopened", PR_NUMBER, HEAD_SHA, OWNER, REPO);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, HEAD_SHA, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(101L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, HEAD_SHA)).thenReturn(analysisEntity);

        PullRequestImpactResult rawResult = createMockImpactResult(null);
        PullRequestImpactResult completedResult = createMockImpactResult(101L);
        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA)).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(101L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(pullRequestImpactService, times(1)).analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA);
    }

    @Test
    void processPullRequestEvent_unsupportedActionClosed_doesNotInvokeAnalysis() throws Exception {
        JsonNode payload = createPayload("closed", PR_NUMBER, HEAD_SHA, OWNER, REPO);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        assertThat(response.event()).isEqualTo("pull_request");
        assertThat(response.deliveryId()).isEqualTo(DELIVERY_ID);
        assertThat(response.message()).contains("closed", "ignored");

        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
        verify(pullRequestAnalysisService, never()).startAnalysis(any(), anyInt());
        verify(repositoryEntityRepository, never()).findByOwnerAndName(any(), any());
    }

    @Test
    void processPullRequestEvent_unsupportedActionLabeled_doesNotInvokeAnalysis() throws Exception {
        JsonNode payload = createPayload("labeled", PR_NUMBER, HEAD_SHA, OWNER, REPO);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("ignored");
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void processPullRequestEvent_missingRepository_throwsResourceNotFoundException() throws Exception {
        JsonNode payload = createPayload("opened", PR_NUMBER, HEAD_SHA, OWNER, REPO);
        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found with owner 'octocat' and name 'ChangeScope'");

        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
        verify(pullRequestAnalysisService, never()).startAnalysis(any(), anyInt());
    }

    @Test
    void processPullRequestEvent_analysisFailure_marksAnalysisFailedAndRethrows() throws Exception {
        JsonNode payload = createPayload("opened", PR_NUMBER, HEAD_SHA, OWNER, REPO);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, HEAD_SHA, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(105L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, HEAD_SHA)).thenReturn(analysisEntity);

        GitHubApiException apiException = new GitHubApiException(502, "BAD_GATEWAY", "GitHub API unavailable");
        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, HEAD_SHA)).thenThrow(apiException);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isSameAs(apiException);

        verify(pullRequestAnalysisService, times(1)).failAnalysis(eq(105L), eq(apiException), any());
        verify(pullRequestAnalysisService, never()).completeAnalysis(any(), any(), any());
    }

    @Test
    void processPullRequestEvent_missingAction_throwsIllegalArgumentException() throws Exception {
        String json = "{\"number\": 42}";
        JsonNode payload = objectMapper.readTree(json);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing action in pull_request event payload");
    }

    @Test
    void processPullRequestEvent_missingHeadSha_throwsIllegalArgumentException() throws Exception {
        JsonNode payload = createPayload("opened", PR_NUMBER, "", OWNER, REPO);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing or invalid head commit SHA");
    }

    @Test
    void processPullRequestEvent_missingPullNumber_throwsIllegalArgumentException() throws Exception {
        String json = """
            {
              "action": "opened",
              "number": 0,
              "pull_request": {
                "head": { "sha": "abc12345" }
              },
              "repository": { "name": "ChangeScope", "owner": { "login": "octocat" } }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing or invalid pull request number");
    }

    @Test
    void processPullRequestEvent_missingRepository_throwsIllegalArgumentWhenPayloadHasNoRepo() throws Exception {
        String json = """
            {
              "action": "opened",
              "number": 42,
              "pull_request": {
                "head": { "sha": "abc12345" }
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing repository object");
    }

    @Test
    void processPullRequestEvent_resolvesOwnerAndNameFromFullName() throws Exception {
        String json = """
            {
              "action": "opened",
              "number": 42,
              "pull_request": {
                "head": { "sha": "abc12345" }
              },
              "repository": {
                "full_name": "octocat/ChangeScope"
              }
            }
            """;
        JsonNode payload = objectMapper.readTree(json);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, "abc12345", "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(110L);

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "ChangeScope")).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, 42, "abc12345")).thenReturn(analysisEntity);

        PullRequestImpactResult rawResult = createMockImpactResult(null);
        PullRequestImpactResult completedResult = createMockImpactResult(110L);
        when(pullRequestImpactService.analyzePullRequest("octocat", "ChangeScope", 42, "abc12345")).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(110L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);
        assertThat(response.status()).isEqualTo("accepted");
    }

    @Test
    void processPullRequestEvent_passesWebhookHeadShaToAnalysisAndPreservesItEvenIfLivePrDiffers() throws Exception {
        String webhookHeadSha = "webhook-commit-sha-12345678901234567890";
        JsonNode payload = createPayload("opened", PR_NUMBER, webhookHeadSha, OWNER, REPO);
        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, webhookHeadSha, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(200L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, webhookHeadSha)).thenReturn(analysisEntity);

        PullRequestMetadata metadata = new PullRequestMetadata(OWNER, REPO, PR_NUMBER, "PR Title", "base_sha_123", webhookHeadSha, PullRequestState.OPEN);
        ImpactReport report = new ImpactReport(List.of(), List.of(), List.of(), List.of());
        PullRequestImpactResult rawResult = new PullRequestImpactResult(metadata, report, List.of(), List.of());
        PullRequestImpactResult completedResult = rawResult.withAnalysisId(200L);

        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, webhookHeadSha)).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(200L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(pullRequestAnalysisService).startAnalysis(10L, PR_NUMBER, webhookHeadSha);
        verify(pullRequestImpactService).analyzePullRequest(OWNER, REPO, PR_NUMBER, webhookHeadSha);
        verify(pullRequestAnalysisService).completeAnalysis(eq(200L), eq(rawResult), any());
    }

    @Test
    void processPullRequestEvent_withBaseSha_passesRevisionsToAnalysis() throws Exception {
        String webhookHeadSha = "head-sha-1111";
        String webhookBaseSha = "base-sha-2222";
        String json = """
            {
              "action": "synchronize",
              "number": 42,
              "pull_request": {
                "number": 42,
                "head": { "sha": "%s" },
                "base": { "sha": "%s" }
              },
              "repository": {
                "name": "ChangeScope",
                "owner": { "login": "octocat" }
              }
            }
            """.formatted(webhookHeadSha, webhookBaseSha);
        JsonNode payload = objectMapper.readTree(json);

        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, webhookHeadSha, webhookBaseSha, AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(300L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, webhookHeadSha)).thenReturn(analysisEntity);

        PullRequestMetadata metadata = new PullRequestMetadata(OWNER, REPO, PR_NUMBER, "PR Title", webhookBaseSha, webhookHeadSha, PullRequestState.OPEN);
        ImpactReport report = new ImpactReport(List.of(), List.of(), List.of(), List.of());
        PullRequestImpactResult rawResult = new PullRequestImpactResult(metadata, report, List.of(), List.of());
        PullRequestImpactResult completedResult = rawResult.withAnalysisId(300L);

        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, webhookHeadSha, webhookBaseSha)).thenReturn(rawResult);
        when(pullRequestAnalysisService.completeAnalysis(eq(300L), eq(rawResult), any())).thenReturn(completedResult);

        WebhookResponse response = processor.processPullRequestEvent(payload, DELIVERY_ID);

        assertThat(response.status()).isEqualTo("accepted");
        verify(pullRequestImpactService).analyzePullRequest(OWNER, REPO, PR_NUMBER, webhookHeadSha, webhookBaseSha);
        verify(pullRequestAnalysisService).completeAnalysis(eq(300L), eq(rawResult), any());
    }

    @Test
    void processPullRequestEvent_historicalRevisionUnsupported_marksAnalysisFailedAndRethrows() throws Exception {
        String historicalHeadSha = "historical-head-1111";
        JsonNode payload = createPayload("synchronize", PR_NUMBER, historicalHeadSha, OWNER, REPO);

        PullRequestAnalysisEntity analysisEntity = new PullRequestAnalysisEntity(
            repositoryEntity, PR_NUMBER, historicalHeadSha, "base_sha_123", AnalysisStatus.RUNNING, null, Instant.now(), null, null
        );
        analysisEntity.setId(301L);

        when(repositoryEntityRepository.findByOwnerAndName(OWNER, REPO)).thenReturn(Optional.of(repositoryEntity));
        when(pullRequestAnalysisService.startAnalysis(10L, PR_NUMBER, historicalHeadSha)).thenReturn(analysisEntity);

        GitHubApiException mismatchEx = new GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED", "Pull request head revision has advanced");
        when(pullRequestImpactService.analyzePullRequest(OWNER, REPO, PR_NUMBER, historicalHeadSha)).thenThrow(mismatchEx);

        assertThatThrownBy(() -> processor.processPullRequestEvent(payload, DELIVERY_ID))
            .isSameAs(mismatchEx);

        verify(pullRequestAnalysisService).failAnalysis(eq(301L), eq(mismatchEx), any());
        verify(pullRequestAnalysisService, never()).completeAnalysis(any(), any(), any());
    }
}
