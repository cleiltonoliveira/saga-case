package com.example.orchestrator.saga;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OrderSagaRepository extends JpaRepository<OrderSaga, UUID> {
    OrderSaga findByOrderId(Long orderId);
}

