package com.changescope.controller;

import com.changescope.dto.AnalysisCoverage;
import com.changescope.dto.AnalysisSummaryResponse;
import com.changescope.dto.ChangedTypeDetection;
import com.changescope.dto.CoverageScope;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.RepositoryResponse;
import com.changescope.dto.TypeChangeEvidence;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubAuthenticationException;
import com.changescope.exception.GitHubRateLimitException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.exception.GlobalExceptionHandler;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.service.PullRequestAnalysisService;
import com.changescope.service.RepositoryService;
import com.changescope.service.github.PullRequestImpactService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RepositoryController.class)
@Import(GlobalExceptionHandler.class)
class PullRequestAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private RepositoryService repositoryService;

    @MockBean
    private PullRequestImpactService pullRequestImpactService;

    @MockBean
    private PullRequestAnalysisService pullRequestAnalysisService;

    private PullRequestAnalysisEntity runningEntity;

    @BeforeEach
    void setUp() {
        runningEntity = new PullRequestAnalysisEntity();
        runningEntity.setId(100L);
        when(pullRequestAnalysisService.startAnalysis(any(), anyInt())).thenReturn(runningEntity);
    }

    @Test
    void analyzePullRequest_success_returns200WithStructuredResultAndAnalysisId() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);

        PullRequestMetadata prMetadata = new PullRequestMetadata(
            "changescope-org", "order-service", 42, "Refactor payment service",
            "base123", "head456", PullRequestState.OPEN
        );
        AnalysisCoverage coverage = AnalysisCoverage.boundedPartial(
            "base123",
            12,
            8,
            false,
            List.of("Targeted discovery is bounded to candidate naming and package heuristics; full repository indexing was not performed")
        );
        ImpactReport impactReport = new ImpactReport(
            List.of("com.changescope.service.PaymentService"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            coverage
        );
        ChangedTypeDetection detection = new ChangedTypeDetection(
            "com.changescope.service.PaymentService",
            "src/main/java/com/changescope/service/PaymentService.java",
            TypeChangeEvidence.PATCH_LINE_MATCH,
            "Matched lines 15-20"
        );
        List<String> warnings = List.of("Warning: 1 file had no patch");

        PullRequestImpactResult result = new PullRequestImpactResult(
            prMetadata,
            impactReport,
            warnings,
            List.of(detection)
        );

        when(pullRequestImpactService.analyzePullRequest(eq("changescope-org"), eq("order-service"), eq(42)))
            .thenReturn(result);

        when(pullRequestAnalysisService.completeAnalysis(eq(100L), any(), any()))
            .thenAnswer(inv -> {
                PullRequestImpactResult r = inv.getArgument(1);
                return r.withAnalysisId(100L);
            });

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.analysisId").value(100))
            .andExpect(jsonPath("$.pullRequest.owner").value("changescope-org"))
            .andExpect(jsonPath("$.pullRequest.repo").value("order-service"))
            .andExpect(jsonPath("$.pullRequest.number").value(42))
            .andExpect(jsonPath("$.pullRequest.title").value("Refactor payment service"))
            .andExpect(jsonPath("$.pullRequest.state").value("OPEN"))
            .andExpect(jsonPath("$.impactReport.totalChangedTypes").value(1))
            .andExpect(jsonPath("$.impactReport.changedTypes[0]").value("com.changescope.service.PaymentService"))
            .andExpect(jsonPath("$.impactReport.coverage.scope").value("PARTIAL_INDEX_BOUNDED"))
            .andExpect(jsonPath("$.impactReport.coverage.baseCommitSha").value("base123"))
            .andExpect(jsonPath("$.impactReport.coverage.productionFilesDiscovered").value(12))
            .andExpect(jsonPath("$.impactReport.coverage.productionFilesIncluded").value(8))
            .andExpect(jsonPath("$.impactReport.coverage.isTruncatedOrCapped").value(false))
            .andExpect(jsonPath("$.impactReport.coverage.isComplete").value(false))
            .andExpect(jsonPath("$.impactReport.coverage.incompleteReasons", hasSize(1)))
            .andExpect(jsonPath("$.impactReport.coverage.incompleteReasons[0]").value(
                "Targeted discovery is bounded to candidate naming and package heuristics; full repository indexing was not performed"
            ))
            .andExpect(jsonPath("$.changedTypeDetections", hasSize(1)))
            .andExpect(jsonPath("$.changedTypeDetections[0].fullyQualifiedName").value("com.changescope.service.PaymentService"))
            .andExpect(jsonPath("$.changedTypeDetections[0].evidence").value("PATCH_LINE_MATCH"))
            .andExpect(jsonPath("$.extractionWarnings", hasSize(1)))
            .andExpect(jsonPath("$.extractionWarnings[0]").value("Warning: 1 file had no patch"));

        verify(repositoryService).getRepositoryById(1L);
        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestImpactService).analyzePullRequest("changescope-org", "order-service", 42);
        verify(pullRequestAnalysisService).completeAnalysis(eq(100L), any(), any());
        verify(pullRequestAnalysisService, never()).failAnalysis(any(), any(), any());
    }

    @Test
    void analyzePullRequest_gitHubResourceNotFound_recordsFailureAndReturns404() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubResourceNotFoundException("Resource not found on GitHub (HTTP 404): Pull Request changescope-org/order-service#42"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value(containsString("Resource not found on GitHub")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubResourceNotFoundException.class), any());
        verify(pullRequestAnalysisService, never()).completeAnalysis(any(), any(), any());
    }

    @Test
    void analyzePullRequest_gitHubRateLimitExceeded_recordsFailureAndReturns429() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubRateLimitException("GitHub API rate limit exceeded (HTTP 429)"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.status").value(429))
            .andExpect(jsonPath("$.error").value("RATE_LIMIT_EXCEEDED"))
            .andExpect(jsonPath("$.message").value(containsString("rate limit exceeded")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubRateLimitException.class), any());
    }

    @Test
    void analyzePullRequest_gitHubAuthenticationFailed_recordsFailureAndReturns401() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubAuthenticationException(401, "GitHub API authentication failed (HTTP 401). Verify configured credentials."));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.message").value(containsString("authentication failed")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubAuthenticationException.class), any());
    }

    @Test
    void analyzePullRequest_persistenceFailure_doesNotReturnSuccess() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);

        PullRequestMetadata pr = new PullRequestMetadata("changescope-org", "order-service", 42, "PR", "b", "h", PullRequestState.OPEN);
        ImpactReport report = new ImpactReport(List.of("com.example.Order"), List.of(), List.of(), List.of());
        PullRequestImpactResult result = new PullRequestImpactResult(pr, report, List.of(), List.of());

        when(pullRequestImpactService.analyzePullRequest(any(), any(), anyInt())).thenReturn(result);
        when(pullRequestAnalysisService.completeAnalysis(eq(100L), any(), any()))
            .thenThrow(new IllegalStateException("Failed to serialize pull request impact result to JSON snapshot"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("INTERNAL_SERVER_ERROR"));
    }

    @Test
    void getAnalyses_success_returns200WithPaginatedHistoryMetadata() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);

        Instant now = Instant.now();
        List<AnalysisSummaryResponse> content = List.of(
            new AnalysisSummaryResponse(10L, 1L, 42, "head42", "base42", AnalysisStatus.COMPLETED, now.minusSeconds(10), now),
            new AnalysisSummaryResponse(9L, 1L, 41, "head41", "base41", AnalysisStatus.FAILED, now.minusSeconds(100), now.minusSeconds(90))
        );
        Page<AnalysisSummaryResponse> pageResult = new PageImpl<>(content, PageRequest.of(0, 20), 2);

        when(pullRequestAnalysisService.getHistoricalAnalyses(1L, 0, 20)).thenReturn(pageResult);

        mockMvc.perform(get("/api/v1/repositories/1/analyses?page=0&size=20"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content", hasSize(2)))
            .andExpect(jsonPath("$.content[0].analysisId").value(10))
            .andExpect(jsonPath("$.content[0].pullNumber").value(42))
            .andExpect(jsonPath("$.content[0].headCommitSha").value("head42"))
            .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$.content[0].reportSnapshot").doesNotExist())
            .andExpect(jsonPath("$.content[1].analysisId").value(9))
            .andExpect(jsonPath("$.content[1].pullNumber").value(41))
            .andExpect(jsonPath("$.content[1].status").value("FAILED"))
            .andExpect(jsonPath("$.totalElements").value(2));

        verify(pullRequestAnalysisService).getHistoricalAnalyses(1L, 0, 20);
    }

    @Test
    void getAnalyses_unknownRepository_returns404() throws Exception {
        when(repositoryService.getRepositoryById(999L))
            .thenThrow(new ResourceNotFoundException("Repository not found with id: 999"));

        mockMvc.perform(get("/api/v1/repositories/999/analyses"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        verify(pullRequestAnalysisService, never()).getHistoricalAnalyses(any(), anyInt(), anyInt());
    }

    @Test
    void getAnalyses_invalidPaginationParameters_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/repositories/1/analyses?page=-1"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(get("/api/v1/repositories/1/analyses?size=0"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
    }

    @Test
    void getAnalysisSnapshot_success_returns200WithFullSnapshot() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);

        PullRequestMetadata prMetadata = new PullRequestMetadata(
            "changescope-org", "order-service", 42, "Refactor payment service",
            "base123", "head456", PullRequestState.OPEN
        );
        AnalysisCoverage coverage = AnalysisCoverage.boundedPartial(
            "base123",
            12,
            8,
            false,
            List.of("Targeted discovery is bounded to candidate naming and package heuristics; full repository indexing was not performed")
        );
        ImpactReport impactReport = new ImpactReport(
            List.of("com.changescope.service.PaymentService"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            coverage
        );
        PullRequestImpactResult originalSnapshot = new PullRequestImpactResult(
            10L,
            prMetadata,
            impactReport,
            List.of("warning-1"),
            List.of()
        );

        // Prove coverage survives JSON snapshot serialization and deserialization
        String storedSnapshotJson = objectMapper.writeValueAsString(originalSnapshot);
        PullRequestImpactResult deserializedSnapshot = objectMapper.readValue(storedSnapshotJson, PullRequestImpactResult.class);

        when(pullRequestAnalysisService.getAnalysisSnapshot(1L, 10L)).thenReturn(deserializedSnapshot);

        mockMvc.perform(get("/api/v1/repositories/1/analyses/10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.analysisId").value(10))
            .andExpect(jsonPath("$.pullRequest.number").value(42))
            .andExpect(jsonPath("$.pullRequest.title").value("Refactor payment service"))
            .andExpect(jsonPath("$.impactReport.totalChangedTypes").value(1))
            .andExpect(jsonPath("$.impactReport.coverage.scope").value("PARTIAL_INDEX_BOUNDED"))
            .andExpect(jsonPath("$.impactReport.coverage.baseCommitSha").value("base123"))
            .andExpect(jsonPath("$.impactReport.coverage.productionFilesDiscovered").value(12))
            .andExpect(jsonPath("$.impactReport.coverage.productionFilesIncluded").value(8))
            .andExpect(jsonPath("$.impactReport.coverage.isTruncatedOrCapped").value(false))
            .andExpect(jsonPath("$.impactReport.coverage.isComplete").value(false))
            .andExpect(jsonPath("$.impactReport.coverage.incompleteReasons", hasSize(1)))
            .andExpect(jsonPath("$.impactReport.coverage.incompleteReasons[0]").value(
                "Targeted discovery is bounded to candidate naming and package heuristics; full repository indexing was not performed"
            ))
            .andExpect(jsonPath("$.extractionWarnings[0]").value("warning-1"));

        verify(pullRequestAnalysisService).getAnalysisSnapshot(1L, 10L);
    }

    @Test
    void getAnalysisSnapshot_ownershipMismatch_returns404() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            2L, 102L, "changescope-org", "inventory-service",
            "changescope-org/inventory-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(2L)).thenReturn(repo);
        when(pullRequestAnalysisService.getAnalysisSnapshot(2L, 10L))
            .thenThrow(new ResourceNotFoundException("Analysis with id 10 does not belong to repository with id 2"));

        mockMvc.perform(get("/api/v1/repositories/2/analyses/10"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value(containsString("does not belong to repository")));
    }

    @Test
    void getAnalysisSnapshot_unknownAnalysis_returns404() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestAnalysisService.getAnalysisSnapshot(1L, 999L))
            .thenThrow(new ResourceNotFoundException("Analysis not found with id: 999"));

        mockMvc.perform(get("/api/v1/repositories/1/analyses/999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void analyzePullRequest_unknownRepositoryId_returns404NotFound() throws Exception {
        when(repositoryService.getRepositoryById(999L))
            .thenThrow(new ResourceNotFoundException("Repository not found with id: 999"));

        mockMvc.perform(post("/api/v1/repositories/999/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.status").value(404))
            .andExpect(jsonPath("$.error").value("NOT_FOUND"))
            .andExpect(jsonPath("$.message").value(containsString("999")));

        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_invalidPullRequestNumber_zero_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/1/pulls/0/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("positive")));

        verify(repositoryService, never()).getRepositoryById(any());
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_invalidPullRequestNumber_negative_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/1/pulls/-5/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("positive")));

        verify(repositoryService, never()).getRepositoryById(any());
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_invalidRepositoryId_zero_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/0/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("positive")));

        verify(repositoryService, never()).getRepositoryById(any());
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_invalidRepositoryId_negative_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/-10/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value(containsString("positive")));

        verify(repositoryService, never()).getRepositoryById(any());
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_nonNumericArguments_returns400BadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/repositories/abc/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/xyz/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.error").value("BAD_REQUEST"));

        verify(repositoryService, never()).getRepositoryById(any());
        verify(pullRequestImpactService, never()).analyzePullRequest(any(), any(), anyInt());
    }

    @Test
    void analyzePullRequest_gitHubAccessForbidden_recordsFailureAndReturns403() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubAuthenticationException(403, "GitHub API access forbidden (HTTP 403) for Pull Request changescope-org/order-service#42"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.error").value("AUTHENTICATION_FAILED"))
            .andExpect(jsonPath("$.message").value(containsString("forbidden")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubAuthenticationException.class), any());
    }

    @Test
    void analyzePullRequest_gitHubNetworkError_recordsFailureAndReturns503() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubApiException(503, "NETWORK_ERROR", "Network error communicating with GitHub API"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.status").value(503))
            .andExpect(jsonPath("$.error").value("NETWORK_ERROR"))
            .andExpect(jsonPath("$.message").value(containsString("Network error")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubApiException.class), any());
    }

    @Test
    void analyzePullRequest_gitHubMalformedResponse_recordsFailureAndReturns500() throws Exception {
        RepositoryResponse repo = new RepositoryResponse(
            1L, 101L, "changescope-org", "order-service",
            "changescope-org/order-service", "main", "c0ffee", Instant.now(), Instant.now()
        );
        when(repositoryService.getRepositoryById(1L)).thenReturn(repo);
        when(pullRequestImpactService.analyzePullRequest("changescope-org", "order-service", 42))
            .thenThrow(new GitHubApiException(500, "MALFORMED_RESPONSE", "Failed to parse or process GitHub response"));

        mockMvc.perform(post("/api/v1/repositories/1/pulls/42/analyze")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.status").value(500))
            .andExpect(jsonPath("$.error").value("MALFORMED_RESPONSE"))
            .andExpect(jsonPath("$.message").value(containsString("Failed to parse or process GitHub response")));

        verify(pullRequestAnalysisService).startAnalysis(1L, 42);
        verify(pullRequestAnalysisService).failAnalysis(eq(100L), any(GitHubApiException.class), any());
    }
}
