package com.changescope.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.Objects;

@Entity
@Table(
    name = "webhook_deliveries",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_webhook_deliveries_delivery_id", columnNames = {"delivery_id"})
    },
    indexes = {
        @Index(name = "idx_webhook_deliveries_delivery_id", columnList = "delivery_id")
    }
)
public class WebhookDeliveryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Delivery ID must not be blank")
    @Size(max = 128, message = "Delivery ID cannot exceed 128 characters")
    @Column(name = "delivery_id", nullable = false, length = 128, unique = true, updatable = false)
    private String deliveryId;

    @NotBlank(message = "Event name must not be blank")
    @Size(max = 100, message = "Event name cannot exceed 100 characters")
    @Column(name = "event", nullable = false, length = 100, updatable = false)
    private String event;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private WebhookDeliveryStatus status = WebhookDeliveryStatus.RECEIVED;

    @Column(name = "failure_summary", length = 1000)
    private String failureSummary;

    @Column(name = "retryable", nullable = false)
    private boolean retryable = false;

    public WebhookDeliveryEntity() {
    }

    public WebhookDeliveryEntity(String deliveryId, String event, Instant receivedAt, WebhookDeliveryStatus status) {
        this(deliveryId, event, receivedAt, status, null, false);
    }

    public WebhookDeliveryEntity(String deliveryId, String event, Instant receivedAt, WebhookDeliveryStatus status, String failureSummary) {
        this(deliveryId, event, receivedAt, status, failureSummary, false);
    }

    public WebhookDeliveryEntity(String deliveryId, String event, Instant receivedAt, WebhookDeliveryStatus status, String failureSummary, boolean retryable) {
        this.deliveryId = deliveryId;
        this.event = event;
        this.receivedAt = receivedAt != null ? receivedAt : Instant.now();
        this.status = status != null ? status : WebhookDeliveryStatus.RECEIVED;
        this.failureSummary = failureSummary;
        this.retryable = retryable;
    }

    @PrePersist
    protected void onCreate() {
        if (this.receivedAt == null) {
            this.receivedAt = Instant.now();
        }
        if (this.status == null) {
            this.status = WebhookDeliveryStatus.RECEIVED;
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getDeliveryId() {
        return deliveryId;
    }

    public void setDeliveryId(String deliveryId) {
        this.deliveryId = deliveryId;
    }

    public String getEvent() {
        return event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setReceivedAt(Instant receivedAt) {
        this.receivedAt = receivedAt;
    }

    public WebhookDeliveryStatus getStatus() {
        return status;
    }

    public void setStatus(WebhookDeliveryStatus status) {
        this.status = status;
    }

    public String getFailureSummary() {
        return failureSummary;
    }

    public void setFailureSummary(String failureSummary) {
        this.failureSummary = failureSummary;
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof WebhookDeliveryEntity that)) return false;
        return Objects.equals(deliveryId, that.deliveryId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(deliveryId);
    }
}
