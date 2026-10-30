package com.changescope.repository;

import com.changescope.model.IndexedTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface IndexedTypeRepository extends JpaRepository<IndexedTypeEntity, Long> {

    List<IndexedTypeEntity> findByIndexId(Long indexId);

    List<IndexedTypeEntity> findByIndexIdOrderByFullyQualifiedNameAsc(Long indexId);

    Optional<IndexedTypeEntity> findByIndexIdAndFullyQualifiedName(Long indexId, String fullyQualifiedName);

    long countByIndexId(Long indexId);

    long deleteByIndexId(Long indexId);
}
