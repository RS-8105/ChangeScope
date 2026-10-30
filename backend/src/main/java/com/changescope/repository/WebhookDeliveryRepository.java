package com.changescope.repository;

import com.changescope.model.WebhookDeliveryEntity;
import com.changescope.model.WebhookDeliveryStatus;
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
public interface WebhookDeliveryRepository extends JpaRepository<WebhookDeliveryEntity, Long> {

    boolean existsByDeliveryId(String deliveryId);

    Optional<WebhookDeliveryEntity> findByDeliveryId(String deliveryId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE WebhookDeliveryEntity w SET w.status = :newStatus, w.failureSummary = null, w.receivedAt = :now WHERE w.deliveryId = :deliveryId AND w.status = :failedStatus AND w.retryable = true")
    int claimForRetry(
        @Param("deliveryId") String deliveryId,
        @Param("newStatus") WebhookDeliveryStatus newStatus,
        @Param("failedStatus") WebhookDeliveryStatus failedStatus,
        @Param("now") Instant now
    );

    @Query("SELECT w.id FROM WebhookDeliveryEntity w " +
           "WHERE w.receivedAt < :cutoff " +
           "ORDER BY w.id ASC")
    List<Long> findExpiredDeliveryIds(
        @Param("cutoff") Instant cutoff,
        Pageable pageable
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM WebhookDeliveryEntity w WHERE w.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
