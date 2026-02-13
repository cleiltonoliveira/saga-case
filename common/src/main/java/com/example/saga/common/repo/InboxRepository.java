package com.example.saga.common.repo;

import com.example.saga.common.domain.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboxRepository extends JpaRepository<InboxMessage, String> {
}

