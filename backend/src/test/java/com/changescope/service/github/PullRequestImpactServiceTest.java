package com.changescope.service.github;

import com.changescope.dto.AffectedType;
import com.changescope.dto.AnalysisCoverage;
import com.changescope.dto.ChangedTypeDetection;
import com.changescope.dto.CoverageScope;
import com.changescope.dto.FileChangeStatus;
import com.changescope.dto.GitDiffExtractionResult;
import com.changescope.dto.ImpactReport;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.PullRequestFileChange;
import com.changescope.dto.PullRequestImpactResult;
import com.changescope.dto.PullRequestMetadata;
import com.changescope.dto.PullRequestState;
import com.changescope.dto.SourceFileContent;
import com.changescope.dto.TypeChangeEvidence;
import com.changescope.exception.GitHubResourceNotFoundException;
import com.changescope.dto.GitTreeResult;
import com.changescope.dto.TestImpactCandidate;
import com.changescope.dto.TestMatchEvidence;
import com.changescope.dto.VerificationPriority;
import com.changescope.dto.VerificationRecommendation;
import com.changescope.service.BlastRadiusAnalysisService;
import com.changescope.service.ImpactReportService;
import com.changescope.service.JavaDependencyAnalysisService;
import com.changescope.service.JavaMethodAnalysisService;
import com.changescope.service.JavaSourceParserService;
import com.changescope.service.TestImpactAnalysisService;
import com.changescope.service.VerificationPlanService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PullRequestImpactServiceTest {

    private GitHubGateway gitHubGateway;
    private GitDiffTypeExtractor gitDiffTypeExtractor;
    private ImpactReportService impactReportService;
    private PullRequestImpactService service;

    @BeforeEach
    void setUp() {
        gitHubGateway = mock(GitHubGateway.class);
        gitDiffTypeExtractor = mock(GitDiffTypeExtractor.class);
        impactReportService = mock(ImpactReportService.class);
        service = new PullRequestImpactService(gitHubGateway, gitDiffTypeExtractor, impactReportService);
    }

    @Test
    void analyzePullRequest_successfulOrchestration_returnsCompleteResult() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor payments",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/OrderService.java",
            FileChangeStatus.MODIFIED,
            5,
            2,
            "@@ -10,3 +10,4 @@"
        );

        SourceFileContent fileContent = new SourceFileContent(
            "src/main/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(new ChangedTypeDetection("com.changescope.OrderService", "src/main/OrderService.java", TypeChangeEvidence.PATCH_LINE_MATCH, "detail")),
            List.of()
        );

        AnalysisCoverage expectedCoverage = AnalysisCoverage.prFilesOnly("base1111");
        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            null,
            expectedCoverage
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/OrderService.java", "head9999")).thenReturn(fileContent);
        when(gitDiffTypeExtractor.extractChangedTypes(eq(List.of(fileChange)), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), eq(List.of("com.changescope.OrderService")))).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        assertThat(result.pullRequest()).isEqualTo(pr);
        assertThat(result.impactReport()).isEqualTo(mockReport);
        assertThat(result.extractionWarnings()).isEmpty();
        assertThat(result.hasWarnings()).isFalse();
        assertThat(result.changedTypeDetections()).hasSize(1);
        assertThat(result.changedTypeDetections().get(0).fullyQualifiedName()).isEqualTo("com.changescope.OrderService");

        // Verify analyzer was passed the available file input
        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OrderService")));
        assertThat(filesCaptor.getValue()).hasSize(1);
        assertThat(filesCaptor.getValue().get(0).filePath()).isEqualTo("src/main/OrderService.java");
    }

    @Test
    void analyzePullRequest_noChangedJavaFiles_returnsEmptyAnalysisWithoutCallingExtractor() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            10,
            "Docs update",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange mdFile = new PullRequestFileChange("README.md", FileChangeStatus.MODIFIED, 10, 0, null);
        PullRequestFileChange ymlFile = new PullRequestFileChange(".github/workflows/ci.yml", FileChangeStatus.ADDED, 20, 0, null);

        ImpactReport emptyReport = new ImpactReport(List.of(), List.of(), List.of(), List.of());

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 10)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 10)).thenReturn(List.of(mdFile, ymlFile));
        when(impactReportService.generateReport(List.of(), List.of())).thenReturn(emptyReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 10);

        assertThat(result.pullRequest()).isEqualTo(pr);
        assertThat(result.impactReport().totalChangedTypes()).isZero();
        assertThat(result.extractionWarnings()).isEmpty();
        assertThat(result.changedTypeDetections()).isEmpty();

        // Verify gateway did not fetch source and extractor was never called
        verify(gitHubGateway, never()).getFileContent(any(), any(), any(), any());
        verify(gitDiffTypeExtractor, never()).extractChangedTypes(any(), anyMap(), anyMap());
    }

    @Test
    void analyzePullRequest_partialSourceRetrieval_continuesWithAvailableFilesAndAddsWarning() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            15,
            "Partial retrieval",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileA = new PullRequestFileChange("src/main/FileA.java", FileChangeStatus.MODIFIED, 1, 1, "@@ -1 +1 @@");
        PullRequestFileChange fileB = new PullRequestFileChange("src/main/FileB.java", FileChangeStatus.MODIFIED, 2, 2, "@@ -1 +1 @@");

        SourceFileContent contentA = new SourceFileContent("src/main/FileA.java", "public class FileA {}", "head9999");

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 15)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 15)).thenReturn(List.of(fileA, fileB));

        // FileA succeeds, FileB fails with 404
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/FileA.java", "head9999")).thenReturn(contentA);
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/FileB.java", "head9999"))
            .thenThrow(new GitHubResourceNotFoundException("File not found on GitHub"));

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.FileA"),
            List.of(new ChangedTypeDetection("com.changescope.FileA", "src/main/FileA.java", TypeChangeEvidence.PATCH_LINE_MATCH, "detail")),
            List.of("Source content not provided for file 'src/main/FileB.java' at PR head commit.")
        );

        ImpactReport mockReport = new ImpactReport(List.of("com.changescope.FileA"), List.of(), List.of(), List.of());
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 15);

        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.extractionWarnings()).anyMatch(w -> w.contains("Failed to retrieve source content for 'src/main/FileB.java'"));

        // Verify only FileA was passed to analyzer
        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.FileA")));
        assertThat(filesCaptor.getValue()).hasSize(1);
        assertThat(filesCaptor.getValue().get(0).filePath()).isEqualTo("src/main/FileA.java");
    }

    @Test
    void analyzePullRequest_invalidPrMetadata_throwsIllegalArgumentException() {
        // Missing head commit SHA
        PullRequestMetadata invalidPr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            1,
            "Broken PR",
            "base1111",
            null, // missing head SHA
            PullRequestState.OPEN
        );
        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 1)).thenReturn(invalidPr);

        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("usable head commit SHA");

        // Repo mismatch
        PullRequestMetadata mismatchPr = new PullRequestMetadata(
            "differentOwner",
            "Hello-World",
            2,
            "Wrong repo",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );
        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 2)).thenReturn(mismatchPr);

        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 2))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not match requested owner");

        // Invalid pull number
        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must be positive");
    }

    @Test
    void analyzePullRequest_extractionWarnings_preservesWarningsInResult() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            20,
            "Fallback PR",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange change = new PullRequestFileChange("Legacy.java", FileChangeStatus.MODIFIED, 5, 2, null);
        SourceFileContent content = new SourceFileContent("Legacy.java", "public class Legacy {}", "head9999");

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 20)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 20)).thenReturn(List.of(change));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "Legacy.java", "head9999")).thenReturn(content);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("Legacy"),
            List.of(new ChangedTypeDetection("Legacy", "Legacy.java", TypeChangeEvidence.FILE_LEVEL_FALLBACK, "fallback")),
            List.of("Patch text unavailable for 'Legacy.java'; falling back to all declared types in file.")
        );

        ImpactReport mockReport = new ImpactReport(List.of("Legacy"), List.of(), List.of(), List.of());
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 20);

        assertThat(result.hasWarnings()).isTrue();
        assertThat(result.extractionWarnings()).containsExactly("Patch text unavailable for 'Legacy.java'; falling back to all declared types in file.");
        assertThat(result.changedTypeDetections().get(0).evidence()).isEqualTo(TypeChangeEvidence.FILE_LEVEL_FALLBACK);
    }

    @Test
    void analyzePullRequest_deletedFile_doesNotFetchHeadContentAndAttemptsBaseRetrieval() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            30,
            "Deleted file PR",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange deletedChange = new PullRequestFileChange(
            "src/main/OldService.java",
            FileChangeStatus.DELETED,
            0,
            50,
            null
        );

        SourceFileContent baseContent = new SourceFileContent("src/main/OldService.java", "public class OldService {}", "base1111");

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 30)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 30)).thenReturn(List.of(deletedChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/OldService.java", "base1111")).thenReturn(baseContent);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OldService"),
            List.of(new ChangedTypeDetection("com.changescope.OldService", "src/main/OldService.java", TypeChangeEvidence.DELETED_FILE_PREVIOUS_SOURCE, "detail")),
            List.of()
        );

        ImpactReport mockReport = new ImpactReport(List.of("com.changescope.OldService"), List.of(), List.of(), List.of());
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 30);

        // Verify head content was NEVER requested for the deleted file
        verify(gitHubGateway, never()).getFileContent("octocat", "Hello-World", "src/main/OldService.java", "head9999");

        // Verify base content WAS requested for deleted file
        verify(gitHubGateway).getFileContent("octocat", "Hello-World", "src/main/OldService.java", "base1111");

        // Verify analyzer was passed empty analysis files (since OldService does not exist at head)
        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OldService")));
        assertThat(filesCaptor.getValue()).isEmpty();

        // Verify extractor received base content
        ArgumentCaptor<Map<String, String>> prevSourcesCaptor = ArgumentCaptor.forClass(Map.class);
        verify(gitDiffTypeExtractor).extractChangedTypes(anyList(), anyMap(), prevSourcesCaptor.capture());
        assertThat(prevSourcesCaptor.getValue()).containsEntry("src/main/OldService.java", "public class OldService {}");
    }

    @Test
    void analyzePullRequest_withExplicitHeadSha_matchesLivePr_usesExplicitHeadShaAndDoesNotFallback() {
        String headSha = "head9999";
        PullRequestMetadata livePr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor payments",
            "base1111",
            headSha,
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/OrderService.java",
            FileChangeStatus.MODIFIED,
            5,
            2,
            "@@ -10,3 +10,4 @@"
        );

        SourceFileContent fileContent = new SourceFileContent(
            "src/main/OrderService.java",
            "public class OrderService {}",
            headSha
        );

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(new ChangedTypeDetection("com.changescope.OrderService", "src/main/OrderService.java", TypeChangeEvidence.PATCH_LINE_MATCH, "detail")),
            List.of()
        );

        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of(),
            List.of()
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(livePr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/OrderService.java", headSha)).thenReturn(fileContent);
        when(gitDiffTypeExtractor.extractChangedTypes(eq(List.of(fileChange)), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), eq(List.of("com.changescope.OrderService")))).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42, headSha);

        // Result PR metadata must record the explicit head SHA
        assertThat(result.pullRequest().headCommitSha()).isEqualTo(headSha);
        assertThat(result.pullRequest().baseCommitSha()).isEqualTo("base1111");

        // Source retrieval must use explicit head SHA
        verify(gitHubGateway).getFileContent("octocat", "Hello-World", "src/main/OrderService.java", headSha);
    }

    @Test
    void analyzePullRequest_explicitHeadShaSourceRetrievalFails_throwsExceptionWithoutFallback() {
        String headSha = "head9999";
        PullRequestMetadata livePr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor payments",
            "base1111",
            headSha,
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/OrderService.java",
            FileChangeStatus.MODIFIED,
            5,
            2,
            "@@ -10,3 +10,4 @@"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(livePr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/OrderService.java", headSha))
            .thenThrow(new GitHubResourceNotFoundException("Commit or file not found on GitHub"));

        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 42, headSha))
            .isInstanceOf(GitHubResourceNotFoundException.class)
            .hasMessageContaining("Commit or file not found on GitHub");
    }

    @Test
    void analyzePullRequest_historicalWebhookHeadShaMismatch_throwsHistoricalRevisionUnsupportedAndNeverQueriesPrFiles() {
        PullRequestMetadata livePr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor payments",
            "base1111",
            "live-head-newer-9999", // live head has moved ahead on GitHub
            PullRequestState.OPEN
        );

        String historicalWebhookHeadSha = "historical-webhook-older-1111";

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(livePr);

        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 42, historicalWebhookHeadSha))
            .isInstanceOf(com.changescope.exception.GitHubApiException.class)
            .satisfies(ex -> {
                com.changescope.exception.GitHubApiException gha = (com.changescope.exception.GitHubApiException) ex;
                assertThat(gha.getStatusCode()).isEqualTo(409);
                assertThat(gha.getErrorCategory()).isEqualTo("HISTORICAL_REVISION_UNSUPPORTED");
                assertThat(gha.getMessage()).contains("head revision has advanced", "live-head-newer-9999", "historical-webhook-older-1111");
            });

        // CRITICAL REGRESSION ASSERTION: Ensure getPullRequestFiles is NEVER queried with a newer revision's files
        verify(gitHubGateway, never()).getPullRequestFiles(any(), any(), anyInt());
        verify(gitHubGateway, never()).getFileContent(any(), any(), any(), any());
    }

    @Test
    void analyzePullRequest_historicalWebhookBaseShaMismatch_throwsHistoricalRevisionUnsupportedAndNeverQueriesPrFiles() {
        PullRequestMetadata livePr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor payments",
            "live-base-2222",
            "head9999",
            PullRequestState.OPEN
        );

        String historicalBaseSha = "historical-base-0000";
        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(livePr);

        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 42, "head9999", historicalBaseSha))
            .isInstanceOf(com.changescope.exception.GitHubApiException.class)
            .satisfies(ex -> {
                com.changescope.exception.GitHubApiException gha = (com.changescope.exception.GitHubApiException) ex;
                assertThat(gha.getStatusCode()).isEqualTo(409);
                assertThat(gha.getErrorCategory()).isEqualTo("HISTORICAL_REVISION_UNSUPPORTED");
                assertThat(gha.getMessage()).contains("base revision has changed", "live-base-2222", "historical-base-0000");
            });

        verify(gitHubGateway, never()).getPullRequestFiles(any(), any(), anyInt());
        verify(gitHubGateway, never()).getFileContent(any(), any(), any(), any());
    }

    @Test
    void analyzePullRequest_blankExplicitHeadSha_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 42, "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Explicit head commit SHA must not be blank");
    }

    @Test
    void analyzePullRequest_blankExplicitBaseSha_throwsIllegalArgumentException() {
        assertThatThrownBy(() -> service.analyzePullRequest("octocat", "Hello-World", 42, "head9999", "   "))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Explicit base commit SHA must not be blank");
    }

    @Test
    void discoverUnchangedTestPaths_matchesConventionalTestNamesAndPrioritizesByPackage() {
        List<String> treePaths = List.of(
            "src/test/java/com/changescope/order/OrderServiceTest.java",
            "src/test/java/com/changescope/order/OrderServiceTests.java",
            "src/test/java/com/changescope/order/OrderServiceIT.java",
            "src/test/java/com/other/pkg/OrderServiceTest.java",
            "src/test/java/com/changescope/order/OrderServiceIntegrationTest.java",
            "src/test/java/com/changescope/order/UnrelatedServiceTest.java",
            "src/main/java/com/changescope/order/OrderService.java",
            "README.md"
        );

        List<String> changedTypes = List.of("com.changescope.order.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedTestPaths(treePaths, changedTypes, prFiles, 20);

        // Should find all OrderService test variations, prioritizing same-package exact matches first
        assertThat(discovered).containsExactly(
            "src/test/java/com/changescope/order/OrderServiceIT.java",
            "src/test/java/com/changescope/order/OrderServiceTest.java",
            "src/test/java/com/changescope/order/OrderServiceTests.java",
            "src/test/java/com/other/pkg/OrderServiceTest.java",
            "src/test/java/com/changescope/order/OrderServiceIntegrationTest.java"
        );
    }

    @Test
    void discoverUnchangedTestPaths_excludesTestsAlreadyInPrChangeset() {
        List<String> treePaths = List.of(
            "src/test/java/com/changescope/OrderServiceTest.java",
            "src/test/java/com/changescope/OrderServiceIT.java"
        );

        List<String> changedTypes = List.of("com.changescope.OrderService");
        // OrderServiceTest.java was already modified in the PR
        Set<String> prFiles = Set.of("src/test/java/com/changescope/OrderServiceTest.java");

        List<String> discovered = service.discoverUnchangedTestPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).containsExactly("src/test/java/com/changescope/OrderServiceIT.java");
    }

    @Test
    void discoverUnchangedTestPaths_enforcesFileLimitOf20() {
        List<String> treePaths = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            treePaths.add("src/test/java/com/changescope/pkg" + i + "/OrderServiceTest.java");
        }

        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedTestPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).hasSize(20);
    }

    @Test
    void discoverUnchangedTestPaths_ignoresNonTestAndNonJavaFiles() {
        List<String> treePaths = List.of(
            "src/main/java/com/changescope/OrderService.java",
            "src/test/java/com/changescope/OrderServiceHelper.java",
            "src/test/resources/OrderServiceTest.xml",
            "src/test/kotlin/com/changescope/OrderServiceTest.kt"
        );

        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedTestPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).isEmpty();
    }

    @Test
    void discoverUnchangedTestPaths_preventsDuplicates() {
        List<String> treePaths = List.of(
            "src/test/java/com/changescope/OrderServiceTest.java",
            "src/test/java/com/changescope/OrderServiceTest.java"
        );

        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedTestPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).containsExactly("src/test/java/com/changescope/OrderServiceTest.java");
    }

    @Test
    void analyzePullRequest_unchangedTestDiscoveredInBaseTree_includedInAnalysisInputsAndAppearsInVerificationPlan() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            10,
            2,
            "@@ -1,5 +1,5 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "package com.changescope; public class OrderService { public void placeOrder() {} }",
            "head9999"
        );

        SourceFileContent unchangedTestContent = new SourceFileContent(
            "src/test/java/com/changescope/OrderServiceTest.java",
            "package com.changescope; import org.junit.jupiter.api.Test; public class OrderServiceTest { @Test void test() { new OrderService().placeOrder(); } }",
            "base1111"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        // Base tree returns unchanged OrderServiceTest.java
        GitTreeResult treeResult = new GitTreeResult(
            List.of("src/test/java/com/changescope/OrderServiceTest.java", "src/main/java/com/changescope/OrderService.java"),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        when(gitHubGateway.getFilesContent("octocat", "Hello-World", List.of("src/test/java/com/changescope/OrderServiceTest.java"), "base1111"))
            .thenReturn(List.of(unchangedTestContent));

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(new ChangedTypeDetection("com.changescope.OrderService", "src/main/java/com/changescope/OrderService.java", TypeChangeEvidence.PATCH_LINE_MATCH, "detail")),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);

        com.changescope.dto.TestImpactCandidate candidate = new com.changescope.dto.TestImpactCandidate(
            "src/test/java/com/changescope/OrderServiceTest.java",
            "com.changescope.OrderServiceTest",
            "test",
            com.changescope.dto.TestMatchEvidence.TYPE_REFERENCE,
            "Direct reference",
            "com.changescope.OrderService"
        );
        com.changescope.dto.VerificationRecommendation rec = new com.changescope.dto.VerificationRecommendation(
            com.changescope.dto.VerificationPriority.HIGH,
            "TEST",
            "com.changescope.OrderServiceTest",
            "EXECUTE",
            "Direct reference"
        );
        com.changescope.dto.AnalysisConfidence confidence = new com.changescope.dto.AnalysisConfidence(
            com.changescope.dto.ConfidenceLevel.HIGH,
            95,
            "HIGH",
            List.of(),
            List.of()
        );
        com.changescope.dto.VerificationPlan plan = new com.changescope.dto.VerificationPlan(
            confidence,
            List.of(rec)
        );
        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(candidate),
            plan
        );
        when(impactReportService.generateReport(anyList(), eq(List.of("com.changescope.OrderService")))).thenReturn(mockReport);

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        // Verify analysis inputs sent to ImpactReportService contain BOTH head content and discovered test
        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OrderService")));

        List<JavaSourceFileInput> analysisFiles = filesCaptor.getValue();
        assertThat(analysisFiles).hasSize(2);
        assertThat(analysisFiles).extracting(JavaSourceFileInput::filePath).containsExactly(
            "src/main/java/com/changescope/OrderService.java",
            "src/test/java/com/changescope/OrderServiceTest.java"
        );

        // Verify verification plan contains candidate test
        assertThat(result.impactReport().candidateTests()).hasSize(1);
        assertThat(result.impactReport().candidateTests().get(0).testClassName()).isEqualTo("com.changescope.OrderServiceTest");
        assertThat(result.impactReport().verificationPlan().recommendations()).hasSize(1);
        assertThat(result.extractionWarnings()).isEmpty();
    }

    @Test
    void analyzePullRequest_gitTreeFailure_retainsAnalysisAndAddsWarning() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            "@@ -1 +1 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        // Tree lookup throws transient exception
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111"))
            .thenThrow(new RuntimeException("GitHub API temporary outage"));

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        assertThat(result).isNotNull();
        assertThat(result.extractionWarnings()).hasSize(1);
        assertThat(result.extractionWarnings().get(0)).contains("Failed to retrieve base repository tree for unchanged test discovery");
    }

    @Test
    void analyzePullRequest_gitTreeTruncated_addsTruncationWarning() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            "@@ -1 +1 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        // Tree is truncated
        GitTreeResult truncatedTree = new GitTreeResult(List.of(), true);
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(truncatedTree);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        assertThat(result).isNotNull();
        assertThat(result.extractionWarnings()).contains("Base repository tree was truncated by GitHub API; some unchanged test files may not have been discovered");
    }

    @Test
    void analyzePullRequest_batchFailureFallsBackToIndividualRetrieval() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            "@@ -1 +1 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        SourceFileContent testContent = new SourceFileContent(
            "src/test/java/com/changescope/OrderServiceTest.java",
            "public class OrderServiceTest {}",
            "base1111"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        GitTreeResult treeResult = new GitTreeResult(
            List.of("src/test/java/com/changescope/OrderServiceTest.java"),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        // Batch call throws, but individual fallback succeeds
        when(gitHubGateway.getFilesContent("octocat", "Hello-World", List.of("src/test/java/com/changescope/OrderServiceTest.java"), "base1111"))
            .thenThrow(new RuntimeException("Batch endpoint error"));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/test/java/com/changescope/OrderServiceTest.java", "base1111"))
            .thenReturn(testContent);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OrderService")));

        assertThat(filesCaptor.getValue()).extracting(JavaSourceFileInput::filePath).contains(
            "src/test/java/com/changescope/OrderServiceTest.java"
        );
    }

    @Test
    void analyzePullRequest_withRealAnalysisPipeline_unchangedBaseTreeTestFlowsThroughToCandidateAndVerificationPlan() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "order-platform",
            101,
            "Update OrderService logic",
            "base_commit_sha_111",
            "head_commit_sha_999",
            PullRequestState.OPEN
        );

        String prodPath = "src/main/java/com/example/OrderService.java";
        String testPath = "src/test/java/com/example/OrderServiceTest.java";

        String prodSource = """
            package com.example;

            public class OrderService {
                public void placeOrder() {
                    System.out.println("Processing order");
                }
            }
            """;

        String testSource = """
            package com.example;

            import org.junit.jupiter.api.Test;

            public class OrderServiceTest {
                @Test
                void testPlaceOrder() {
                    OrderService service = new OrderService();
                    service.placeOrder();
                }
            }
            """;

        PullRequestFileChange fileChange = new PullRequestFileChange(
            prodPath,
            FileChangeStatus.MODIFIED,
            3,
            1,
            """
            @@ -4,3 +4,3 @@
            -    public void placeOrder() {}
            +    public void placeOrder() {
            +        System.out.println("Processing order");
            +    }
            """
        );

        SourceFileContent prodHeadContent = new SourceFileContent(prodPath, prodSource, "head_commit_sha_999");
        SourceFileContent testBaseContent = new SourceFileContent(testPath, testSource, "base_commit_sha_111");

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 101)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 101)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "order-platform", prodPath, "head_commit_sha_999"))
            .thenReturn(prodHeadContent);

        GitTreeResult baseTreeResult = new GitTreeResult(
            List.of(prodPath, testPath),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "order-platform", "base_commit_sha_111"))
            .thenReturn(baseTreeResult);

        when(gitHubGateway.getFilesContent("octocat", "order-platform", List.of(testPath), "base_commit_sha_111"))
            .thenReturn(List.of(testBaseContent));

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 101);

        assertThat(result.extractionWarnings()).isEmpty();
        assertThat(result.impactReport()).isNotNull();

        List<TestImpactCandidate> candidates = result.impactReport().candidateTests();
        assertThat(candidates).hasSize(1);
        TestImpactCandidate candidate = candidates.get(0);
        assertThat(candidate.testFilePath()).isEqualTo(testPath);
        assertThat(candidate.testClassName()).isEqualTo("com.example.OrderServiceTest");
        assertThat(candidate.testMethodName()).isEqualTo("testPlaceOrder");
        assertThat(candidate.evidenceKind()).isEqualTo(TestMatchEvidence.METHOD_INVOCATION);
        assertThat(candidate.targetElement()).isEqualTo("com.example.OrderService#placeOrder(): void");
        assertThat(candidate.reason()).contains("Directly invokes production method: com.example.OrderService#placeOrder(): void");

        List<VerificationRecommendation> recommendations = result.impactReport().verificationPlan().recommendations();
        VerificationRecommendation testRecommendation = recommendations.stream()
            .filter(r -> "CANDIDATE_TEST".equals(r.category()) && "com.example.OrderServiceTest#testPlaceOrder".equals(r.target()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Expected CANDIDATE_TEST recommendation for OrderServiceTest#testPlaceOrder"));

        assertThat(testRecommendation.priority()).isEqualTo(VerificationPriority.HIGH);
        assertThat(testRecommendation.action()).isEqualTo("Run targeted test method 'testPlaceOrder'");
        assertThat(testRecommendation.reason()).isEqualTo("Directly invokes production method: com.example.OrderService#placeOrder(): void");
    }

    @Test
    void discoverUnchangedProductionPaths_excludesTestsAndGeneratedDirectories() {
        List<String> treePaths = List.of(
            "src/test/java/com/changescope/OrderController.java",
            "test/java/com/changescope/OrderController.java",
            "target/classes/com/changescope/OrderController.java",
            "build/classes/com/changescope/OrderController.java",
            ".gradle/cache/com/changescope/OrderController.java",
            "src/main/java/com/changescope/OrderTest.java",
            "src/main/java/com/changescope/OrderTests.java",
            "src/main/java/com/changescope/OrderIT.java",
            "src/main/java/com/changescope/OrderTestCase.java",
            "src/main/resources/OrderService.xml",
            "src/main/kotlin/com/changescope/OrderController.kt",
            "src/main/java/com/changescope/OrderController.java"
        );
        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedProductionPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).containsExactly("src/main/java/com/changescope/OrderController.java");
    }

    @Test
    void discoverUnchangedProductionPaths_enforcesFileLimitOf20() {
        List<String> treePaths = new java.util.ArrayList<>();
        for (int i = 1; i <= 30; i++) {
            treePaths.add("src/main/java/com/changescope/OrderController" + i + ".java");
        }
        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of();

        List<String> discovered = service.discoverUnchangedProductionPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).hasSize(20);
    }

    @Test
    void discoverUnchangedProductionPaths_excludesPrFilesIncludingRenamedAndDeleted() {
        List<String> treePaths = List.of(
            "src/main/java/com/changescope/ModifiedService.java",
            "src/main/java/com/changescope/DeletedService.java",
            "src/main/java/com/changescope/OldServiceName.java",
            "src/main/java/com/changescope/UnchangedController.java"
        );
        List<String> changedTypes = List.of("com.changescope.OrderService");
        Set<String> prFiles = Set.of(
            "src/main/java/com/changescope/ModifiedService.java",
            "src/main/java/com/changescope/DeletedService.java",
            "src/main/java/com/changescope/OldServiceName.java"
        );

        List<String> discovered = service.discoverUnchangedProductionPaths(treePaths, changedTypes, prFiles, 20);

        assertThat(discovered).containsExactly("src/main/java/com/changescope/UnchangedController.java");
    }

    @Test
    void analyzePullRequest_prHeadFileTakesPrecedenceOverBaseFile() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            5,
            2,
            "@@ -1,5 +1,5 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "// HEAD CONTENT - AUTHORITATIVE",
            "head9999"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        GitTreeResult treeResult = new GitTreeResult(
            List.of("src/main/java/com/changescope/OrderService.java"),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        service.analyzePullRequest("octocat", "Hello-World", 42);

        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OrderService")));

        List<JavaSourceFileInput> analysisFiles = filesCaptor.getValue();
        assertThat(analysisFiles).hasSize(1);
        assertThat(analysisFiles.get(0).sourceCode()).isEqualTo("// HEAD CONTENT - AUTHORITATIVE");
    }

    @Test
    void analyzePullRequest_deletedPrFileNotReintroducedFromBaseTree() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Delete old service",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange deletedChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OldService.java",
            FileChangeStatus.DELETED,
            0,
            10,
            "@@ -1,10 +0,0 @@"
        );

        SourceFileContent baseDeletedContent = new SourceFileContent(
            "src/main/java/com/changescope/OldService.java",
            "package com.changescope; public class OldService {}",
            "base1111"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(deletedChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OldService.java", "base1111"))
            .thenReturn(baseDeletedContent);

        GitTreeResult treeResult = new GitTreeResult(
            List.of("src/main/java/com/changescope/OldService.java"),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OldService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        service.analyzePullRequest("octocat", "Hello-World", 42);

        ArgumentCaptor<List<JavaSourceFileInput>> filesCaptor = ArgumentCaptor.forClass(List.class);
        verify(impactReportService).generateReport(filesCaptor.capture(), eq(List.of("com.changescope.OldService")));

        assertThat(filesCaptor.getValue()).isEmpty();
    }

    @Test
    void analyzePullRequest_productionFetchFailure_addsWarningAndContinues() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            "@@ -1 +1 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        GitTreeResult treeResult = new GitTreeResult(
            List.of("src/main/java/com/changescope/OrderController.java"),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        when(gitHubGateway.getFilesContent("octocat", "Hello-World", List.of("src/main/java/com/changescope/OrderController.java"), "base1111"))
            .thenThrow(new RuntimeException("Production batch fetch failure"));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderController.java", "base1111"))
            .thenThrow(new RuntimeException("Production single fetch failure"));

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        assertThat(result).isNotNull();
        assertThat(result.extractionWarnings())
            .anyMatch(w -> w.contains("Failed to retrieve source content for discovered production file"));
    }

    @Test
    void analyzePullRequest_productionCandidateLimitExceeded_addsWarning() {
        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "Hello-World",
            42,
            "Refactor OrderService",
            "base1111",
            "head9999",
            PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "src/main/java/com/changescope/OrderService.java",
            FileChangeStatus.MODIFIED,
            1,
            0,
            "@@ -1 +1 @@"
        );

        SourceFileContent headContent = new SourceFileContent(
            "src/main/java/com/changescope/OrderService.java",
            "public class OrderService {}",
            "head9999"
        );

        when(gitHubGateway.getPullRequest("octocat", "Hello-World", 42)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "Hello-World", 42)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "Hello-World", "src/main/java/com/changescope/OrderService.java", "head9999"))
            .thenReturn(headContent);

        List<String> treePaths = new java.util.ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            treePaths.add("src/main/java/com/changescope/OrderController" + i + ".java");
        }
        GitTreeResult treeResult = new GitTreeResult(treePaths, false);
        when(gitHubGateway.getGitTree("octocat", "Hello-World", "base1111")).thenReturn(treeResult);

        GitDiffExtractionResult extraction = new GitDiffExtractionResult(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of()
        );
        when(gitDiffTypeExtractor.extractChangedTypes(anyList(), anyMap(), anyMap())).thenReturn(extraction);
        when(impactReportService.generateReport(anyList(), anyList())).thenReturn(mock(ImpactReport.class));

        PullRequestImpactResult result = service.analyzePullRequest("octocat", "Hello-World", 42);

        assertThat(result).isNotNull();
        assertThat(result.extractionWarnings())
            .contains("Targeted production file discovery reached limit of 20 files; some unchanged production dependents may be missing");
    }

    @Test
    void analyzePullRequest_withRealAnalysisPipeline_unchangedProductionDependentDiscoveredAndAppearsInBlastRadius() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat",
            "order-platform",
            202,
            "Update OrderService logic",
            "base_commit_111",
            "head_commit_999",
            PullRequestState.OPEN
        );

        String prodPath = "src/main/java/com/example/OrderService.java";
        String controllerPath = "src/main/java/com/example/OrderController.java";

        String prodSource = """
            package com.example;

            public class OrderService {
                public void placeOrder() {
                    System.out.println("Processing order");
                }
            }
            """;

        String controllerSource = """
            package com.example;

            public class OrderController {
                private final OrderService orderService;

                public OrderController(OrderService orderService) {
                    this.orderService = orderService;
                }

                public void submitOrder() {
                    orderService.placeOrder();
                }
            }
            """;

        PullRequestFileChange fileChange = new PullRequestFileChange(
            prodPath,
            FileChangeStatus.MODIFIED,
            3,
            1,
            """
            @@ -4,3 +4,3 @@
            -    public void placeOrder() {}
            +    public void placeOrder() {
            +        System.out.println("Processing order");
            +    }
            """
        );

        SourceFileContent prodHeadContent = new SourceFileContent(prodPath, prodSource, "head_commit_999");
        SourceFileContent controllerBaseContent = new SourceFileContent(controllerPath, controllerSource, "base_commit_111");

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 202)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 202)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "order-platform", prodPath, "head_commit_999"))
            .thenReturn(prodHeadContent);

        GitTreeResult baseTreeResult = new GitTreeResult(
            List.of(prodPath, controllerPath),
            false
        );
        when(gitHubGateway.getGitTree("octocat", "order-platform", "base_commit_111"))
            .thenReturn(baseTreeResult);

        when(gitHubGateway.getFilesContent("octocat", "order-platform", List.of(controllerPath), "base_commit_111"))
            .thenReturn(List.of(controllerBaseContent));

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 202);

        assertThat(result.extractionWarnings()).isEmpty();
        assertThat(result.impactReport()).isNotNull();

        assertThat(result.impactReport().changedTypes()).containsExactly("com.example.OrderService");
        assertThat(result.impactReport().affectedTypes()).contains(new AffectedType("com.example.OrderController", 1));

        AnalysisCoverage coverage = result.impactReport().coverage();
        assertThat(coverage).isNotNull();
        assertThat(coverage.scope()).isEqualTo(CoverageScope.PARTIAL_INDEX_BOUNDED);
        assertThat(coverage.baseCommitSha()).isEqualTo("base_commit_111");
        assertThat(coverage.productionFilesDiscovered()).isEqualTo(1);
        assertThat(coverage.productionFilesIncluded()).isEqualTo(1);
        assertThat(coverage.isTruncatedOrCapped()).isFalse();
        assertThat(coverage.isComplete()).isFalse();
        assertThat(coverage.incompleteReasons())
            .anyMatch(r -> r.contains("bounded to candidate naming and package heuristics"));
    }

    @Test
    void analyzePullRequest_structuredCoverage_truncatedTreeMarksIncompleteAndCapped() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat", "order-platform", 203, "Update OrderService", "base_commit_111", "head_commit_999", PullRequestState.OPEN
        );

        String prodPath = "src/main/java/com/example/OrderService.java";
        String prodSource = "package com.example;\npublic class OrderService {\n    public void placeOrder() {}\n}";

        PullRequestFileChange fileChange = new PullRequestFileChange(
            prodPath, FileChangeStatus.MODIFIED, 1, 1, "@@ -3 +3 @@\n- void placeOrder() {}\n+ void placeOrder() { int x = 1; }"
        );

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 203)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 203)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "order-platform", prodPath, "head_commit_999"))
            .thenReturn(new SourceFileContent(prodPath, prodSource, "head_commit_999"));

        GitTreeResult truncatedTree = new GitTreeResult(List.of(prodPath), true);
        when(gitHubGateway.getGitTree("octocat", "order-platform", "base_commit_111")).thenReturn(truncatedTree);

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 203);

        assertThat(result.impactReport()).isNotNull();
        AnalysisCoverage coverage = result.impactReport().coverage();
        assertThat(coverage).isNotNull();
        assertThat(coverage.scope()).isEqualTo(CoverageScope.PARTIAL_INDEX_BOUNDED);
        assertThat(coverage.isTruncatedOrCapped()).isTrue();
        assertThat(coverage.isComplete()).isFalse();
        assertThat(coverage.incompleteReasons())
            .anyMatch(r -> r.contains("tree was truncated by GitHub API"));
        assertThat(result.impactReport().verificationPlan().confidence().negativeFactors())
            .anyMatch(f -> f.contains("truncated or capped at discovery limits"));
    }

    @Test
    void analyzePullRequest_structuredCoverage_cappedProductionFilesMarksIncomplete() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat", "order-platform", 204, "Update OrderService", "base_commit_111", "head_commit_999", PullRequestState.OPEN
        );

        String prodPath = "src/main/java/com/example/OrderService.java";
        String prodSource = "package com.example;\npublic class OrderService {\n    public void placeOrder() {}\n}";

        PullRequestFileChange fileChange = new PullRequestFileChange(
            prodPath, FileChangeStatus.MODIFIED, 1, 1, "@@ -3 +3 @@\n- void placeOrder() {}\n+ void placeOrder() { int x = 1; }"
        );

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 204)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 204)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "order-platform", prodPath, "head_commit_999"))
            .thenReturn(new SourceFileContent(prodPath, prodSource, "head_commit_999"));

        // Build 25 candidates in same package
        List<String> treeFiles = new java.util.ArrayList<>();
        treeFiles.add(prodPath);
        for (int i = 1; i <= 25; i++) {
            treeFiles.add("src/main/java/com/example/OrderExtra" + i + ".java");
        }
        GitTreeResult treeResult = new GitTreeResult(treeFiles, false);
        when(gitHubGateway.getGitTree("octocat", "order-platform", "base_commit_111")).thenReturn(treeResult);

        when(gitHubGateway.getFilesContent(eq("octocat"), eq("order-platform"), anyList(), eq("base_commit_111")))
            .thenReturn(List.of());

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 204);

        assertThat(result.impactReport()).isNotNull();
        AnalysisCoverage coverage = result.impactReport().coverage();
        assertThat(coverage).isNotNull();
        assertThat(coverage.scope()).isEqualTo(CoverageScope.PARTIAL_INDEX_BOUNDED);
        assertThat(coverage.productionFilesDiscovered()).isEqualTo(25);
        assertThat(coverage.isTruncatedOrCapped()).isTrue();
        assertThat(coverage.isComplete()).isFalse();
        assertThat(coverage.incompleteReasons())
            .anyMatch(r -> r.contains("Targeted production file discovery reached limit of 20 files"));
    }

    @Test
    void analyzePullRequest_structuredCoverage_treeLookupFailureFallback() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat", "order-platform", 205, "Update OrderService", "base_commit_111", "head_commit_999", PullRequestState.OPEN
        );

        String prodPath = "src/main/java/com/example/OrderService.java";
        String prodSource = "package com.example;\npublic class OrderService {\n    public void placeOrder() {}\n}";

        PullRequestFileChange fileChange = new PullRequestFileChange(
            prodPath, FileChangeStatus.MODIFIED, 1, 1, "@@ -3 +3 @@\n- void placeOrder() {}\n+ void placeOrder() { int x = 1; }"
        );

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 205)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 205)).thenReturn(List.of(fileChange));
        when(gitHubGateway.getFileContent("octocat", "order-platform", prodPath, "head_commit_999"))
            .thenReturn(new SourceFileContent(prodPath, prodSource, "head_commit_999"));

        when(gitHubGateway.getGitTree("octocat", "order-platform", "base_commit_111"))
            .thenThrow(new RuntimeException("Tree API timed out"));

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 205);

        assertThat(result.impactReport()).isNotNull();
        AnalysisCoverage coverage = result.impactReport().coverage();
        assertThat(coverage).isNotNull();
        assertThat(coverage.scope()).isEqualTo(CoverageScope.PR_FILES_ONLY);
        assertThat(coverage.isComplete()).isFalse();
        assertThat(coverage.incompleteReasons())
            .anyMatch(r -> r.contains("Failed to retrieve base repository tree for unchanged discovery"));
        assertThat(result.impactReport().verificationPlan().confidence().negativeFactors())
            .anyMatch(f -> f.contains("Repository tree discovery failed"));
    }

    @Test
    void analyzePullRequest_structuredCoverage_cleanPrFilesOnly_whenNonJavaChanges() {
        JavaSourceParserService parserService = new JavaSourceParserService();
        JavaDependencyAnalysisService dependencyAnalysisService = new JavaDependencyAnalysisService(parserService);
        BlastRadiusAnalysisService blastRadiusAnalysisService = new BlastRadiusAnalysisService();
        JavaMethodAnalysisService javaMethodAnalysisService = new JavaMethodAnalysisService();
        TestImpactAnalysisService testImpactAnalysisService = new TestImpactAnalysisService();
        VerificationPlanService verificationPlanService = new VerificationPlanService();
        ImpactReportService realImpactReportService = new ImpactReportService(
            dependencyAnalysisService,
            blastRadiusAnalysisService,
            javaMethodAnalysisService,
            testImpactAnalysisService,
            verificationPlanService
        );
        GitDiffTypeExtractor realGitDiffTypeExtractor = new GitDiffTypeExtractor();

        PullRequestImpactService realService = new PullRequestImpactService(
            gitHubGateway,
            realGitDiffTypeExtractor,
            realImpactReportService
        );

        PullRequestMetadata pr = new PullRequestMetadata(
            "octocat", "order-platform", 206, "Update Readme", "base_commit_111", "head_commit_999", PullRequestState.OPEN
        );

        PullRequestFileChange fileChange = new PullRequestFileChange(
            "README.md", FileChangeStatus.MODIFIED, 2, 1, "@@ -1 +1 @@"
        );

        when(gitHubGateway.getPullRequest("octocat", "order-platform", 206)).thenReturn(pr);
        when(gitHubGateway.getPullRequestFiles("octocat", "order-platform", 206)).thenReturn(List.of(fileChange));

        PullRequestImpactResult result = realService.analyzePullRequest("octocat", "order-platform", 206);

        assertThat(result.impactReport()).isNotNull();
        AnalysisCoverage coverage = result.impactReport().coverage();
        assertThat(coverage).isNotNull();
        assertThat(coverage.scope()).isEqualTo(CoverageScope.PR_FILES_ONLY);
        assertThat(coverage.isComplete()).isTrue();
        assertThat(coverage.incompleteReasons()).isEmpty();
    }
}
