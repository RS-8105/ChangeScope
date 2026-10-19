package com.changescope.service.github;

import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GitDiffExtractionResult;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.SourceFileContent;
import com.changescope.service.ImpactReportService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PullRequestImpactService {

    private final GitHubGateway gitHubGateway;
    private final GitDiffTypeExtractor gitDiffTypeExtractor;
    private final ImpactReportService impactReportService;

    public PullRequestImpactService(
        GitHubGateway gitHubGateway,
        GitDiffTypeExtractor gitDiffTypeExtractor,
        ImpactReportService impactReportService
    ) {
        this.gitHubGateway = gitHubGateway;
        this.gitDiffTypeExtractor = gitDiffTypeExtractor;
        this.impactReportService = impactReportService;
    }

    public PullRequestImpactResult analyzePullRequest(String owner, String repo, int pullNumber) {
        validateInputs(owner, repo, pullNumber);

        // 1. Retrieve PR metadata
        PullRequestMetadata pr = gitHubGateway.getPullRequest(owner, repo, pullNumber);
        validatePullRequestMetadata(pr, owner, repo, pullNumber);

        // 2. Retrieve PR file changes
        List<PullRequestFileChange> fileChanges = gitHubGateway.getPullRequestFiles(owner, repo, pullNumber);
        if (fileChanges == null || fileChanges.isEmpty()) {
            ImpactReport emptyReport = impactReportService.generateReport(List.of(), List.of());
            return new PullRequestImpactResult(pr, emptyReport, List.of(), List.of());
        }

        // 3. Filter Java source files
        List<PullRequestFileChange> javaChanges = fileChanges.stream()
            .filter(f -> f != null && f.isJavaSource())
            .toList();

        if (javaChanges.isEmpty()) {
            ImpactReport emptyReport = impactReportService.generateReport(List.of(), List.of());
            return new PullRequestImpactResult(pr, emptyReport, List.of(), List.of());
        }

        // 4. Retrieve source contents
        Map<String, String> headSources = new HashMap<>();
        Map<String, String> previousSources = new HashMap<>();
        List<SourceFileContent> availableHeadContents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        for (PullRequestFileChange change : javaChanges) {
            String path = change.filePath();

            // Handle deleted files
            if (change.status() == FileChangeStatus.DELETED) {
                if (pr.baseCommitSha() != null && !pr.baseCommitSha().isBlank()) {
                    try {
                        SourceFileContent baseContent = gitHubGateway.getFileContent(owner, repo, path, pr.baseCommitSha());
                        if (baseContent != null && baseContent.content() != null) {
                            previousSources.put(path, baseContent.content());
                        }
                    } catch (Exception ex) {
                        warnings.add("Could not retrieve base commit content for deleted file '" + path + "': " + ex.getMessage());
                    }
                }
                continue;
            }

            // Retrieve head commit content for added / modified / renamed files
            try {
                SourceFileContent content = gitHubGateway.getFileContent(owner, repo, path, pr.headCommitSha());
                if (content != null && content.content() != null) {
                    headSources.put(path, content.content());
                    availableHeadContents.add(content);
                } else {
                    warnings.add("Received empty source content for '" + path + "' at commit " + pr.headCommitSha());
                }
            } catch (Exception ex) {
                warnings.add("Failed to retrieve source content for '" + path + "' at commit " + pr.headCommitSha() + ": " + ex.getMessage());
            }
        }

        // 5. Detect changed types from diff patch and AST source ranges
        GitDiffExtractionResult extractionResult = gitDiffTypeExtractor.extractChangedTypes(
            javaChanges,
            headSources,
            previousSources
        );

        warnings.addAll(extractionResult.warnings());
        Collections.sort(warnings);

        // 6. Only pass available source files to the impact analysis pipeline
        List<JavaSourceFileInput> analysisFiles = availableHeadContents.stream()
            .map(SourceFileContent::toFileInput)
            .toList();

        // 7. Run full impact analysis
        ImpactReport impactReport = impactReportService.generateReport(
            analysisFiles,
            extractionResult.changedTypes()
        );

        return new PullRequestImpactResult(
            pr,
            impactReport,
            warnings,
            extractionResult.detections()
        );
    }

    private void validateInputs(String owner, String repo, int pullNumber) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("Repository owner must not be null or blank");
        }
        if (repo == null || repo.isBlank()) {
            throw new IllegalArgumentException("Repository name must not be null or blank");
        }
        if (pullNumber <= 0) {
            throw new IllegalArgumentException("Pull request number must be positive");
        }
    }

    private void validatePullRequestMetadata(PullRequestMetadata pr, String owner, String repo, int pullNumber) {
        if (pr == null) {
            throw new IllegalArgumentException("Could not retrieve pull request metadata for " + owner + "/" + repo + "#" + pullNumber);
        }
        if (pr.owner() != null && !pr.owner().equalsIgnoreCase(owner.trim())) {
            throw new IllegalArgumentException("Pull request owner '" + pr.owner() + "' does not match requested owner '" + owner + "'");
        }
        if (pr.repo() != null && !pr.repo().equalsIgnoreCase(repo.trim())) {
            throw new IllegalArgumentException("Pull request repo '" + pr.repo() + "' does not match requested repo '" + repo + "'");
        }
        if (pr.headCommitSha() == null || pr.headCommitSha().isBlank()) {
            throw new IllegalArgumentException("Pull request " + owner + "/" + repo + "#" + pullNumber + " does not have a usable head commit SHA");
        }
    }
}
