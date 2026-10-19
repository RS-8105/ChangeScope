package com.changescope.repository;

import com.changescope.model.RepositoryEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class RepositoryEntityRepositoryTest {

    @Autowired
    private RepositoryEntityRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveAndFindById_persistsEntityWithTimestamps() {
        RepositoryEntity entity = new RepositoryEntity("changescope-org", "inventory-core", "main", 202L);

        RepositoryEntity saved = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<RepositoryEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getOwner()).isEqualTo("changescope-org");
        assertThat(found.get().getName()).isEqualTo("inventory-core");
        assertThat(found.get().getFullName()).isEqualTo("changescope-org/inventory-core");
        assertThat(found.get().getInstallationId()).isNull();
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    @Test
    void saveAndFindById_withInstallationId_persistsAndRetrievesInstallationId() {
        RepositoryEntity entity = new RepositoryEntity("changescope-org", "inventory-core", "main", 202L, 7777L);

        RepositoryEntity saved = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<RepositoryEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getGithubId()).isEqualTo(202L);
        assertThat(found.get().getInstallationId()).isEqualTo(7777L);
        assertThat(found.get().getInstallationId()).isNotEqualTo(found.get().getGithubId());
    }

    @Test
    void existsByOwnerAndName_returnsTrueWhenExists() {
        RepositoryEntity entity = new RepositoryEntity("changescope-org", "payment-service", "main", 303L);
        repository.save(entity);
        entityManager.flush();

        assertThat(repository.existsByOwnerAndName("changescope-org", "payment-service")).isTrue();
        assertThat(repository.existsByOwnerAndName("changescope-org", "non-existent")).isFalse();
    }

    @Test
    void uniqueConstraint_ownerAndName_throwsDataIntegrityViolation() {
        RepositoryEntity first = new RepositoryEntity("changescope-org", "shared-lib", "main", 101L);
        repository.save(first);
        entityManager.flush();

        RepositoryEntity duplicate = new RepositoryEntity("changescope-org", "shared-lib", "develop", 102L);

        assertThatThrownBy(() -> {
            repository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
