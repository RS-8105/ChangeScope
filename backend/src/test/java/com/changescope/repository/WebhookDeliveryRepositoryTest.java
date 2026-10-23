package com.changescope.repository;

import com.changescope.model.WebhookDeliveryEntity;
import com.changescope.model.WebhookDeliveryStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
class WebhookDeliveryRepositoryTest {

    @Autowired
    private WebhookDeliveryRepository repository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void saveAndFindById_persistsMetadataCorrectly() {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity(
            "del-12345",
            "pull_request",
            Instant.now(),
            WebhookDeliveryStatus.RECEIVED
        );

        WebhookDeliveryEntity saved = repository.save(entity);
        entityManager.flush();
        entityManager.clear();

        Optional<WebhookDeliveryEntity> found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getDeliveryId()).isEqualTo("del-12345");
        assertThat(found.get().getEvent()).isEqualTo("pull_request");
        assertThat(found.get().getStatus()).isEqualTo(WebhookDeliveryStatus.RECEIVED);
        assertThat(found.get().getReceivedAt()).isNotNull();
    }

    @Test
    void existsByDeliveryId_returnsTrueWhenExists() {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity(
            "del-exists-test",
            "ping",
            Instant.now(),
            WebhookDeliveryStatus.PROCESSED
        );
        repository.save(entity);
        entityManager.flush();

        assertThat(repository.existsByDeliveryId("del-exists-test")).isTrue();
        assertThat(repository.existsByDeliveryId("del-non-existent")).isFalse();
    }

    @Test
    void uniqueConstraint_duplicateDeliveryId_throwsDataIntegrityViolation() {
        WebhookDeliveryEntity first = new WebhookDeliveryEntity(
            "del-duplicate-test",
            "pull_request",
            Instant.now(),
            WebhookDeliveryStatus.RECEIVED
        );
        repository.save(first);
        entityManager.flush();

        WebhookDeliveryEntity duplicate = new WebhookDeliveryEntity(
            "del-duplicate-test",
            "pull_request",
            Instant.now(),
            WebhookDeliveryStatus.RECEIVED
        );

        assertThatThrownBy(() -> {
            repository.save(duplicate);
            entityManager.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void claimForRetry_whenFailedAndRetryable_updatesStatusAndClearsFailureSummary() {
        WebhookDeliveryEntity entity = new WebhookDeliveryEntity(
            "del-claim-test",
            "pull_request",
            Instant.now().minusSeconds(60),
            WebhookDeliveryStatus.FAILED,
            "Transient failure",
            true
        );
        repository.save(entity);
        entityManager.flush();

        Instant now = Instant.now();
        int claimed = repository.claimForRetry(
            "del-claim-test",
            WebhookDeliveryStatus.RECEIVED,
            WebhookDeliveryStatus.FAILED,
            now
        );

        assertThat(claimed).isEqualTo(1);

        entityManager.clear();
        WebhookDeliveryEntity updated = repository.findByDeliveryId("del-claim-test").orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(WebhookDeliveryStatus.RECEIVED);
        assertThat(updated.getFailureSummary()).isNull();
    }

    @Test
    void claimForRetry_whenNotFailedOrNotRetryable_returnsZero() {
        // Case 1: Status is PROCESSED
        WebhookDeliveryEntity processed = new WebhookDeliveryEntity(
            "del-processed-test",
            "pull_request",
            Instant.now(),
            WebhookDeliveryStatus.PROCESSED,
            null,
            false
        );
        repository.save(processed);

        // Case 2: Status is FAILED but NOT retryable
        WebhookDeliveryEntity permanentFail = new WebhookDeliveryEntity(
            "del-permanent-test",
            "pull_request",
            Instant.now(),
            WebhookDeliveryStatus.FAILED,
            "Permanent failure",
            false
        );
        repository.save(permanentFail);
        entityManager.flush();

        Instant now = Instant.now();
        int claimedProcessed = repository.claimForRetry(
            "del-processed-test",
            WebhookDeliveryStatus.RECEIVED,
            WebhookDeliveryStatus.FAILED,
            now
        );
        int claimedPermanent = repository.claimForRetry(
            "del-permanent-test",
            WebhookDeliveryStatus.RECEIVED,
            WebhookDeliveryStatus.FAILED,
            now
        );

        assertThat(claimedProcessed).isZero();
        assertThat(claimedPermanent).isZero();
    }
}
