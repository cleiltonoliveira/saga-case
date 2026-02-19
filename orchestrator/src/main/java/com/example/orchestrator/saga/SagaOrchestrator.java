package com.example.orchestrator.saga;

import com.example.saga.common.domain.InboxMessage;
import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.InboxRepository;
import com.example.saga.common.repo.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;

@Component
public class SagaOrchestrator {
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderSagaRepository orderSagaRepository;
    private final InboxRepository inboxRepository;
    private static final Logger logger = LoggerFactory.getLogger(SagaOrchestrator.class);

    public SagaOrchestrator(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate, OrderSagaRepository orderSagaRepository, InboxRepository inboxRepository) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.orderSagaRepository = orderSagaRepository;
        this.inboxRepository = inboxRepository;
    }

    private boolean eventAlreadyReceived(String messageId) {
        return inboxRepository.existsById(messageId);
    }

    @KafkaListener(topics = "order_created", groupId = "saga-poc-group")
    @Transactional
    public void onOrderCreated(@Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String messageId, String payload) {
        logger.info("Orchestrator received OrderCreated messageId={} payload={}", messageId, payload);

        try {
            if (eventAlreadyReceived(messageId)) {
                return;
            }

            // cria o inbox para evitar duplicação.
            InboxMessage im = new InboxMessage();
            im.setMessageId(messageId);
            im.setType("OrderCreated");
            inboxRepository.save(im);
            logger.info("InboxMessage created with ID: {}", im.getMessageId());

            // parse payload to get orderId
            var mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(payload);
            Long orderId = node.get("orderId").asLong();

            // create saga
            OrderSaga saga = new OrderSaga();
            saga.setOrderId(orderId);
            saga.setStatus(SagaStatus.PAYMENT_REQUESTED);
            saga.setCurrentStep("PAYMENT_REQUESTED");
            orderSagaRepository.save(saga);
            logger.info("Saga created with status {}", saga.getStatus());

            // create outbox message to request payment
            OutboxMessage m = new OutboxMessage();
            m.setAggregateType("saga");
            m.setAggregateId(String.valueOf(saga.getSagaId()));

            m.setType("payment_requested");
            m.setPayload("{\"sagaId\":\"" + saga.getSagaId() + "\",\"orderId\":" + orderId + "}");
            outboxRepository.save(m);

            logger.info("Saga {} created for order {} ", saga.getSagaId(), orderId);
        } catch (Exception e) {
            logger.error("Failed to process OrderCreated", e);
        }
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollOutbox() {
        List<OutboxMessage> pending = outboxRepository.findByTypeAndPublishedFalseOrderByCreatedAtAsc("payment_requested");

        if (!pending.isEmpty()) {
            logger.info("{} pending OutboxMessage of type payment_requested to process", pending.size());
        }
        for (OutboxMessage m : pending) {
            try {
                String topic = m.getType();
                String messageId = java.util.UUID.randomUUID().toString();
                logger.info("Sending message {} of type payment_requested to the payment service", messageId);
                var result = kafkaTemplate.send(topic, messageId, m.getPayload()).get();

                m.setPublished(true);
                outboxRepository.save(m);

                logger.info("Message {} of type payment_requested published", messageId);
            } catch (Exception e) {
                logger.warn("Failed to publish outbox message immediately, will be picked by poller later", e);
            }
        }
    }

    @KafkaListener(topics = "payment_completed", groupId = "saga-poc-group")
    @Transactional
    public void onPaymentCompleted(@Header(KafkaHeaders.RECEIVED_MESSAGE_KEY) String messageId, String payload) {
        logger.info("Orchestrator received payment_completed messageId={} payload={}", messageId, payload);
        try {

            if (eventAlreadyReceived(messageId)) {
                return;
            }

            // cria o inbox para evitar duplicação.
            InboxMessage im = new InboxMessage();
            im.setMessageId(messageId);
            im.setType("payment_completed");
            inboxRepository.save(im);
            logger.info("InboxMessage of type payment_completed created with ID: {}", im.getMessageId());

            var mapper = new ObjectMapper();
            JsonNode node = mapper.readTree(payload);
            UUID sagaId = UUID.fromString(node.get("sagaId").asText());

            // essa checagem é necessária?
            OrderSaga saga = orderSagaRepository.findById(sagaId).orElse(null);
            if (saga == null) {
                logger.warn("Saga {} not found", sagaId);
                return;
            }

            saga.setStatus(SagaStatus.PAYMENT_COMPLETED);
            saga.setCurrentStep("PAYMENT_COMPLETED");
            orderSagaRepository.save(saga);

            logger.info("Saga {} has been finished and status is PAYMENT_COMPLETED", sagaId);
        } catch (Exception e) {
            logger.error("Failed to process PaymentCompleted", e);
        }

    }

}
