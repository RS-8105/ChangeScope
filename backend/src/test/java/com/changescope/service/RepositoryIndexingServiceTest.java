package com.changescope.service;

import com.changescope.config.AnalysisProperties;
import com.changescope.dto.DependencyAnalysisResult;
import com.changescope.dto.FileParseError;
import com.changescope.dto.GitTreeResult;
import com.changescope.dto.JavaSourceFileInput;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.SourceFileContent;
import com.changescope.dto.TypeDependency;
import com.changescope.dto.TypeKind;
import com.changescope.exception.GitHubApiException;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import com.changescope.service.github.GitHubGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RepositoryIndexingServiceTest {

    @Mock
    private GitHubGateway gitHubGateway;

    @Mock
    private JavaDependencyAnalysisService dependencyAnalysisService;

    @Mock
    private RepositoryEntityRepository repositoryEntityRepository;

    @Mock
    private RepositoryIndexRepository repositoryIndexRepository;

    @Mock
    private IndexedTypeRepository indexedTypeRepository;

    @Mock
    private IndexedTypeDependencyRepository indexedTypeDependencyRepository;

    private JavaSourceParserService parserService;
    private PlatformTransactionManager transactionManager;
    private AnalysisProperties analysisProperties;
    private RepositoryIndexingService indexingService;

    private RepositoryEntity repo;

    @BeforeEach
    void setUp() {
        parserService = new JavaSourceParserService();
        analysisProperties = new AnalysisProperties();
        analysisProperties.setStaleTimeout(Duration.ofMinutes(30));

        transactionManager = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) throws TransactionException {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) throws TransactionException {
            }

            @Override
            public void rollback(TransactionStatus status) throws TransactionException {
            }
        };

        indexingService = new RepositoryIndexingService(
            gitHubGateway,
            dependencyAnalysisService,
            parserService,
            repositoryEntityRepository,
            repositoryIndexRepository,
            indexedTypeRepository,
            indexedTypeDependencyRepository,
            transactionManager,
            analysisProperties
        );

        repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
    }

    @Test
    @DisplayName("Production Java file filter correctly filters production vs test and build files")
    void isProductionJavaFile_correctlyFiltersProductionVsTestAndBuildFiles() {
        // Valid production files
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/OrderService.java")).isTrue();
        assertThat(RepositoryIndexingService.isProductionJavaFile("com/example/OrderService.java")).isTrue();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/TestUtilityService.java")).isTrue();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/IntegrationTester.java")).isTrue();

        // Non-Java files
        assertThat(RepositoryIndexingService.isProductionJavaFile("pom.xml")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("README.md")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/resources/app.properties")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile(null)).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("   ")).isFalse();

        // Test directories
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/test/java/com/example/OrderService.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("test/com/example/OrderService.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("tests/com/example/OrderService.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("module/src/test/java/Foo.java")).isFalse();

        // Build and generated directories
        assertThat(RepositoryIndexingService.isProductionJavaFile("target/generated-sources/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("build/classes/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile(".gradle/caches/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile(".mvn/wrapper/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("bin/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("out/production/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("generated/Foo.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("generated-sources/Foo.java")).isFalse();

        // Test filename suffixes
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/OrderServiceTest.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/OrderServiceTests.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/OrderServiceIT.java")).isFalse();
        assertThat(RepositoryIndexingService.isProductionJavaFile("src/main/java/com/example/OrderServiceTestCase.java")).isFalse();
    }

    @Test
    @DisplayName("COMPLETED outcome when all files are retrieved and parsed without error")
    void indexRepository_completedOutcome_whenAllFilesRetrievedAndParsed() {
        String commitSha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(100L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> {
                RepositoryIndexEntity e = inv.getArgument(0);
                if (e.getId() == null) e.setId(100L);
                return e;
            });
        when(repositoryIndexRepository.findById(100L)).thenReturn(Optional.of(claimed));

        // Mock Git tree
        List<String> treePaths = List.of(
            "src/main/java/com/example/OrderService.java",
            "src/main/java/com/example/PaymentGateway.java",
            "src/test/java/com/example/OrderServiceTest.java",
            "pom.xml"
        );
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(treePaths, false));

        // Mock batch file content
        String orderSrc = "package com.example; public class OrderService { private PaymentGateway gateway; }";
        String paymentSrc = "package com.example; public interface PaymentGateway {}";
        List<SourceFileContent> contents = List.of(
            new SourceFileContent("src/main/java/com/example/OrderService.java", orderSrc, commitSha),
            new SourceFileContent("src/main/java/com/example/PaymentGateway.java", paymentSrc, commitSha)
        );
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(contents);

        // Mock dependency analysis
        List<TypeDependency> deps = List.of(
            new TypeDependency("com.example.OrderService", "com.example.PaymentGateway", RelationshipKind.FIELD, "src/main/java/com/example/OrderService.java")
        );
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(deps, List.of()));

        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(commitSha);
        when(repositoryEntityRepository.findByIdForUpdate(42L))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, true);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        assertThat(result.getTotalFilesDiscovered()).isEqualTo(2);
        assertThat(result.getTotalFilesIndexed()).isEqualTo(2);
        assertThat(result.getTotalTypesIndexed()).isEqualTo(2);
        assertThat(result.getTotalDependenciesIndexed()).isEqualTo(1);
        assertThat(result.isTruncatedOrCapped()).isFalse();
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getCompletedAt()).isNotNull();

        // Verify child persistence
        verify(indexedTypeRepository).saveAll(anyList());
        verify(indexedTypeDependencyRepository).saveAll(anyList());
        // Verify lastIndexedCommit updated for default branch
        assertThat(repo.getLastIndexedCommit()).isEqualTo(commitSha);
        verify(repositoryEntityRepository).saveAndFlush(repo);
    }

    @Test
    @DisplayName("Multi-batch ingestion retrieves files in batches of at most 100")
    void indexRepository_multiBatchRetrieval_whenMoreThan100Files() {
        String commitSha = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(200L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(200L);
            return e;
        });
        when(repositoryIndexRepository.findById(200L)).thenReturn(Optional.of(claimed));

        // Create 250 production files
        List<String> paths = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            paths.add("src/main/java/com/example/File" + i + ".java");
        }
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(paths, false));

        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenAnswer(inv -> {
                List<String> batchPaths = inv.getArgument(2);
                List<SourceFileContent> batchContents = new ArrayList<>();
                for (String p : batchPaths) {
                    batchContents.add(new SourceFileContent(p, "package com.example; public class " + p.substring(p.lastIndexOf('/') + 1, p.length() - 5) + " {}", commitSha));
                }
                return batchContents;
            });

        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        // 250 files should be retrieved in 3 batches: 100, 100, 50
        verify(gitHubGateway, times(3)).getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha));
        assertThat(result.getTotalFilesDiscovered()).isEqualTo(250);
        assertThat(result.getTotalFilesIndexed()).isEqualTo(250);
        assertThat(result.getTotalTypesIndexed()).isEqualTo(250);
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
    }

    @Test
    @DisplayName("Batch failure falls back to individual file retrieval")
    void indexRepository_batchFailure_fallsBackToIndividualRetrieval() {
        String commitSha = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(300L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(300L);
            return e;
        });
        when(repositoryIndexRepository.findById(300L)).thenReturn(Optional.of(claimed));

        List<String> paths = List.of(
            "src/main/java/com/example/GoodFile.java",
            "src/main/java/com/example/BadFile.java"
        );
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(paths, false));

        // Batch call throws exception
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenThrow(new GitHubApiException(500, "BATCH_ERROR", "Batch request failed"));

        // Individual calls: GoodFile succeeds, BadFile throws exception
        when(gitHubGateway.getFileContent("octocat", "hello-world", "src/main/java/com/example/GoodFile.java", commitSha))
            .thenReturn(new SourceFileContent("src/main/java/com/example/GoodFile.java", "package com.example; public class GoodFile {}", commitSha));
        when(gitHubGateway.getFileContent("octocat", "hello-world", "src/main/java/com/example/BadFile.java", commitSha))
            .thenThrow(new GitHubApiException(400, "FILE_TOO_LARGE", "File exceeds 2MB"));

        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        // Individual fallbacks were invoked
        verify(gitHubGateway).getFileContent("octocat", "hello-world", "src/main/java/com/example/GoodFile.java", commitSha);
        verify(gitHubGateway).getFileContent("octocat", "hello-world", "src/main/java/com/example/BadFile.java", commitSha);

        // PARTIAL status because BadFile download failed
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.PARTIAL);
        assertThat(result.getTotalFilesDiscovered()).isEqualTo(2);
        assertThat(result.getTotalFilesIndexed()).isEqualTo(1);
        assertThat(result.getTotalTypesIndexed()).isEqualTo(1);
        assertThat(result.getFailureReason()).contains("Some production files could not be retrieved");
    }

    @Test
    @DisplayName("PARTIAL outcome when Git tree is truncated or files exceed 5000 cap")
    void indexRepository_partialOutcome_whenTreeTruncatedOrCapped() {
        String commitSha = "d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(400L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(400L);
            return e;
        });
        when(repositoryIndexRepository.findById(400L)).thenReturn(Optional.of(claimed));

        // Truncated tree
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Service.java"), true));

        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Service.java", "package com.example; public class Service {}", commitSha)));

        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.PARTIAL);
        assertThat(result.isTruncatedOrCapped()).isTrue();
        assertThat(result.getFailureReason()).contains("Git tree was truncated");
    }

    @Test
    @DisplayName("PARTIAL outcome when source parsing errors occur on some files")
    void indexRepository_partialOutcome_whenParseErrorsOccur() {
        String commitSha = "e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(500L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(500L);
            return e;
        });
        when(repositoryIndexRepository.findById(500L)).thenReturn(Optional.of(claimed));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of(
                "src/main/java/com/example/Valid.java",
                "src/main/java/com/example/Invalid.java"
            ), false));

        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(
                new SourceFileContent("src/main/java/com/example/Valid.java", "package com.example; public class Valid {}", commitSha),
                new SourceFileContent("src/main/java/com/example/Invalid.java", "package com.example; public class { broken", commitSha)
            ));

        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of(
                new FileParseError("src/main/java/com/example/Invalid.java", "Syntax error at line 1")
            )));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.PARTIAL);
        assertThat(result.getTotalTypesIndexed()).isEqualTo(1); // Only Valid.java produced a type
        assertThat(result.getFailureReason()).contains("Some files contained syntax or parse errors");
    }

    @Test
    @DisplayName("FAILED outcome when Git tree retrieval fails")
    void indexRepository_failedOutcome_whenTreeRetrievalFails() {
        String commitSha = "f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(600L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(600L);
            return e;
        });
        when(repositoryIndexRepository.findById(600L)).thenReturn(Optional.of(claimed));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenThrow(new GitHubApiException(404, "NOT_FOUND", "Commit tree not found"));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(result.getFailureReason()).contains("Failed to retrieve recursive Git tree");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("FAILED outcome when no usable graph can be produced from non-empty codebase")
    void indexRepository_failedOutcome_whenNoUsableGraphFromNonEmptyCodebase() {
        String commitSha = "1234567890abcdef1234567890abcdef12345678";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(700L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(700L);
            return e;
        });
        when(repositoryIndexRepository.findById(700L)).thenReturn(Optional.of(claimed));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Broken.java"), false));

        // File download returns broken content
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Broken.java", "invalid java syntax }", commitSha)));

        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of(new FileParseError("Broken.java", "Syntax error"))));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(result.getFailureReason()).contains("No usable graph could be produced from non-empty production codebase");
    }

    @Test
    @DisplayName("Duplicate request returns existing COMPLETED index without duplicate work")
    void indexRepository_duplicateCompletedRequest_returnsExistingWithoutWork() {
        String commitSha = "completed1234567890abcdef1234567890abcde"; // 40 chars
        RepositoryIndexEntity existing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.COMPLETED);
        existing.setId(800L);
        existing.setTotalTypesIndexed(15);

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(existing));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result).isSameAs(existing);
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
        verify(dependencyAnalysisService, never()).analyze(anyList());
    }

    @Test
    @DisplayName("Active INDEXING request returns existing entity without duplicate work")
    void indexRepository_activeIndexing_returnsWithoutDuplicateWork() {
        String commitSha = "active1234567890abcdef1234567890abcdef12";
        RepositoryIndexEntity existing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        existing.setId(900L);
        existing.setStartedAt(Instant.now().minus(Duration.ofMinutes(5))); // 5 mins ago (< 30 min)

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(existing));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result).isSameAs(existing);
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("Stale INDEXING request (> 30 mins) is reclaimed and processed")
    void indexRepository_staleIndexing_reclaimsAndProcesses() {
        String commitSha = "stale1234567890abcdef1234567890abcdef123";
        RepositoryIndexEntity stale = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        stale.setId(1000L);
        stale.setStartedAt(Instant.now().minus(Duration.ofMinutes(45))); // 45 mins ago (> 30 min timeout)

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(stale));
        when(repositoryIndexRepository.findById(1000L)).thenReturn(Optional.of(stale));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // Should proceed with indexing
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", commitSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        // Verifies previous children cleared during reclaim and prior to final persistence
        verify(indexedTypeRepository, times(2)).deleteByIndexId(1000L);
        verify(indexedTypeDependencyRepository, times(2)).deleteByIndexId(1000L);
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
    }

    @Test
    @DisplayName("Concurrent insert conflict safely recovers and returns existing record")
    void indexRepository_concurrentInsertConflict_safelyRecovers() {
        String commitSha = "conflict1234567890abcdef1234567890abcdef";
        RepositoryIndexEntity existingConflict = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.COMPLETED);
        existingConflict.setId(1100L);

        // Initially not found, then found on conflict recovery
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty())
            .thenReturn(Optional.of(existingConflict));
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        // Insert throws DataIntegrityViolationException
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenThrow(new DataIntegrityViolationException("uk_repo_indices_repo_commit violation"));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result).isSameAs(existingConflict);
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("lastIndexedCommit is not updated for non-default branch or failed indexing")
    void indexRepository_lastIndexedCommit_notUpdatedForNonDefaultOrFailed() {
        String commitSha = "nondefault1234567890abcdef1234567890abcd";
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity claimed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        claimed.setId(1200L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1200L);
            return e;
        });
        when(repositoryIndexRepository.findById(1200L)).thenReturn(Optional.of(claimed));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", commitSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // Index on non-default branch: isDefaultBranch = false
        indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(repo.getLastIndexedCommit()).isNull();
        verify(repositoryEntityRepository, never()).saveAndFlush(repo);
    }

    @Test
    @DisplayName("Older webhook finishes after a newer commit: does not regress lastIndexedCommit")
    void olderWebhookFinishesAfterNewerCommit_doesNotRegressPointer() {
        String olderSha = "older11111111111111111111111111111111111"; // 40 chars
        String newerSha = "newer22222222222222222222222222222222222"; // 40 chars

        // Repository already has newerSha as lastIndexedCommit
        repo.setLastIndexedCommit(newerSha);

        when(repositoryIndexRepository.findForUpdate(42L, olderSha))
            .thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity olderIndex = new RepositoryIndexEntity(repo, olderSha, RepositoryIndexStatus.INDEXING);
        olderIndex.setId(10L);

        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(10L);
            return e;
        });
        when(repositoryIndexRepository.findById(10L)).thenReturn(Optional.of(olderIndex));

        when(gitHubGateway.getGitTree("octocat", "hello-world", olderSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(olderSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", olderSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // GitHub HEAD is newerSha, so olderSha is not the current default-branch HEAD
        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(newerSha);

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", olderSha, true);

        // Snapshot still succeeds as COMPLETED
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        // But lastIndexedCommit must remain newerSha and NOT regress to olderSha
        assertThat(repo.getLastIndexedCommit()).isEqualTo(newerSha);
        verify(repositoryEntityRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("Newer webhook finishes before an older commit: updates pointer, then older commit preserves it")
    void newerWebhookFinishesBeforeOlderCommit_updatesPointerThenPreserves() {
        String olderSha = "older11111111111111111111111111111111111";
        String newerSha = "newer22222222222222222222222222222222222";

        repo.setLastIndexedCommit(null);

        // 1. Newer commit finishes first
        when(repositoryIndexRepository.findForUpdate(42L, newerSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(100L);
            return e;
        });
        when(repositoryIndexRepository.findById(anyLong())).thenAnswer(inv -> {
            RepositoryIndexEntity e = new RepositoryIndexEntity(repo, "mock", RepositoryIndexStatus.INDEXING);
            e.setId(inv.getArgument(0));
            return Optional.of(e);
        });

        when(gitHubGateway.getGitTree("octocat", "hello-world", newerSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(newerSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", newerSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // GitHub HEAD is newerSha
        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(newerSha);
        when(repositoryEntityRepository.findByIdForUpdate(42L))
            .thenReturn(Optional.of(repo));

        indexingService.indexRepository(42L, "octocat", "hello-world", newerSha, true);
        assertThat(repo.getLastIndexedCommit()).isEqualTo(newerSha);

        // 2. Older commit finishes later
        when(repositoryIndexRepository.findForUpdate(42L, olderSha)).thenReturn(Optional.empty());
        when(gitHubGateway.getGitTree("octocat", "hello-world", olderSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(olderSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", olderSha)));

        indexingService.indexRepository(42L, "octocat", "hello-world", olderSha, true);

        // Pointer must remain newerSha
        assertThat(repo.getLastIndexedCommit()).isEqualTo(newerSha);
    }

    @Test
    @DisplayName("Current default-branch HEAD is updated correctly")
    void currentDefaultBranchHead_isUpdatedCorrectly() {
        String headSha = "head111111111111111111111111111111111111";
        repo.setLastIndexedCommit(null);

        when(repositoryIndexRepository.findForUpdate(42L, headSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, headSha, RepositoryIndexStatus.INDEXING);
        index.setId(50L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(50L);
            return e;
        });
        when(repositoryIndexRepository.findById(50L)).thenReturn(Optional.of(index));

        when(gitHubGateway.getGitTree("octocat", "hello-world", headSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(headSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", headSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(headSha);
        when(repositoryEntityRepository.findByIdForUpdate(42L))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", headSha, true);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        assertThat(repo.getLastIndexedCommit()).isEqualTo(headSha);
        verify(repositoryEntityRepository).saveAndFlush(repo);
    }

    @Test
    @DisplayName("Feature-branch indexing never updates the pointer")
    void featureBranchIndexing_neverUpdatesPointer() {
        String featureSha = "feat111111111111111111111111111111111111";
        String existingSha = "main111111111111111111111111111111111111";
        repo.setLastIndexedCommit(existingSha);

        when(repositoryIndexRepository.findForUpdate(42L, featureSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, featureSha, RepositoryIndexStatus.INDEXING);
        index.setId(60L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(60L);
            return e;
        });
        when(repositoryIndexRepository.findById(60L)).thenReturn(Optional.of(index));

        when(gitHubGateway.getGitTree("octocat", "hello-world", featureSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(featureSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", featureSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // Index on non-default branch: isDefaultBranch = false
        indexingService.indexRepository(42L, "octocat", "hello-world", featureSha, false);

        assertThat(repo.getLastIndexedCommit()).isEqualTo(existingSha);
        verify(gitHubGateway, never()).getBranchHeadCommit(any(), any(), any());
        verify(repositoryEntityRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("FAILED indexing never updates the pointer")
    void failedIndexing_neverUpdatesPointer() {
        String failedSha = "fail111111111111111111111111111111111111";
        String existingSha = "main111111111111111111111111111111111111";
        repo.setLastIndexedCommit(existingSha);

        when(repositoryIndexRepository.findForUpdate(42L, failedSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, failedSha, RepositoryIndexStatus.INDEXING);
        index.setId(70L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(70L);
            return e;
        });
        when(repositoryIndexRepository.findById(70L)).thenReturn(Optional.of(index));

        // Git tree retrieval fails
        when(gitHubGateway.getGitTree("octocat", "hello-world", failedSha))
            .thenThrow(new GitHubApiException(404, "NOT_FOUND", "Commit tree not found"));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", failedSha, true);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(repo.getLastIndexedCommit()).isEqualTo(existingSha);
        verify(gitHubGateway, never()).getBranchHeadCommit(any(), any(), any());
    }

    @Test
    @DisplayName("GitHub HEAD lookup failure preserves the existing pointer")
    void gitHubHeadLookupFailure_preservesExistingPointer() {
        String commitSha = "succ111111111111111111111111111111111111";
        String existingSha = "prev111111111111111111111111111111111111";
        repo.setLastIndexedCommit(existingSha);

        when(repositoryIndexRepository.findForUpdate(42L, commitSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        index.setId(80L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(80L);
            return e;
        });
        when(repositoryIndexRepository.findById(80L)).thenReturn(Optional.of(index));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", commitSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // GitHub HEAD lookup fails with network exception
        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenThrow(new GitHubApiException(503, "NETWORK_ERROR", "GitHub API network error"));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, true);

        // Snapshot is still successful (COMPLETED)
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        // Existing pointer is preserved
        assertThat(repo.getLastIndexedCommit()).isEqualTo(existingSha);
        verify(repositoryEntityRepository, never()).findByIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("Concurrent pointer updates cannot regress the value")
    void concurrentPointerUpdates_cannotRegressPointer() {
        String olderSha = "older11111111111111111111111111111111111";
        String newerSha = "newer22222222222222222222222222222222222";
        String initialSha = "init00000000000000000000000000000000000";

        repo.setLastIndexedCommit(initialSha);

        // Thread 1 (olderSha) starts update: sees initialSha in repo.
        // During Thread 1's initial check, GitHub returns olderSha (simulating race window where olderSha was HEAD)
        // But on Thread 1's retry after concurrent DB update detection, GitHub returns newerSha.
        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(olderSha)  // First check: saw olderSha
            .thenReturn(newerSha); // Retry check: sees newerSha

        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        // When Thread 1 acquires lock, DB has already been updated to newerSha by Thread 2
        RepositoryEntity lockedWithNewer = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        lockedWithNewer.setId(42L);
        lockedWithNewer.setLastIndexedCommit(newerSha);

        when(repositoryEntityRepository.findByIdForUpdate(42L))
            .thenReturn(Optional.of(lockedWithNewer));

        // Execute pointer update for olderSha
        indexingService.updateLastIndexedCommitSafely(42L, "octocat", "hello-world", olderSha);

        // Value must NOT have regressed to olderSha
        assertThat(lockedWithNewer.getLastIndexedCommit()).isEqualTo(newerSha);
    }

    @Test
    @DisplayName("Partial indexing on default branch updates pointer when it is current HEAD")
    void partialIndexingOnDefaultBranch_updatesPointerWhenHead() {
        String partialSha = "part111111111111111111111111111111111111";
        repo.setLastIndexedCommit(null);

        when(repositoryIndexRepository.findForUpdate(42L, partialSha)).thenReturn(Optional.empty());
        when(repositoryEntityRepository.findById(42L)).thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, partialSha, RepositoryIndexStatus.INDEXING);
        index.setId(90L);
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class))).thenAnswer(inv -> {
            RepositoryIndexEntity e = inv.getArgument(0);
            if (e.getId() == null) e.setId(90L);
            return e;
        });
        when(repositoryIndexRepository.findById(90L)).thenReturn(Optional.of(index));

        // Truncated tree -> PARTIAL status
        when(gitHubGateway.getGitTree("octocat", "hello-world", partialSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Foo.java"), true));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(partialSha)))
            .thenReturn(List.of(new SourceFileContent("src/main/java/com/example/Foo.java", "package com.example; public class Foo {}", partialSha)));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        when(gitHubGateway.getBranchHeadCommit("octocat", "hello-world", "main"))
            .thenReturn(partialSha);
        when(repositoryEntityRepository.findByIdForUpdate(42L))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", partialSha, true);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.PARTIAL);
        assertThat(repo.getLastIndexedCommit()).isEqualTo(partialSha);
        verify(repositoryEntityRepository).saveAndFlush(repo);
    }

    // =========================================================================
    // Part C Tests: Concurrency, Stale Snapshot Recovery & Reconciliation
    // =========================================================================

    @Test
    @DisplayName("Two concurrent requests claiming same stale snapshot: only first claims, second detects active indexing")
    void claimIndexingSnapshot_concurrentWorkers_staleSnapshot_onlyFirstWorkerClaims() {
        String commitSha = "staleconc1111111111111111111111111111111";
        RepositoryIndexEntity snapshot = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        snapshot.setId(2001L);
        snapshot.setStartedAt(Instant.now().minus(Duration.ofMinutes(45))); // Stale (> 30 min)

        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // Worker 1 acquires lock and sees stale snapshot
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(snapshot));

        when(repositoryIndexRepository.findById(2001L)).thenReturn(Optional.of(snapshot));
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of(), false));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // Worker 1 runs and reclaims snapshot
        RepositoryIndexEntity worker1Result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);
        assertThat(worker1Result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);

        // Snapshot now has fresh startedAt; Worker 2 acquires lock and sees it as actively indexing
        RepositoryIndexEntity activelyRunning = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        activelyRunning.setId(2001L);
        activelyRunning.setStartedAt(Instant.now()); // Fresh!

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(activelyRunning));

        // Worker 2 runs: should detect actively indexing and return immediately without duplicate ingestion
        RepositoryIndexEntity worker2Result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);
        assertThat(worker2Result).isSameAs(activelyRunning);

        // Verify git tree was only fetched once (by Worker 1)
        verify(gitHubGateway, times(1)).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("Two concurrent requests claiming same FAILED snapshot: only first claims, second detects active indexing")
    void claimIndexingSnapshot_concurrentWorkers_failedSnapshot_onlyFirstWorkerClaims() {
        String commitSha = "failedconc111111111111111111111111111111";
        RepositoryIndexEntity failedSnapshot = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        failedSnapshot.setId(2002L);
        failedSnapshot.setFailureReason("Previous connection timeout");

        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        // Worker 1 sees FAILED snapshot
        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(failedSnapshot));
        when(repositoryIndexRepository.findById(2002L)).thenReturn(Optional.of(failedSnapshot));
        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of(), false));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        // Worker 1 reclaims snapshot and proceeds
        RepositoryIndexEntity worker1Result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);
        assertThat(worker1Result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);

        // Worker 2 now sees the actively running snapshot with fresh startedAt
        RepositoryIndexEntity activelyRunning = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        activelyRunning.setId(2002L);
        activelyRunning.setStartedAt(Instant.now());

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(activelyRunning));

        RepositoryIndexEntity worker2Result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);
        assertThat(worker2Result).isSameAs(activelyRunning);
        verify(gitHubGateway, times(1)).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("Active INDEXING record (< staleTimeout) is not reclaimed")
    void claimIndexingSnapshot_activeIndexingRecord_isNotReclaimed() {
        String commitSha = "activecheck11111111111111111111111111111";
        RepositoryIndexEntity activeSnapshot = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        activeSnapshot.setId(2003L);
        activeSnapshot.setStartedAt(Instant.now().minus(Duration.ofMinutes(10))); // 10 mins ago (< 30 min)

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(activeSnapshot));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result).isSameAs(activeSnapshot);
        // Children are never deleted
        verify(indexedTypeRepository, never()).deleteByIndexId(anyLong());
        verify(indexedTypeDependencyRepository, never()).deleteByIndexId(anyLong());
        // Git tree is never fetched
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
    }

    @Test
    @DisplayName("Stale INDEXING records transition to FAILED via reconcileStaleIndexes")
    void reconcileStaleIndexes_transitionsStaleIndexingRecordsToFailed() {
        Instant executionTime = Instant.parse("2026-10-30T12:00:00Z");
        Duration timeout = Duration.ofMinutes(30);
        Instant expectedCutoff = executionTime.minus(timeout);

        when(repositoryIndexRepository.reconcileStaleIndexes(
            eq(RepositoryIndexStatus.INDEXING),
            eq(expectedCutoff),
            eq(RepositoryIndexStatus.FAILED),
            eq(executionTime),
            eq(RepositoryIndexingService.DEFAULT_STALE_INDEX_FAILURE_REASON)
        )).thenReturn(3);

        int count = indexingService.reconcileStaleIndexes(timeout, executionTime);

        assertThat(count).isEqualTo(3);
        verify(repositoryIndexRepository).reconcileStaleIndexes(
            eq(RepositoryIndexStatus.INDEXING),
            eq(expectedCutoff),
            eq(RepositoryIndexStatus.FAILED),
            eq(executionTime),
            eq(RepositoryIndexingService.DEFAULT_STALE_INDEX_FAILURE_REASON)
        );
    }

    @Test
    @DisplayName("Recent INDEXING records remain untouched during reconciliation")
    void reconcileStaleIndexes_recentIndexingRecords_remainUntouched() {
        Instant executionTime = Instant.now();
        Duration timeout = Duration.ofMinutes(30);

        // When cutoff is properly applied, the DB query updates only records older than cutoff.
        // If there are no stale records (only recent ones), 0 rows are updated.
        when(repositoryIndexRepository.reconcileStaleIndexes(
            eq(RepositoryIndexStatus.INDEXING),
            any(Instant.class),
            eq(RepositoryIndexStatus.FAILED),
            eq(executionTime),
            eq(RepositoryIndexingService.DEFAULT_STALE_INDEX_FAILURE_REASON)
        )).thenReturn(0);

        int count = indexingService.reconcileStaleIndexes(timeout, executionTime);

        assertThat(count).isEqualTo(0);
        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(repositoryIndexRepository).reconcileStaleIndexes(
            eq(RepositoryIndexStatus.INDEXING),
            cutoffCaptor.capture(),
            eq(RepositoryIndexStatus.FAILED),
            eq(executionTime),
            eq(RepositoryIndexingService.DEFAULT_STALE_INDEX_FAILURE_REASON)
        );
        assertThat(cutoffCaptor.getValue()).isEqualTo(executionTime.minus(timeout));
    }

    @Test
    @DisplayName("Completed and PARTIAL records remain untouched during claiming and reconciliation")
    void completedAndPartialRecords_remainUntouched() {
        String completedSha = "comp111111111111111111111111111111111111";
        String partialSha = "part222222222222222222222222222222222222";

        RepositoryIndexEntity comp = new RepositoryIndexEntity(repo, completedSha, RepositoryIndexStatus.COMPLETED);
        comp.setId(2004L);
        RepositoryIndexEntity part = new RepositoryIndexEntity(repo, partialSha, RepositoryIndexStatus.PARTIAL);
        part.setId(2005L);

        when(repositoryIndexRepository.findForUpdate(42L, completedSha)).thenReturn(Optional.of(comp));
        when(repositoryIndexRepository.findForUpdate(42L, partialSha)).thenReturn(Optional.of(part));

        RepositoryIndexEntity compResult = indexingService.indexRepository(42L, "octocat", "hello-world", completedSha, false);
        RepositoryIndexEntity partResult = indexingService.indexRepository(42L, "octocat", "hello-world", partialSha, false);

        assertThat(compResult).isSameAs(comp);
        assertThat(partResult).isSameAs(part);
        verify(gitHubGateway, never()).getGitTree(any(), any(), any());
        verify(indexedTypeRepository, never()).deleteByIndexId(anyLong());
    }

    @Test
    @DisplayName("Reconciliation is idempotent across multiple invocations")
    void reconcileStaleIndexes_isIdempotent() {
        when(repositoryIndexRepository.reconcileStaleIndexes(any(), any(), any(), any(), any()))
            .thenReturn(2)  // First run cleans up 2 stale records
            .thenReturn(0); // Second run finds 0 stale records

        int firstRun = indexingService.reconcileStaleIndexes();
        int secondRun = indexingService.reconcileStaleIndexes();

        assertThat(firstRun).isEqualTo(2);
        assertThat(secondRun).isEqualTo(0);
        verify(repositoryIndexRepository, times(2)).reconcileStaleIndexes(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("A reclaimed snapshot can subsequently be indexed successfully")
    void indexRepository_reclaimedSnapshot_canSubsequentlyBeIndexedSuccessfully() {
        String commitSha = "reclaimsuccess11111111111111111111111111";
        RepositoryIndexEntity failed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.FAILED);
        failed.setId(2006L);
        failed.setFailureReason("Previous failure");

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(failed));
        when(repositoryIndexRepository.findById(2006L)).thenReturn(Optional.of(failed));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        when(gitHubGateway.getGitTree("octocat", "hello-world", commitSha))
            .thenReturn(new GitTreeResult(List.of("src/main/java/com/example/Reclaimed.java"), false));
        when(gitHubGateway.getFilesContent(eq("octocat"), eq("hello-world"), anyList(), eq(commitSha)))
            .thenReturn(List.of(new SourceFileContent(
                "src/main/java/com/example/Reclaimed.java",
                "package com.example; public class Reclaimed {}",
                commitSha
            )));
        when(dependencyAnalysisService.analyze(anyList()))
            .thenReturn(new DependencyAnalysisResult(List.of(), List.of()));

        RepositoryIndexEntity result = indexingService.indexRepository(42L, "octocat", "hello-world", commitSha, false);

        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        assertThat(result.getTotalTypesIndexed()).isEqualTo(1);
        assertThat(result.getFailureReason()).isNull();
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("failSnapshotIfIndexing transitions active INDEXING snapshot to FAILED with sanitized reason")
    void failSnapshotIfIndexing_activeSnapshot_transitionsToFailed() {
        String commitSha = "faillock111111111111111111111111111111111";
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.INDEXING);
        indexing.setId(3001L);

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(indexing));
        when(repositoryIndexRepository.saveAndFlush(any(RepositoryIndexEntity.class)))
            .thenAnswer(inv -> inv.getArgument(0));

        RepositoryIndexEntity failed = indexingService.failSnapshotIfIndexing(42L, commitSha, "Asynchronous task failure: timeout");

        assertThat(failed).isNotNull();
        assertThat(failed.getStatus()).isEqualTo(RepositoryIndexStatus.FAILED);
        assertThat(failed.getFailureReason()).isEqualTo("Asynchronous task failure: timeout");
        verify(repositoryIndexRepository).saveAndFlush(indexing);
    }

    @Test
    @DisplayName("failSnapshotIfIndexing does not overwrite already completed snapshot")
    void failSnapshotIfIndexing_completedSnapshot_isUntouched() {
        String commitSha = "completed11111111111111111111111111111111";
        RepositoryIndexEntity completed = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.COMPLETED);
        completed.setId(3002L);

        when(repositoryIndexRepository.findForUpdate(42L, commitSha))
            .thenReturn(Optional.of(completed));

        RepositoryIndexEntity result = indexingService.failSnapshotIfIndexing(42L, commitSha, "Asynchronous failure");

        assertThat(result).isSameAs(completed);
        assertThat(result.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        verify(repositoryIndexRepository, never()).saveAndFlush(any());
    }
}
