package com.example.payment.kafka;

import com.example.payment.domain.PaymentEntity;
import com.example.payment.repository.PaymentRepository;
import com.example.saga.common.domain.InboxMessage;
import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.InboxRepository;
import com.example.saga.common.repo.OutboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

@Component
public class PaymentKafkaListener {
    private final PaymentRepository paymentRepository;
    private final InboxRepository inboxRepository;
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(PaymentKafkaListener.class);

    public PaymentKafkaListener(PaymentRepository paymentRepository, InboxRepository inboxRepository, OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate) {
        this.paymentRepository = paymentRepository;
        this.inboxRepository = inboxRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Escuta mensagens no tópico payment_requested, checa se é duplicata através do mecanismo inbox;
     * Salva pagamento, cria mensagem de outbox, que será publicada pelo poller
     */
    @KafkaListener(topics = "payment_requested", groupId = "saga-poc-group")
    @Transactional
    public void listen(ConsumerRecord<String, String> record) throws Exception {
        String messageId = record.key();
        String payload = record.value();

        logger.info("Received new message, ID: {}, payload: {}", messageId, payload);

        if (inboxRepository.existsById(messageId)) {
            logger.info("Message with ID: {} already exists", messageId);
            return;
        }
        InboxMessage im = new InboxMessage();
        im.setMessageId(messageId);
        im.setType("PaymentRequested");
        inboxRepository.save(im);
        logger.info("InboxMessage created with ID: {}", im.getMessageId());

        JsonNode node = objectMapper.readTree(payload);
        Long orderId = node.get("orderId").asLong();

        // simulate a payment process
        PaymentEntity p = new PaymentEntity();
        p.setOrderId(orderId);
        p.setAmount(0.0);
        p.setStatus("COMPLETED");

        paymentRepository.save(p);

        // create outbox event PaymentCompleted
        OutboxMessage m = new OutboxMessage();
        m.setAggregateType("payment");
        m.setAggregateId(String.valueOf(p.getId()));
        m.setType("payment_completed");
        // include sagaId if present
        String sagaId = node.has("sagaId") ? node.get("sagaId").asText() : null;
        m.setPayload("{\"sagaId\":\"" + (sagaId != null ? sagaId : "") + "\",\"orderId\":" + orderId + "}");
        outboxRepository.save(m);

        logger.info("Order {} has been paid with payment ID: {} and payment_completed outbox created", p.getOrderId(), p.getId());
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollOutbox() {
        List<OutboxMessage> pending = outboxRepository.findByTypeAndPublishedFalseOrderByCreatedAtAsc("payment_completed");

        if (!pending.isEmpty()) {
            logger.info("{} pending OutboxMessage of type payment_completed to process", pending.size());
        }
        for (OutboxMessage m : pending) {
            try {
                String topic = m.getType();
                String messageId = java.util.UUID.randomUUID().toString();

                logger.info("Sending message {} of type payment_completed to the orchestrator service", messageId);
                var result = kafkaTemplate.send(topic, messageId, m.getPayload()).get();

                m.setPublished(true);
                outboxRepository.save(m);

                logger.info("Message {} of type payment_completed published", messageId);
            } catch (Exception e) {
                logger.warn("Failed to publish outbox message immediately, will be picked by poller later", e);
            }
        }
    }
}
