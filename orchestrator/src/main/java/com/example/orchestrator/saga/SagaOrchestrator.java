package com.example.orchestrator.saga;

import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.OutboxRepository;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
public class SagaOrchestrator {
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public SagaOrchestrator(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 1000)
    @Transactional
    public void pollOutbox() {
        List<OutboxMessage> pending = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxMessage m : pending) {
            String topic = m.getType();
            String messageId = java.util.UUID.randomUUID().toString();
            kafkaTemplate.send(topic, messageId, m.getPayload());
            m.setPublished(true);
            outboxRepository.save(m);
        }
    }
}
