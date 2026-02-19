package com.example.saga.common.repo;

import com.example.saga.common.domain.OutboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxMessage, Long> {
    List<OutboxMessage> findByTypeAndPublishedFalseOrderByCreatedAtAsc(String aggregateType);
}

