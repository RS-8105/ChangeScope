package com.changescope.service.github;

import com.changescope.dto.ChangedTypeDetection;
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
import com.changescope.service.ImpactReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

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

        ImpactReport mockReport = new ImpactReport(
            List.of("com.changescope.OrderService"),
            List.of(),
            List.of(),
            List.of()
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
}
