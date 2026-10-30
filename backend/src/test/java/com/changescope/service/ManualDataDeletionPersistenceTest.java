package com.changescope.service;

import com.changescope.config.AnalysisProperties;
import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeKind;
import com.changescope.exception.ResourceNotFoundException;
import com.changescope.model.AnalysisStatus;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.PullRequestAnalysisEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import com.changescope.repository.IndexedTypeDependencyRepository;
import com.changescope.repository.IndexedTypeRepository;
import com.changescope.repository.PullRequestAnalysisRepository;
import com.changescope.repository.RepositoryEntityRepository;
import com.changescope.repository.RepositoryIndexRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({RepositoryService.class, PullRequestAnalysisService.class, ObjectMapper.class, AnalysisProperties.class})
class ManualDataDeletionPersistenceTest {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private PullRequestAnalysisService pullRequestAnalysisService;

    @Autowired
    private RepositoryEntityRepository repositoryEntityRepository;

    @Autowired
    private RepositoryIndexRepository repositoryIndexRepository;

    @Autowired
    private IndexedTypeRepository indexedTypeRepository;

    @Autowired
    private IndexedTypeDependencyRepository indexedTypeDependencyRepository;

    @Autowired
    private PullRequestAnalysisRepository pullRequestAnalysisRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private RepositoryEntity repo1;
    private RepositoryEntity repo2;

    @BeforeEach
    void setUp() {
        repo1 = repositoryEntityRepository.save(new RepositoryEntity("org-alpha", "service-alpha", "main", 101L));
        repo2 = repositoryEntityRepository.save(new RepositoryEntity("org-beta", "service-beta", "main", 102L));
        entityManager.flush();
    }

    private PullRequestAnalysisEntity createAnalysis(RepositoryEntity repo, int pullNumber, AnalysisStatus status) {
        PullRequestAnalysisEntity entity = new PullRequestAnalysisEntity(
            repo,
            pullNumber,
            "head" + pullNumber,
            "base" + pullNumber,
            status,
            Instant.now(),
            Instant.now(),
            "{\"mock\":\"snapshot\"}"
        );
        return pullRequestAnalysisRepository.save(entity);
    }

    private RepositoryIndexEntity createIndexWithTypesAndDeps(RepositoryEntity repo, String commitSha) {
        RepositoryIndexEntity index = new RepositoryIndexEntity(repo, commitSha, RepositoryIndexStatus.COMPLETED);
        IndexedTypeEntity type1 = new IndexedTypeEntity(
            index, "com.example.ServiceA", "ServiceA", "com.example", "src/ServiceA.java", TypeKind.CLASS
        );
        IndexedTypeEntity type2 = new IndexedTypeEntity(
            index, "com.example.RepoA", "RepoA", "com.example", "src/RepoA.java", TypeKind.INTERFACE
        );
        IndexedTypeDependencyEntity dep = new IndexedTypeDependencyEntity(
            index, "com.example.ServiceA", "com.example.RepoA", RelationshipKind.FIELD, "src/ServiceA.java"
        );
        index.addIndexedType(type1);
        index.addIndexedType(type2);
        index.addIndexedDependency(dep);
        return repositoryIndexRepository.save(index);
    }

    @Test
    void deleteIndividualAnalysis_success_deletesTargetAnalysisOnly() {
        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 10, AnalysisStatus.COMPLETED);
        PullRequestAnalysisEntity a2 = createAnalysis(repo1, 11, AnalysisStatus.FAILED);
        RepositoryIndexEntity idx = createIndexWithTypesAndDeps(repo1, "commit111");
        entityManager.flush();

        pullRequestAnalysisService.deleteAnalysis(repo1.getId(), a1.getId());
        entityManager.flush();
        entityManager.clear();

        // a1 is deleted
        assertThat(pullRequestAnalysisRepository.findById(a1.getId())).isEmpty();
        // a2 remains intact
        assertThat(pullRequestAnalysisRepository.findById(a2.getId())).isPresent();
        // repo1 remains intact
        assertThat(repositoryEntityRepository.findById(repo1.getId())).isPresent();
        // repo1 index and types remain intact
        assertThat(repositoryIndexRepository.findById(idx.getId())).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(idx.getId())).hasSize(2);
    }

    @Test
    void deleteIndividualAnalysis_ownershipMismatch_throwsResourceNotFoundAndPreservesData() {
        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 10, AnalysisStatus.COMPLETED);
        PullRequestAnalysisEntity a2 = createAnalysis(repo2, 20, AnalysisStatus.COMPLETED);
        entityManager.flush();

        // Attempting to delete repo2's analysis through repo1
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAnalysis(repo1.getId(), a2.getId()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("does not belong to repository");

        entityManager.flush();
        entityManager.clear();

        // Both analyses are preserved
        assertThat(pullRequestAnalysisRepository.findById(a1.getId())).isPresent();
        assertThat(pullRequestAnalysisRepository.findById(a2.getId())).isPresent();
    }

    @Test
    void deleteIndividualAnalysis_missingAndInvalidIds_failSafely() {
        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 10, AnalysisStatus.COMPLETED);
        entityManager.flush();

        // Invalid IDs
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAnalysis(0L, a1.getId()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAnalysis(repo1.getId(), -5L))
            .isInstanceOf(IllegalArgumentException.class);

        // Missing repository
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAnalysis(999999L, a1.getId()))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found");

        // Missing analysis
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAnalysis(repo1.getId(), 888888L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Analysis not found");
    }

    @Test
    void deleteAllRepositoryAnalyses_success_deletesAllAnalysesWhilePreservingRepoAndIndex() {
        createAnalysis(repo1, 1, AnalysisStatus.COMPLETED);
        createAnalysis(repo1, 2, AnalysisStatus.FAILED);
        createAnalysis(repo1, 3, AnalysisStatus.RUNNING);
        RepositoryIndexEntity idx1 = createIndexWithTypesAndDeps(repo1, "commit111");

        createAnalysis(repo2, 10, AnalysisStatus.COMPLETED);
        createAnalysis(repo2, 11, AnalysisStatus.FAILED);
        RepositoryIndexEntity idx2 = createIndexWithTypesAndDeps(repo2, "commit222");
        entityManager.flush();

        int deleted = pullRequestAnalysisService.deleteAllAnalyses(repo1.getId());
        entityManager.flush();
        entityManager.clear();

        assertThat(deleted).isEqualTo(3);
        // Repo1 has no analyses remaining
        assertThat(pullRequestAnalysisRepository.findByRepositoryIdOrderByStartedAtDesc(repo1.getId())).isEmpty();
        // Repo1 still exists
        assertThat(repositoryEntityRepository.findById(repo1.getId())).isPresent();
        // Repo1 index and child records still exist
        assertThat(repositoryIndexRepository.findById(idx1.getId())).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(idx1.getId())).hasSize(2);
        assertThat(indexedTypeDependencyRepository.findByIndexId(idx1.getId())).hasSize(1);

        // Repo2 analyses and index are completely untouched!
        assertThat(pullRequestAnalysisRepository.findByRepositoryIdOrderByStartedAtDesc(repo2.getId())).hasSize(2);
        assertThat(repositoryIndexRepository.findById(idx2.getId())).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(idx2.getId())).hasSize(2);
    }

    @Test
    void deleteAllRepositoryAnalyses_missingRepo_throwsResourceNotFound() {
        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAllAnalyses(999999L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found");

        assertThatThrownBy(() -> pullRequestAnalysisService.deleteAllAnalyses(0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteRepositoryIndex_success_deletesIndicesTypesAndDepsWhilePreservingRepoAndAnalyses() {
        repo1.setLastIndexedCommit("commit111");
        repo1 = repositoryEntityRepository.save(repo1);

        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 1, AnalysisStatus.COMPLETED);
        RepositoryIndexEntity idx1 = createIndexWithTypesAndDeps(repo1, "commit111");

        repo2.setLastIndexedCommit("commit222");
        repo2 = repositoryEntityRepository.save(repo2);
        PullRequestAnalysisEntity a2 = createAnalysis(repo2, 10, AnalysisStatus.COMPLETED);
        RepositoryIndexEntity idx2 = createIndexWithTypesAndDeps(repo2, "commit222");
        entityManager.flush();

        repositoryService.deleteRepositoryIndex(repo1.getId());
        entityManager.flush();
        entityManager.clear();

        // Repo1 index, types, and dependencies deleted
        assertThat(repositoryIndexRepository.findById(idx1.getId())).isEmpty();
        assertThat(indexedTypeRepository.findByIndexId(idx1.getId())).isEmpty();
        assertThat(indexedTypeDependencyRepository.findByIndexId(idx1.getId())).isEmpty();

        // Repo1 record preserved, but lastIndexedCommit reset to null
        RepositoryEntity refreshedRepo1 = repositoryEntityRepository.findById(repo1.getId()).orElseThrow();
        assertThat(refreshedRepo1.getLastIndexedCommit()).isNull();

        // Repo1 analysis preserved
        assertThat(pullRequestAnalysisRepository.findById(a1.getId())).isPresent();

        // Repo2 completely untouched
        RepositoryEntity refreshedRepo2 = repositoryEntityRepository.findById(repo2.getId()).orElseThrow();
        assertThat(refreshedRepo2.getLastIndexedCommit()).isEqualTo("commit222");
        assertThat(repositoryIndexRepository.findById(idx2.getId())).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(idx2.getId())).hasSize(2);
        assertThat(indexedTypeDependencyRepository.findByIndexId(idx2.getId())).hasSize(1);
        assertThat(pullRequestAnalysisRepository.findById(a2.getId())).isPresent();
    }

    @Test
    void deleteRepositoryIndex_idempotentWhenNoIndicesExist() {
        repo1.setLastIndexedCommit(null);
        repositoryEntityRepository.save(repo1);
        entityManager.flush();

        // Call once
        repositoryService.deleteRepositoryIndex(repo1.getId());
        // Call twice
        repositoryService.deleteRepositoryIndex(repo1.getId());
        entityManager.flush();
        entityManager.clear();

        RepositoryEntity refreshed = repositoryEntityRepository.findById(repo1.getId()).orElseThrow();
        assertThat(refreshed.getLastIndexedCommit()).isNull();
    }

    @Test
    void deleteRepository_cascadesAllAssociatedRecordsCleanlyWithoutOrphans() {
        repo1.setLastIndexedCommit("commit111");
        repo1 = repositoryEntityRepository.save(repo1);
        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 1, AnalysisStatus.COMPLETED);
        PullRequestAnalysisEntity a2 = createAnalysis(repo1, 2, AnalysisStatus.FAILED);
        RepositoryIndexEntity idx1 = createIndexWithTypesAndDeps(repo1, "commit111");

        repo2.setLastIndexedCommit("commit222");
        repo2 = repositoryEntityRepository.save(repo2);
        PullRequestAnalysisEntity a3 = createAnalysis(repo2, 10, AnalysisStatus.COMPLETED);
        RepositoryIndexEntity idx2 = createIndexWithTypesAndDeps(repo2, "commit222");
        entityManager.flush();

        repositoryService.deleteRepository(repo1.getId());
        entityManager.flush();
        entityManager.clear();

        // Repo1 and all child records are completely deleted
        assertThat(repositoryEntityRepository.findById(repo1.getId())).isEmpty();
        assertThat(pullRequestAnalysisRepository.findById(a1.getId())).isEmpty();
        assertThat(pullRequestAnalysisRepository.findById(a2.getId())).isEmpty();
        assertThat(repositoryIndexRepository.findById(idx1.getId())).isEmpty();
        assertThat(indexedTypeRepository.findByIndexId(idx1.getId())).isEmpty();
        assertThat(indexedTypeDependencyRepository.findByIndexId(idx1.getId())).isEmpty();

        // Repo2 and all its child records remain completely untouched!
        assertThat(repositoryEntityRepository.findById(repo2.getId())).isPresent();
        assertThat(pullRequestAnalysisRepository.findById(a3.getId())).isPresent();
        assertThat(repositoryIndexRepository.findById(idx2.getId())).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(idx2.getId())).hasSize(2);
        assertThat(indexedTypeDependencyRepository.findByIndexId(idx2.getId())).hasSize(1);
    }

    @Test
    void deleteRepository_missingAndInvalidIds_throwsException() {
        assertThatThrownBy(() -> repositoryService.deleteRepository(999999L))
            .isInstanceOf(ResourceNotFoundException.class)
            .hasMessageContaining("Repository not found");

        assertThatThrownBy(() -> repositoryService.deleteRepository(0L))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void foreignKeyIntegrityAndTransactionRollback_onException_preservesAllData() {
        PullRequestAnalysisEntity a1 = createAnalysis(repo1, 1, AnalysisStatus.COMPLETED);
        RepositoryIndexEntity idx1 = createIndexWithTypesAndDeps(repo1, "commit111");
        entityManager.flush();

        DefaultTransactionDefinition def = new DefaultTransactionDefinition();
        def.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        TransactionTemplate txTemplate = new TransactionTemplate(transactionManager, def);

        // Execute a transaction that deletes repo1's analyses and then throws an exception
        assertThatThrownBy(() -> {
            txTemplate.execute(status -> {
                pullRequestAnalysisRepository.deleteByRepositoryId(repo1.getId());
                throw new RuntimeException("Simulated unexpected failure during deletion");
            });
        }).isInstanceOf(RuntimeException.class).hasMessageContaining("Simulated unexpected failure");

        entityManager.clear();

        // Verify transaction rolled back: analysis, repo, and index records are completely preserved
        assertThat(pullRequestAnalysisRepository.findById(a1.getId())).isPresent();
        assertThat(repositoryEntityRepository.findById(repo1.getId())).isPresent();
        assertThat(repositoryIndexRepository.findById(idx1.getId())).isPresent();
    }
}
