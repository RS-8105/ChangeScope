package com.changescope.repository;

import com.changescope.model.RepositoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepositoryEntityRepository extends JpaRepository<RepositoryEntity, Long> {

    boolean existsByOwnerAndName(String owner, String name);

    Optional<RepositoryEntity> findByOwnerAndName(String owner, String name);
}
