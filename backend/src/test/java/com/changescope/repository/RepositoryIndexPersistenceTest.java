package com.changescope.repository;

import com.changescope.dto.RelationshipKind;
import com.changescope.dto.TypeKind;
import com.changescope.model.IndexedTypeDependencyEntity;
import com.changescope.model.IndexedTypeEntity;
import com.changescope.model.RepositoryEntity;
import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class RepositoryIndexPersistenceTest {

    @Autowired
    private RepositoryEntityRepository repositoryEntityRepository;

    @Autowired
    private RepositoryIndexRepository repositoryIndexRepository;

    @Autowired
    private IndexedTypeRepository indexedTypeRepository;

    @Autowired
    private IndexedTypeDependencyRepository indexedTypeDependencyRepository;

    @Autowired
    private TestEntityManager entityManager;

    private RepositoryEntity repository;

    @BeforeEach
    void setUp() {
        repository = repositoryEntityRepository.save(
            new RepositoryEntity("changescope-org", "order-service", "main", 1001L)
        );
        entityManager.flush();
    }

    @Test
    void saveAndRetrieveIndex_success_persistsMetadataTypesAndDependencies() {
        String commitSha = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2";
        Instant startedAt = Instant.now().minusSeconds(15);
        Instant completedAt = Instant.now();

        RepositoryIndexEntity index = new RepositoryIndexEntity(repository, commitSha, RepositoryIndexStatus.COMPLETED);
        index.setTotalFilesDiscovered(10);
        index.setTotalFilesIndexed(10);
        index.setTruncatedOrCapped(false);
        index.setStartedAt(startedAt);
        index.setCompletedAt(completedAt);

        IndexedTypeEntity typeService = new IndexedTypeEntity(
            index,
            "com.changescope.order.OrderService",
            "OrderService",
            "com.changescope.order",
            "src/main/java/com/changescope/order/OrderService.java",
            TypeKind.CLASS
        );

        IndexedTypeEntity typeRepo = new IndexedTypeEntity(
            index,
            "com.changescope.order.OrderRepository",
            "OrderRepository",
            "com.changescope.order",
            "src/main/java/com/changescope/order/OrderRepository.java",
            TypeKind.INTERFACE
        );

        IndexedTypeDependencyEntity dep = new IndexedTypeDependencyEntity(
            index,
            "com.changescope.order.OrderService",
            "com.changescope.order.OrderRepository",
            RelationshipKind.FIELD,
            "src/main/java/com/changescope/order/OrderService.java"
        );

        index.addIndexedType(typeService);
        index.addIndexedType(typeRepo);
        index.addIndexedDependency(dep);

        RepositoryIndexEntity savedIndex = repositoryIndexRepository.save(index);
        entityManager.flush();
        entityManager.clear();

        // 1. Verify index metadata retrieval
        Optional<RepositoryIndexEntity> retrieved = repositoryIndexRepository.findByRepositoryIdAndCommitSha(
            repository.getId(), commitSha
        );
        assertThat(retrieved).isPresent();
        RepositoryIndexEntity foundIndex = retrieved.get();
        assertThat(foundIndex.getId()).isEqualTo(savedIndex.getId());
        assertThat(foundIndex.getCommitSha()).isEqualTo(commitSha);
        assertThat(foundIndex.getStatus()).isEqualTo(RepositoryIndexStatus.COMPLETED);
        assertThat(foundIndex.getTotalFilesDiscovered()).isEqualTo(10);
        assertThat(foundIndex.getTotalFilesIndexed()).isEqualTo(10);
        assertThat(foundIndex.getTotalTypesIndexed()).isEqualTo(2);
        assertThat(foundIndex.getTotalDependenciesIndexed()).isEqualTo(1);
        assertThat(foundIndex.isTruncatedOrCapped()).isFalse();
        assertThat(foundIndex.getStartedAt()).isNotNull();
        assertThat(foundIndex.getCompletedAt()).isNotNull();
        assertThat(foundIndex.getCreatedAt()).isNotNull();

        // 2. Verify indexed types retrieval
        List<IndexedTypeEntity> types = indexedTypeRepository.findByIndexId(foundIndex.getId());
        assertThat(types).hasSize(2);
        assertThat(types).extracting(IndexedTypeEntity::getFullyQualifiedName)
            .containsExactlyInAnyOrder(
                "com.changescope.order.OrderService",
                "com.changescope.order.OrderRepository"
            );
        assertThat(types).extracting(IndexedTypeEntity::getTypeKind)
            .containsExactlyInAnyOrder(TypeKind.CLASS, TypeKind.INTERFACE);

        // 3. Verify indexed dependencies and reverse lookup
        List<IndexedTypeDependencyEntity> deps = indexedTypeDependencyRepository.findByIndexId(foundIndex.getId());
        assertThat(deps).hasSize(1);
        assertThat(deps.get(0).getSourceType()).isEqualTo("com.changescope.order.OrderService");
        assertThat(deps.get(0).getTargetType()).isEqualTo("com.changescope.order.OrderRepository");
        assertThat(deps.get(0).getRelationshipKind()).isEqualTo(RelationshipKind.FIELD);

        List<IndexedTypeDependencyEntity> reverseDeps = indexedTypeDependencyRepository.findByIndexIdAndTargetType(
            foundIndex.getId(), "com.changescope.order.OrderRepository"
        );
        assertThat(reverseDeps).hasSize(1);
        assertThat(reverseDeps.get(0).getSourceType()).isEqualTo("com.changescope.order.OrderService");
    }

    @Test
    void duplicateRepoAndCommitSha_violatesUniqueConstraint() {
        String commitSha = "b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3";

        RepositoryIndexEntity firstIndex = new RepositoryIndexEntity(
            repository, commitSha, RepositoryIndexStatus.COMPLETED
        );
        repositoryIndexRepository.save(firstIndex);
        entityManager.flush();

        RepositoryIndexEntity duplicateIndex = new RepositoryIndexEntity(
            repository, commitSha, RepositoryIndexStatus.PENDING
        );

        assertThatThrownBy(() -> {
            repositoryIndexRepository.save(duplicateIndex);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deleteRepository_cascadesAndDeletesAllAssociatedIndicesAndEdges() {
        String commitSha = "c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4";

        RepositoryIndexEntity index = new RepositoryIndexEntity(
            repository, commitSha, RepositoryIndexStatus.COMPLETED
        );

        IndexedTypeEntity type = new IndexedTypeEntity(
            index,
            "com.changescope.order.OrderService",
            "OrderService",
            "com.changescope.order",
            "src/main/java/com/changescope/order/OrderService.java",
            TypeKind.CLASS
        );

        IndexedTypeDependencyEntity dep = new IndexedTypeDependencyEntity(
            index,
            "com.changescope.order.OrderService",
            "com.changescope.order.OrderRepository",
            RelationshipKind.FIELD,
            "src/main/java/com/changescope/order/OrderService.java"
        );

        index.addIndexedType(type);
        index.addIndexedDependency(dep);

        RepositoryIndexEntity savedIndex = repositoryIndexRepository.save(index);
        entityManager.flush();
        entityManager.clear();

        Long repoId = repository.getId();
        Long indexId = savedIndex.getId();

        // Verify entities exist prior to deletion
        assertThat(repositoryIndexRepository.findById(indexId)).isPresent();
        assertThat(indexedTypeRepository.findByIndexId(indexId)).hasSize(1);
        assertThat(indexedTypeDependencyRepository.findByIndexId(indexId)).hasSize(1);

        // Delete parent repository
        repositoryEntityRepository.deleteById(repoId);
        entityManager.flush();
        entityManager.clear();

        // Verify repository, associated index, and all child types & edges were cascaded and deleted
        assertThat(repositoryEntityRepository.findById(repoId)).isEmpty();
        assertThat(repositoryIndexRepository.findById(indexId)).isEmpty();
        assertThat(indexedTypeRepository.findByIndexId(indexId)).isEmpty();
        assertThat(indexedTypeDependencyRepository.findByIndexId(indexId)).isEmpty();
    }
}
