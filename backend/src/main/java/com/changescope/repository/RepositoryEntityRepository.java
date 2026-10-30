package com.changescope.repository;

import com.changescope.model.RepositoryEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface RepositoryEntityRepository extends JpaRepository<RepositoryEntity, Long> {

    boolean existsByOwnerAndName(String owner, String name);

    Optional<RepositoryEntity> findByOwnerAndName(String owner, String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RepositoryEntity r WHERE r.id = :id")
    Optional<RepositoryEntity> findByIdForUpdate(@Param("id") Long id);
}
