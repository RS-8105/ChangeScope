package com.changescope.repository;

import com.changescope.model.PullRequestAnalysisEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PullRequestAnalysisRepository extends JpaRepository<PullRequestAnalysisEntity, Long> {

    Page<PullRequestAnalysisEntity> findByRepositoryId(Long repositoryId, Pageable pageable);

    Page<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDesc(Long repositoryId, Pageable pageable);

    List<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDescIdDesc(Long repositoryId);

    List<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDesc(Long repositoryId);

    Optional<PullRequestAnalysisEntity> findByIdAndRepositoryId(Long id, Long repositoryId);

    void deleteByRepositoryId(Long repositoryId);
}
