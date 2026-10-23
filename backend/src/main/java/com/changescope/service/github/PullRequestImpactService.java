package com.changescope.service.github;

import com.changescope.dto.AnalysisCoverage;
import com.changescope.dto.CoverageScope;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GitDiffExtractionResult;
import com.changescope.dto.GitTreeResult;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.SourceFileContent;
import com.changescope.exception.GitHubApiException;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.service.ImpactReportService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
        return analyzePullRequest(owner, repo, pullNumber, null, null);
    }

    public PullRequestImpactResult analyzePullRequest(String owner, String repo, int pullNumber, String explicitHeadSha) {
        return analyzePullRequest(owner, repo, pullNumber, explicitHeadSha, null);
    }

    public PullRequestImpactResult analyzePullRequest(
        String owner,
        String repo,
        int pullNumber,
        String explicitHeadSha,
        String explicitBaseSha
    ) {
        validateInputs(owner, repo, pullNumber);
        if (explicitHeadSha != null && explicitHeadSha.isBlank()) {
            throw new IllegalArgumentException("Explicit head commit SHA must not be blank");
        }
        if (explicitBaseSha != null && explicitBaseSha.isBlank()) {
            throw new IllegalArgumentException("Explicit base commit SHA must not be blank");
        }

        // 1. Retrieve PR metadata
        PullRequestMetadata pr = gitHubGateway.getPullRequest(owner, repo, pullNumber);
        validatePullRequestMetadata(pr, owner, repo, pullNumber);

        // Verify revision consistency:
        // GitHub's pull request files endpoint (/repos/{owner}/{repo}/pulls/{number}/files) only reflects
        // the current live head/base state of the pull request. If an explicit head or base SHA is specified
        // and does not match the PR's live revision on GitHub, ChangeScope must not query the live files endpoint,
        // as doing so would accidentally analyze a newer revision's changed-file list against the requested historical commit.
        String cleanExplicitHeadSha = (explicitHeadSha != null && !explicitHeadSha.isBlank())
            ? explicitHeadSha.trim()
            : null;
        String cleanExplicitBaseSha = (explicitBaseSha != null && !explicitBaseSha.isBlank())
            ? explicitBaseSha.trim()
            : null;

        if (cleanExplicitHeadSha != null && !cleanExplicitHeadSha.equalsIgnoreCase(pr.headCommitSha())) {
            throw new GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED",
                "Pull request " + owner + "/" + repo + "#" + pullNumber
                    + " head revision has advanced (live head: " + pr.headCommitSha()
                    + ", requested: " + cleanExplicitHeadSha
                    + "). The GitHub pull request files endpoint does not support historical commit revisions.");
        }

        if (cleanExplicitBaseSha != null && pr.baseCommitSha() != null && !cleanExplicitBaseSha.equalsIgnoreCase(pr.baseCommitSha())) {
            throw new GitHubApiException(409, "HISTORICAL_REVISION_UNSUPPORTED",
                "Pull request " + owner + "/" + repo + "#" + pullNumber
                    + " base revision has changed (live base: " + pr.baseCommitSha()
                    + ", requested: " + cleanExplicitBaseSha
                    + "). The GitHub pull request files endpoint does not support historical commit revisions.");
        }

        // Determine effective revisions
        String effectiveHeadSha = (cleanExplicitHeadSha != null) ? cleanExplicitHeadSha : pr.headCommitSha();
        String effectiveBaseSha = (cleanExplicitBaseSha != null) ? cleanExplicitBaseSha : pr.baseCommitSha();

        // Ensure PR metadata reflects the revisions actually analyzed
        PullRequestMetadata effectivePr = pr.withRevisions(effectiveHeadSha, effectiveBaseSha);

        // 2. Retrieve PR file changes
        List<PullRequestFileChange> fileChanges = gitHubGateway.getPullRequestFiles(owner, repo, pullNumber);
        if (fileChanges == null || fileChanges.isEmpty()) {
            AnalysisCoverage coverage = AnalysisCoverage.prFilesOnly(effectiveBaseSha);
            ImpactReport emptyReport = impactReportService.generateReport(List.of(), List.of(), coverage);
            if (emptyReport == null) {
                emptyReport = impactReportService.generateReport(List.of(), List.of());
            }
            if (emptyReport != null && emptyReport.coverage() == null) {
                emptyReport = emptyReport.withCoverage(coverage);
            }
            return new PullRequestImpactResult(effectivePr, emptyReport, List.of(), List.of());
        }

        // 3. Filter Java source files
        List<PullRequestFileChange> javaChanges = fileChanges.stream()
            .filter(f -> f != null && f.isJavaSource())
            .toList();

        if (javaChanges.isEmpty()) {
            AnalysisCoverage coverage = AnalysisCoverage.prFilesOnly(effectiveBaseSha);
            ImpactReport emptyReport = impactReportService.generateReport(List.of(), List.of(), coverage);
            if (emptyReport == null) {
                emptyReport = impactReportService.generateReport(List.of(), List.of());
            }
            if (emptyReport != null && emptyReport.coverage() == null) {
                emptyReport = emptyReport.withCoverage(coverage);
            }
            return new PullRequestImpactResult(effectivePr, emptyReport, List.of(), List.of());
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
                if (effectivePr.baseCommitSha() != null && !effectivePr.baseCommitSha().isBlank()) {
                    try {
                        SourceFileContent baseContent = gitHubGateway.getFileContent(owner, repo, path, effectivePr.baseCommitSha());
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
                SourceFileContent content = gitHubGateway.getFileContent(owner, repo, path, effectiveHeadSha);
                if (content != null && content.content() != null) {
                    headSources.put(path, content.content());
                    availableHeadContents.add(content);
                } else {
                    if (explicitHeadSha != null && !explicitHeadSha.isBlank()) {
                        throw new GitHubResourceNotFoundException("Received empty source content for '" + path + "' at requested revision " + effectiveHeadSha);
                    }
                    warnings.add("Received empty source content for '" + path + "' at commit " + effectiveHeadSha);
                }
            } catch (Exception ex) {
                if (explicitHeadSha != null && !explicitHeadSha.isBlank()) {
                    if (ex instanceof RuntimeException re && (ex instanceof GitHubApiException || ex instanceof IllegalArgumentException)) {
                        throw re;
                    }
                    throw new GitHubResourceNotFoundException("Failed to retrieve source content for '" + path + "' at requested revision " + effectiveHeadSha + ": " + ex.getMessage(), ex);
                }
                warnings.add("Failed to retrieve source content for '" + path + "' at commit " + effectiveHeadSha + ": " + ex.getMessage());
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

        // 6. Targeted discovery of unchanged Java production and test files from base commit tree
        List<SourceFileContent> discoveredTestContents = new ArrayList<>();
        List<SourceFileContent> discoveredProductionContents = new ArrayList<>();
        List<String> incompleteReasons = new ArrayList<>();
        boolean isTruncated = false;
        boolean isCapped = false;
        int prodFilesDiscovered = 0;
        boolean discoveryAttempted = false;
        boolean discoveryFailed = false;
        String discoveryFailureReason = null;

        if (effectiveBaseSha != null && !effectiveBaseSha.isBlank()
                && !extractionResult.changedTypes().isEmpty()) {
            try {
                GitTreeResult treeResult = gitHubGateway.getGitTree(owner, repo, effectiveBaseSha);
                if (treeResult != null) {
                    discoveryAttempted = true;
                    isTruncated = treeResult.truncated();
                    if (isTruncated) {
                        warnings.add("Base repository tree was truncated by GitHub API; some unchanged production files may not have been discovered");
                        warnings.add("Base repository tree was truncated by GitHub API; some unchanged test files may not have been discovered");
                        incompleteReasons.add("Base repository tree was truncated by GitHub API; some unchanged production files may not have been discovered");
                    }

                    Set<String> prFilePaths = new HashSet<>();
                    for (PullRequestFileChange f : fileChanges) {
                        if (f != null) {
                            if (f.filePath() != null) {
                                prFilePaths.add(f.filePath());
                            }
                            if (f.previousFilePath() != null) {
                                prFilePaths.add(f.previousFilePath());
                            }
                        }
                    }

                    // A. Targeted discovery of unchanged production files
                    List<String> allCandidateProdPaths = discoverAllUnchangedProductionPaths(
                        treeResult.filePaths(),
                        extractionResult.changedTypes(),
                        prFilePaths
                    );

                    prodFilesDiscovered = allCandidateProdPaths.size();
                    isCapped = prodFilesDiscovered > MAX_DISCOVERED_PRODUCTION_FILES;
                    if (isCapped) {
                        warnings.add("Targeted production file discovery reached limit of "
                            + MAX_DISCOVERED_PRODUCTION_FILES + " files; some unchanged production dependents may be missing");
                        incompleteReasons.add("Targeted production file discovery reached limit of "
                            + MAX_DISCOVERED_PRODUCTION_FILES + " files; some unchanged production dependents may be missing");
                    }

                    if (!isTruncated && !isCapped) {
                        incompleteReasons.add("Targeted discovery is bounded to candidate naming and package heuristics; full repository indexing was not performed");
                    }

                    List<String> candidateProdPaths = allCandidateProdPaths.stream()
                        .limit(MAX_DISCOVERED_PRODUCTION_FILES)
                        .toList();

                    if (!candidateProdPaths.isEmpty()) {
                        try {
                            List<SourceFileContent> batch = gitHubGateway.getFilesContent(
                                owner,
                                repo,
                                candidateProdPaths,
                                effectiveBaseSha
                            );
                            if (batch != null) {
                                for (SourceFileContent sfc : batch) {
                                    if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                        discoveredProductionContents.add(sfc);
                                    } else if (sfc != null && sfc.filePath() != null) {
                                        warnings.add("Received empty source content for discovered production file '" + sfc.filePath() + "' at commit " + effectiveBaseSha);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            for (String prodPath : candidateProdPaths) {
                                try {
                                    SourceFileContent sfc = gitHubGateway.getFileContent(owner, repo, prodPath, effectiveBaseSha);
                                    if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                        discoveredProductionContents.add(sfc);
                                    } else {
                                        warnings.add("Received empty source content for discovered production file '" + prodPath + "' at commit " + effectiveBaseSha);
                                    }
                                } catch (Exception individualEx) {
                                    warnings.add("Failed to retrieve source content for discovered production file '" + prodPath + "' at commit " + effectiveBaseSha + ": " + individualEx.getMessage());
                                }
                            }
                        }
                    }

                    // B. Targeted discovery of unchanged test files
                    List<String> candidateTestPaths = discoverUnchangedTestPaths(
                        treeResult.filePaths(),
                        extractionResult.changedTypes(),
                        prFilePaths,
                        MAX_DISCOVERED_TEST_FILES
                    );

                    if (!candidateTestPaths.isEmpty()) {
                        try {
                            List<SourceFileContent> batch = gitHubGateway.getFilesContent(
                                owner,
                                repo,
                                candidateTestPaths,
                                effectiveBaseSha
                            );
                            if (batch != null) {
                                for (SourceFileContent sfc : batch) {
                                    if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                        discoveredTestContents.add(sfc);
                                    } else if (sfc != null && sfc.filePath() != null) {
                                        warnings.add("Received empty source content for discovered test '" + sfc.filePath() + "' at commit " + effectiveBaseSha);
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            // If batch fails, attempt individual retrievals so one error doesn't discard all
                            for (String testPath : candidateTestPaths) {
                                try {
                                    SourceFileContent sfc = gitHubGateway.getFileContent(owner, repo, testPath, effectiveBaseSha);
                                    if (sfc != null && sfc.content() != null && !sfc.content().isBlank()) {
                                        discoveredTestContents.add(sfc);
                                    } else {
                                        warnings.add("Received empty source content for discovered test '" + testPath + "' at commit " + effectiveBaseSha);
                                    }
                                } catch (Exception individualEx) {
                                    warnings.add("Failed to retrieve source content for discovered test '" + testPath + "' at commit " + effectiveBaseSha + ": " + individualEx.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                discoveryFailed = true;
                warnings.add("Failed to retrieve base repository tree for unchanged test discovery: " + ex.getMessage());
                discoveryFailureReason = "Failed to retrieve base repository tree for unchanged discovery: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                incompleteReasons.add(discoveryFailureReason);
            }
        }

        // 7. Merge source files for impact analysis without duplicates
        // Head contents take absolute precedence; base-branch files never overwrite PR changes.
        Set<String> deletedPaths = fileChanges.stream()
            .filter(f -> f != null && (f.status() == FileChangeStatus.DELETED || f.previousFilePath() != null))
            .flatMap(f -> {
                List<String> paths = new ArrayList<>();
                if (f.status() == FileChangeStatus.DELETED && f.filePath() != null) {
                    paths.add(f.filePath());
                }
                if (f.previousFilePath() != null) {
                    paths.add(f.previousFilePath());
                }
                return paths.stream();
            })
            .collect(Collectors.toSet());

        Set<String> includedPaths = new HashSet<>();
        List<JavaSourceFileInput> analysisFiles = new ArrayList<>();
        int prodFilesIncluded = 0;

        for (SourceFileContent headContent : availableHeadContents) {
            if (headContent != null && headContent.filePath() != null && includedPaths.add(headContent.filePath())) {
                analysisFiles.add(headContent.toFileInput());
            }
        }

        for (SourceFileContent prodContent : discoveredProductionContents) {
            if (prodContent != null && prodContent.filePath() != null) {
                String path = prodContent.filePath();
                if (!deletedPaths.contains(path) && includedPaths.add(path)) {
                    analysisFiles.add(prodContent.toFileInput());
                    prodFilesIncluded++;
                }
            }
        }

        for (SourceFileContent testContent : discoveredTestContents) {
            if (testContent != null && testContent.filePath() != null) {
                String path = testContent.filePath();
                if (!deletedPaths.contains(path) && includedPaths.add(path)) {
                    analysisFiles.add(testContent.toFileInput());
                }
            }
        }

        // Determine structured analysis coverage
        AnalysisCoverage coverage;
        if (discoveryFailed) {
            coverage = AnalysisCoverage.prFilesOnlyIncomplete(effectiveBaseSha, discoveryFailureReason);
        } else if (!discoveryAttempted) {
            coverage = AnalysisCoverage.prFilesOnly(effectiveBaseSha);
        } else {
            coverage = AnalysisCoverage.boundedPartial(
                effectiveBaseSha,
                prodFilesDiscovered,
                prodFilesIncluded,
                isTruncated || isCapped,
                incompleteReasons
            );
        }

        Collections.sort(warnings);

        // 8. Run full impact analysis
        ImpactReport impactReport = impactReportService.generateReport(
            analysisFiles,
            extractionResult.changedTypes(),
            coverage
        );
        if (impactReport == null) {
            impactReport = impactReportService.generateReport(
                analysisFiles,
                extractionResult.changedTypes()
            );
        }
        if (impactReport != null && impactReport.coverage() == null) {
            impactReport = impactReport.withCoverage(coverage);
        }

        return new PullRequestImpactResult(
            effectivePr,
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

    static final int MAX_DISCOVERED_TEST_FILES = 20;

    List<String> discoverUnchangedTestPaths(
        List<String> treePaths,
        List<String> changedTypes,
        Set<String> prFilePaths,
        int maxLimit
    ) {
        if (treePaths == null || treePaths.isEmpty() || changedTypes == null || changedTypes.isEmpty()) {
            return List.of();
        }

        record ProdType(String simpleName, String packageName, String packagePath) {}
        List<ProdType> prodTypes = new ArrayList<>();
        for (String type : changedTypes) {
            if (type == null || type.isBlank()) continue;
            String clean = type.trim();
            int lastDot = clean.lastIndexOf('.');
            if (lastDot > 0) {
                String pkg = clean.substring(0, lastDot);
                String simple = clean.substring(lastDot + 1);
                prodTypes.add(new ProdType(simple, pkg, pkg.replace('.', '/')));
            } else {
                prodTypes.add(new ProdType(clean, null, null));
            }
        }

        if (prodTypes.isEmpty()) {
            return List.of();
        }

        record CandidateMatch(String path, int score) {}
        List<CandidateMatch> matchedCandidates = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        for (String rawPath : treePaths) {
            if (rawPath == null || rawPath.isBlank()) continue;
            String path = rawPath.trim();

            if (!path.endsWith(".java")) {
                continue;
            }

            if (prFilePaths != null && prFilePaths.contains(path)) {
                continue;
            }

            int lastSlash = path.lastIndexOf('/');
            String filename = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
            String simpleTestName = filename.substring(0, filename.length() - ".java".length());

            boolean isTestName = simpleTestName.endsWith("Test")
                || simpleTestName.endsWith("Tests")
                || simpleTestName.endsWith("IT");
            if (!isTestName) {
                continue;
            }

            int bestScore = 0;
            for (ProdType prod : prodTypes) {
                boolean exactNameMatch = simpleTestName.equals(prod.simpleName() + "Test")
                    || simpleTestName.equals(prod.simpleName() + "Tests")
                    || simpleTestName.equals(prod.simpleName() + "IT");

                boolean prefixNameMatch = !exactNameMatch
                    && simpleTestName.startsWith(prod.simpleName())
                    && (simpleTestName.endsWith("Test") || simpleTestName.endsWith("Tests") || simpleTestName.endsWith("IT"));

                boolean packageMatch = prod.packagePath() != null && path.contains(prod.packagePath());

                if (packageMatch && exactNameMatch) {
                    bestScore = Math.max(bestScore, 4);
                } else if (exactNameMatch) {
                    bestScore = Math.max(bestScore, 3);
                } else if (packageMatch && prefixNameMatch) {
                    bestScore = Math.max(bestScore, 2);
                } else if (prefixNameMatch) {
                    bestScore = Math.max(bestScore, 1);
                }
            }

            if (bestScore > 0 && seenPaths.add(path)) {
                matchedCandidates.add(new CandidateMatch(path, bestScore));
            }
        }

        matchedCandidates.sort(Comparator
            .comparingInt(CandidateMatch::score).reversed()
            .thenComparing(CandidateMatch::path)
        );

        return matchedCandidates.stream()
            .map(CandidateMatch::path)
            .limit(maxLimit)
            .toList();
    }

    static final int MAX_DISCOVERED_PRODUCTION_FILES = 20;

    static boolean isNonProductionOrTestPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String normalized = path.replace('\\', '/').toLowerCase();

        // Check directory exclusions
        if (normalized.startsWith("src/test/") || normalized.contains("/src/test/")
                || normalized.startsWith("test/") || normalized.contains("/test/")
                || normalized.startsWith("target/") || normalized.contains("/target/")
                || normalized.startsWith("build/") || normalized.contains("/build/")
                || normalized.startsWith(".gradle/") || normalized.contains("/.gradle/")) {
            return true;
        }

        // Conventional test filename suffixes
        int lastSlash = path.lastIndexOf('/');
        String filename = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
        if (filename.endsWith(".java")) {
            String simpleName = filename.substring(0, filename.length() - ".java".length());
            if (simpleName.endsWith("Test")
                    || simpleName.endsWith("Tests")
                    || simpleName.endsWith("IT")
                    || simpleName.endsWith("TestCase")) {
                return true;
            }
        }

        return false;
    }

    List<String> discoverUnchangedProductionPaths(
        List<String> treePaths,
        List<String> changedTypes,
        Set<String> prFilePaths,
        int maxLimit
    ) {
        return discoverAllUnchangedProductionPaths(treePaths, changedTypes, prFilePaths).stream()
            .limit(maxLimit)
            .toList();
    }

    List<String> discoverAllUnchangedProductionPaths(
        List<String> treePaths,
        List<String> changedTypes,
        Set<String> prFilePaths
    ) {
        if (treePaths == null || treePaths.isEmpty() || changedTypes == null || changedTypes.isEmpty()) {
            return List.of();
        }

        record ProdType(String simpleName, String stem, String packageName, String packagePath, String ancestorPackagePath) {}
        List<ProdType> prodTypes = new ArrayList<>();
        for (String type : changedTypes) {
            if (type == null || type.isBlank()) continue;
            String clean = type.trim();
            int lastDot = clean.lastIndexOf('.');
            String simple = (lastDot > 0) ? clean.substring(lastDot + 1) : clean;
            String stem = simple.replaceAll("(Service|Controller|Repository|Manager|Handler|Factory|Client|Component|Impl|Helper|Util|Dao|Entity|Model|Dto)$", "");
            if (stem.isBlank()) {
                stem = simple;
            }

            if (lastDot > 0) {
                String pkg = clean.substring(0, lastDot);
                String pkgPath = pkg.replace('.', '/');
                int parentDot = pkg.lastIndexOf('.');
                String ancestorPath = (parentDot > 0) ? pkg.substring(0, parentDot).replace('.', '/') : null;
                prodTypes.add(new ProdType(simple, stem, pkg, pkgPath, ancestorPath));
            } else {
                prodTypes.add(new ProdType(simple, stem, null, null, null));
            }
        }

        if (prodTypes.isEmpty()) {
            return List.of();
        }

        record CandidateMatch(String path, int score) {}
        List<CandidateMatch> matchedCandidates = new ArrayList<>();
        Set<String> seenPaths = new HashSet<>();

        for (String rawPath : treePaths) {
            if (rawPath == null || rawPath.isBlank()) continue;
            String path = rawPath.trim();

            if (!path.endsWith(".java")) {
                continue;
            }

            if (isNonProductionOrTestPath(path)) {
                continue;
            }

            if (prFilePaths != null && prFilePaths.contains(path)) {
                continue;
            }

            int lastSlash = path.lastIndexOf('/');
            String filename = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;
            String simpleCandidateName = filename.substring(0, filename.length() - ".java".length());

            int bestScore = 0;
            for (ProdType prod : prodTypes) {
                boolean packageMatch = prod.packagePath() != null && path.contains(prod.packagePath());
                boolean exactNameMatch = simpleCandidateName.contains(prod.simpleName());
                boolean stemMatch = prod.stem() != null && prod.stem().length() >= 3 && simpleCandidateName.contains(prod.stem());

                if (packageMatch && (exactNameMatch || stemMatch)) {
                    bestScore = Math.max(bestScore, 4);
                } else if (packageMatch) {
                    bestScore = Math.max(bestScore, 3);
                } else if (exactNameMatch || stemMatch) {
                    bestScore = Math.max(bestScore, 2);
                } else if (prod.ancestorPackagePath() != null && path.contains(prod.ancestorPackagePath())) {
                    bestScore = Math.max(bestScore, 1);
                }
            }

            if (bestScore > 0 && seenPaths.add(path)) {
                matchedCandidates.add(new CandidateMatch(path, bestScore));
            }
        }

        matchedCandidates.sort(Comparator
            .comparingInt(CandidateMatch::score).reversed()
            .thenComparing(CandidateMatch::path)
        );

        return matchedCandidates.stream()
            .map(CandidateMatch::path)
            .toList();
    }
}
