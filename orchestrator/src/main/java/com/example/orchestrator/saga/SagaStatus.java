package com.example.orchestrator.saga;

public enum SagaStatus {
    STARTED,
    PAYMENT_REQUESTED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    INVENTORY_REQUESTED,
    INVENTORY_RESERVED,
    INVENTORY_FAILED,
    COMPLETED,
    COMPENSATING,
    FAILED
}

