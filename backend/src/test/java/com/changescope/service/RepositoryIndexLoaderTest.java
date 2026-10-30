package com.changescope.service;

import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeKind;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RepositoryIndexLoaderTest {

    private RepositoryEntityRepository repositoryEntityRepository;
    private RepositoryIndexRepository repositoryIndexRepository;
    private IndexedTypeRepository indexedTypeRepository;
    private IndexedTypeDependencyRepository indexedTypeDependencyRepository;
    private RepositoryIndexLoader loader;

    @BeforeEach
    void setUp() {
        repositoryEntityRepository = mock(RepositoryEntityRepository.class);
        repositoryIndexRepository = mock(RepositoryIndexRepository.class);
        indexedTypeRepository = mock(IndexedTypeRepository.class);
        indexedTypeDependencyRepository = mock(IndexedTypeDependencyRepository.class);

        loader = new RepositoryIndexLoader(
            repositoryEntityRepository,
            repositoryIndexRepository,
            indexedTypeRepository,
            indexedTypeDependencyRepository
        );
    }

    @Test
    @DisplayName("Exact commit SHA lookup succeeds and never uses lastIndexedCommit")
    void loadIndex_usesExactCommitShaAndIgnoresLastIndexedCommit() {
        RepositoryEntity repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
        repo.setLastIndexedCommit("older_commit_sha_999");

        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(
            repo,
            "exact_base_sha_123",
            RepositoryIndexStatus.COMPLETED
        );
        index.setId(101L);
        index.setTotalFilesDiscovered(100);
        index.setTotalFilesIndexed(95);

        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "exact_base_sha_123"))
            .thenReturn(Optional.of(index));

        IndexedTypeEntity type = new IndexedTypeEntity(
            index,
            "com.example.OrderService",
            "OrderService",
            "com.example",
            "src/OrderService.java",
            TypeKind.CLASS
        );
        when(indexedTypeRepository.findByIndexId(101L)).thenReturn(List.of(type));

        IndexedTypeDependencyEntity dep = new IndexedTypeDependencyEntity(
            index,
            "com.example.OrderController",
            "com.example.OrderService",
            RelationshipKind.FIELD,
            "src/OrderController.java"
        );
        when(indexedTypeDependencyRepository.findByIndexId(101L)).thenReturn(List.of(dep));

        RepositoryIndexLoadResult result = loader.loadIndex("octocat", "hello-world", "exact_base_sha_123");

        assertThat(result).isInstanceOf(RepositoryIndexLoadResult.Usable.class);
        RepositoryIndexLoadResult.Usable usable = (RepositoryIndexLoadResult.Usable) result;
        assertThat(usable.commitSha()).isEqualTo("exact_base_sha_123");
        assertThat(usable.status()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        assertThat(usable.isComplete()).isTrue();
        assertThat(usable.totalFilesDiscovered()).isEqualTo(100);
        assertThat(usable.totalFilesIndexed()).isEqualTo(95);

        // Assert mapping into BaseGraphSnapshot
        assertThat(usable.snapshot().indexedTypes()).hasSize(1);
        assertThat(usable.snapshot().indexedTypes().get(0).fullyQualifiedName()).isEqualTo("com.example.OrderService");
        assertThat(usable.snapshot().dependencies()).hasSize(1);
        assertThat(usable.snapshot().dependencies().get(0).sourceType()).isEqualTo("com.example.OrderController");

        // Verify that lastIndexedCommit was never queried or used
        verify(repositoryIndexRepository, never()).findByRepositoryIdAndCommitSha(eq(42L), eq("older_commit_sha_999"));
    }

    @Test
    @DisplayName("Eligible status: PARTIAL is usable but marked incomplete")
    void loadIndex_whenPartial_returnsUsableWithIncomplete() {
        RepositoryEntity repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        RepositoryIndexEntity index = new RepositoryIndexEntity(
            repo,
            "partial_base_sha",
            RepositoryIndexStatus.PARTIAL
        );
        index.setId(102L);
        index.setTruncatedOrCapped(true);
        index.setTotalFilesDiscovered(500);
        index.setTotalFilesIndexed(200);

        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "partial_base_sha"))
            .thenReturn(Optional.of(index));
        when(indexedTypeRepository.findByIndexId(102L)).thenReturn(List.of());
        when(indexedTypeDependencyRepository.findByIndexId(102L)).thenReturn(List.of());

        RepositoryIndexLoadResult result = loader.loadIndex("octocat", "hello-world", "partial_base_sha");

        assertThat(result).isInstanceOf(RepositoryIndexLoadResult.Usable.class);
        RepositoryIndexLoadResult.Usable usable = (RepositoryIndexLoadResult.Usable) result;
        assertThat(usable.status()).isEqualTo(RepositoryIndexStatus.PARTIAL);
        assertThat(usable.isTruncatedOrCapped()).isTrue();
        assertThat(usable.isComplete()).isFalse();
    }

    @Test
    @DisplayName("Ineligible statuses: PENDING, INDEXING, FAILED are rejected and do not query types/dependencies")
    void loadIndex_whenIneligible_returnsUnusableWithoutQueryingChildren() {
        RepositoryEntity repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));

        // 1. INDEXING
        RepositoryIndexEntity indexing = new RepositoryIndexEntity(repo, "sha_indexing", RepositoryIndexStatus.INDEXING);
        indexing.setId(201L);
        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "sha_indexing"))
            .thenReturn(Optional.of(indexing));

        RepositoryIndexLoadResult resIndexing = loader.loadIndex("octocat", "hello-world", "sha_indexing");
        assertThat(resIndexing).isInstanceOf(RepositoryIndexLoadResult.Unusable.class);
        assertThat(((RepositoryIndexLoadResult.Unusable) resIndexing).status()).isEqualTo(RepositoryIndexStatus.INDEXING);

        // 2. PENDING
        RepositoryIndexEntity pending = new RepositoryIndexEntity(repo, "sha_pending", RepositoryIndexStatus.PENDING);
        pending.setId(202L);
        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "sha_pending"))
            .thenReturn(Optional.of(pending));

        RepositoryIndexLoadResult resPending = loader.loadIndex("octocat", "hello-world", "sha_pending");
        assertThat(resPending).isInstanceOf(RepositoryIndexLoadResult.Unusable.class);
        assertThat(((RepositoryIndexLoadResult.Unusable) resPending).status()).isEqualTo(RepositoryIndexStatus.PENDING);

        // 3. FAILED
        RepositoryIndexEntity failed = new RepositoryIndexEntity(repo, "sha_failed", RepositoryIndexStatus.FAILED);
        failed.setId(203L);
        failed.setFailureReason("Syntax error limit exceeded");
        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "sha_failed"))
            .thenReturn(Optional.of(failed));

        RepositoryIndexLoadResult resFailed = loader.loadIndex("octocat", "hello-world", "sha_failed");
        assertThat(resFailed).isInstanceOf(RepositoryIndexLoadResult.Unusable.class);
        assertThat(((RepositoryIndexLoadResult.Unusable) resFailed).reason()).contains("Syntax error limit exceeded");

        // Verify child queries were never executed for any ineligible status
        verify(indexedTypeRepository, never()).findByIndexId(anyLong());
        verify(indexedTypeDependencyRepository, never()).findByIndexId(anyLong());
    }

    @Test
    @DisplayName("Missing repository or missing index returns NotFound")
    void loadIndex_whenMissing_returnsNotFound() {
        // Missing repo
        when(repositoryEntityRepository.findByOwnerAndName("unknown", "repo"))
            .thenReturn(Optional.empty());
        RepositoryIndexLoadResult noRepo = loader.loadIndex("unknown", "repo", "sha123");
        assertThat(noRepo).isInstanceOf(RepositoryIndexLoadResult.NotFound.class);
        assertThat(((RepositoryIndexLoadResult.NotFound) noRepo).reason()).contains("not registered");

        // Registered repo but missing commit index
        RepositoryEntity repo = new RepositoryEntity("octocat", "hello-world", "main", 12345L);
        repo.setId(42L);
        when(repositoryEntityRepository.findByOwnerAndName("octocat", "hello-world"))
            .thenReturn(Optional.of(repo));
        when(repositoryIndexRepository.findByRepositoryIdAndCommitSha(42L, "missing_sha"))
            .thenReturn(Optional.empty());

        RepositoryIndexLoadResult noIndex = loader.loadIndex("octocat", "hello-world", "missing_sha");
        assertThat(noIndex).isInstanceOf(RepositoryIndexLoadResult.NotFound.class);
        assertThat(((RepositoryIndexLoadResult.NotFound) noIndex).reason()).contains("No persistent index found");
    }
}
