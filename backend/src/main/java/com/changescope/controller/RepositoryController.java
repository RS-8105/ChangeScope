package com.changescope.controller;

import com.changescope.dto.AnalysisSummaryResponse;
import com.changescope.dto.CreateRepositoryRequest;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.RepositoryResponse;
import com.changescope.service.PullRequestAnalysisService;
import com.changescope.service.RepositoryService;
import com.changescope.service.github.PullRequestImpactService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/v1/repositories")
public class RepositoryController {

    private final RepositoryService repositoryService;
    private final PullRequestImpactService pullRequestImpactService;
    private final PullRequestAnalysisService pullRequestAnalysisService;

    public RepositoryController(
        RepositoryService repositoryService,
        PullRequestImpactService pullRequestImpactService,
        PullRequestAnalysisService pullRequestAnalysisService
    ) {
        this.repositoryService = repositoryService;
        this.pullRequestImpactService = pullRequestImpactService;
        this.pullRequestAnalysisService = pullRequestAnalysisService;
    }

    @PostMapping
    public ResponseEntity<RepositoryResponse> createRepository(@Valid @RequestBody CreateRepositoryRequest request) {
        RepositoryResponse response = repositoryService.createRepository(request);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(response.id())
            .toUri();
        return ResponseEntity.created(location).body(response);
    }

    @GetMapping
    public ResponseEntity<List<RepositoryResponse>> getAllRepositories() {
        return ResponseEntity.ok(repositoryService.getAllRepositories());
    }

    @GetMapping("/{id}")
    public ResponseEntity<RepositoryResponse> getRepositoryById(@PathVariable Long id) {
        return ResponseEntity.ok(repositoryService.getRepositoryById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteRepository(@PathVariable Long id) {
        repositoryService.deleteRepository(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/pulls/{number}/analyze")
    public ResponseEntity<PullRequestImpactResult> analyzePullRequest(
        @PathVariable Long id,
        @PathVariable Integer number
    ) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (number == null || number <= 0) {
            throw new IllegalArgumentException("Pull request number must be a positive integer");
        }

        RepositoryResponse repo = repositoryService.getRepositoryById(id);

        com.changescope.model.PullRequestAnalysisEntity analysisRecord = pullRequestAnalysisService.startAnalysis(id, number);
        Long analysisId = analysisRecord.getId();

        PullRequestImpactResult result;
        try {
            result = pullRequestImpactService.analyzePullRequest(
                repo.owner(),
                repo.name(),
                number
            );
        } catch (Throwable ex) {
            pullRequestAnalysisService.failAnalysis(analysisId, ex, Instant.now());
            throw ex;
        }

        PullRequestImpactResult persisted = pullRequestAnalysisService.completeAnalysis(
            analysisId,
            result,
            Instant.now()
        );

        return ResponseEntity.ok(persisted);
    }

    @GetMapping("/{id}/analyses")
    public ResponseEntity<Page<AnalysisSummaryResponse>> getAnalyses(
        @PathVariable Long id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (page < 0) {
            throw new IllegalArgumentException("Page index must not be negative");
        }
        if (size <= 0) {
            throw new IllegalArgumentException("Page size must be greater than zero");
        }

        repositoryService.getRepositoryById(id);
        Page<AnalysisSummaryResponse> analyses = pullRequestAnalysisService.getHistoricalAnalyses(id, page, size);
        return ResponseEntity.ok(analyses);
    }

    @GetMapping("/{id}/analyses/{analysisId}")
    public ResponseEntity<PullRequestImpactResult> getAnalysisSnapshot(
        @PathVariable Long id,
        @PathVariable Long analysisId
    ) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException("Repository ID must be a positive integer");
        }
        if (analysisId == null || analysisId <= 0) {
            throw new IllegalArgumentException("Analysis ID must be a positive integer");
        }

        repositoryService.getRepositoryById(id);
        PullRequestImpactResult snapshot = pullRequestAnalysisService.getAnalysisSnapshot(id, analysisId);
        return ResponseEntity.ok(snapshot);
    }
}
