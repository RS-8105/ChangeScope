package com.changescope.repository;

import com.changescope.model.RepositoryIndexEntity;
import com.changescope.model.RepositoryIndexStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface RepositoryIndexRepository extends JpaRepository<RepositoryIndexEntity, Long> {

    Optional<RepositoryIndexEntity> findByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM RepositoryIndexEntity r WHERE r.repository.id = :repositoryId AND r.commitSha = :commitSha")
    Optional<RepositoryIndexEntity> findForUpdate(
        @Param("repositoryId") Long repositoryId,
        @Param("commitSha") String commitSha
    );

    boolean existsByRepositoryIdAndCommitSha(Long repositoryId, String commitSha);

    List<RepositoryIndexEntity> findByRepositoryId(Long repositoryId);

    List<RepositoryIndexEntity> findByRepositoryIdOrderByCreatedAtDesc(Long repositoryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RepositoryIndexEntity r " +
           "SET r.status = :targetStatus, " +
           "    r.completedAt = :completedAt, " +
           "    r.failureReason = :failureReason, " +
           "    r.attemptToken = null " +
           "WHERE r.status = :indexingStatus " +
           "  AND ((r.startedAt IS NOT NULL AND r.startedAt < :cutoff) OR (r.startedAt IS NULL AND r.createdAt < :cutoff))")
    int reconcileStaleIndexes(
        @Param("indexingStatus") RepositoryIndexStatus indexingStatus,
        @Param("cutoff") Instant cutoff,
        @Param("targetStatus") RepositoryIndexStatus targetStatus,
        @Param("completedAt") Instant completedAt,
        @Param("failureReason") String failureReason
    );

    @Query("SELECT r FROM RepositoryIndexEntity r " +
           "WHERE r.status = :indexingStatus " +
           "  AND ((r.startedAt IS NOT NULL AND r.startedAt < :cutoff) OR (r.startedAt IS NULL AND r.createdAt < :cutoff)) " +
           "ORDER BY r.startedAt ASC NULLS FIRST, r.id ASC")
    List<RepositoryIndexEntity> findStaleIndexes(
        @Param("indexingStatus") RepositoryIndexStatus indexingStatus,
        @Param("cutoff") Instant cutoff,
        org.springframework.data.domain.Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RepositoryIndexEntity r " +
           "SET r.status = :failedStatus, " +
           "    r.completedAt = :completedAt, " +
           "    r.failureReason = :failureReason, " +
           "    r.retryable = :retryable, " +
           "    r.retryCount = :newRetryCount, " +
           "    r.nextRetryAt = :nextRetryAt, " +
           "    r.attemptToken = null " +
           "WHERE r.id = :id " +
           "  AND r.status = :indexingStatus " +
           "  AND ((r.startedAt IS NOT NULL AND r.startedAt < :cutoff) OR (r.startedAt IS NULL AND r.createdAt < :cutoff))")
    int reconcileStaleIndexById(
        @Param("id") Long id,
        @Param("indexingStatus") RepositoryIndexStatus indexingStatus,
        @Param("cutoff") Instant cutoff,
        @Param("failedStatus") RepositoryIndexStatus failedStatus,
        @Param("completedAt") Instant completedAt,
        @Param("failureReason") String failureReason,
        @Param("retryable") boolean retryable,
        @Param("newRetryCount") int newRetryCount,
        @Param("nextRetryAt") Instant nextRetryAt
    );

    @Query("SELECT r FROM RepositoryIndexEntity r " +
           "WHERE r.status = :failedStatus " +
           "  AND r.retryable = true " +
           "  AND r.nextRetryAt IS NOT NULL " +
           "  AND r.nextRetryAt <= :now " +
           "ORDER BY r.nextRetryAt ASC")
    List<RepositoryIndexEntity> findDueRetries(
        @Param("failedStatus") RepositoryIndexStatus failedStatus,
        @Param("now") Instant now,
        org.springframework.data.domain.Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RepositoryIndexEntity r " +
           "SET r.status = :indexingStatus, " +
           "    r.startedAt = :now, " +
           "    r.nextRetryAt = null, " +
           "    r.attemptToken = :attemptToken " +
           "WHERE r.id = :id " +
           "  AND r.status = :failedStatus " +
           "  AND r.retryable = true " +
           "  AND r.nextRetryAt <= :now")
    int claimDueRetry(
        @Param("id") Long id,
        @Param("failedStatus") RepositoryIndexStatus failedStatus,
        @Param("indexingStatus") RepositoryIndexStatus indexingStatus,
        @Param("now") Instant now,
        @Param("attemptToken") String attemptToken
    );

    default int claimDueRetry(Long id, RepositoryIndexStatus failedStatus, RepositoryIndexStatus indexingStatus, Instant now) {
        return claimDueRetry(id, failedStatus, indexingStatus, now, java.util.UUID.randomUUID().toString());
    }

    long countByRepositoryId(Long repositoryId);

    long deleteByRepositoryId(Long repositoryId);
}
