package com.changescope.repository;

import com.changescope.model.IndexedTypeDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IndexedTypeDependencyRepository extends JpaRepository<IndexedTypeDependencyEntity, Long> {

    List<IndexedTypeDependencyEntity> findByIndexId(Long indexId);

    List<IndexedTypeDependencyEntity> findByIndexIdAndTargetType(Long indexId, String targetType);

    List<IndexedTypeDependencyEntity> findByIndexIdAndSourceType(Long indexId, String sourceType);

    long countByIndexId(Long indexId);

    long deleteByIndexId(Long indexId);
}
