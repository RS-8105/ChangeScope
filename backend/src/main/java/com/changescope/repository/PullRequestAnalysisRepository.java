package com.changescope.repository;

import com.changescope.model.AnalysisStatus;
import com.changescope.model.PullRequestAnalysisEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PullRequestAnalysisRepository extends JpaRepository<PullRequestAnalysisEntity, Long> {

    Page<PullRequestAnalysisEntity> findByRepositoryId(Long repositoryId, Pageable pageable);

    Page<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDesc(Long repositoryId, Pageable pageable);

    List<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDescIdDesc(Long repositoryId);

    List<PullRequestAnalysisEntity> findByRepositoryIdOrderByStartedAtDesc(Long repositoryId);

    Optional<PullRequestAnalysisEntity> findByIdAndRepositoryId(Long id, Long repositoryId);

    List<PullRequestAnalysisEntity> findByStatusAndStartedAtBefore(AnalysisStatus status, Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE PullRequestAnalysisEntity a " +
           "SET a.status = :targetStatus, " +
           "    a.completedAt = :completedAt, " +
           "    a.failureSummary = :failureSummary " +
           "WHERE a.status = :runningStatus " +
           "  AND a.startedAt < :cutoff")
    int reconcileStaleAnalyses(
        @Param("runningStatus") AnalysisStatus runningStatus,
        @Param("cutoff") Instant cutoff,
        @Param("targetStatus") AnalysisStatus targetStatus,
        @Param("completedAt") Instant completedAt,
        @Param("failureSummary") String failureSummary
    );

    void deleteByRepositoryId(Long repositoryId);
}
