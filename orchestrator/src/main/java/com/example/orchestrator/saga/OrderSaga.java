package com.example.orchestrator.saga;

import javax.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "order_saga")
public class OrderSaga {
    @Id
    private UUID sagaId = UUID.randomUUID();

    private Long orderId;

    @Enumerated(EnumType.STRING)
    private SagaStatus status;

    private String currentStep;

    private Integer retryCount = 0;

    private Boolean compensating = false;

    private Instant createdAt = Instant.now();

    private Instant updatedAt = Instant.now();

    public UUID getSagaId() { return sagaId; }
    public void setSagaId(UUID sagaId) { this.sagaId = sagaId; }
    public Long getOrderId() { return orderId; }
    public void setOrderId(Long orderId) { this.orderId = orderId; }
    public SagaStatus getStatus() { return status; }
    public void setStatus(SagaStatus status) { this.status = status; }
    public String getCurrentStep() { return currentStep; }
    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    public Boolean getCompensating() { return compensating; }
    public void setCompensating(Boolean compensating) { this.compensating = compensating; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

