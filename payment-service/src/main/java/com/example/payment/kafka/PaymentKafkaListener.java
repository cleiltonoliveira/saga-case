package com.example.payment.kafka;

import com.example.payment.domain.PaymentEntity;
import com.example.payment.repository.PaymentRepository;
import com.example.saga.common.domain.InboxMessage;
import com.example.saga.common.repo.InboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class PaymentKafkaListener {
    private final PaymentRepository paymentRepository;
    private final InboxRepository inboxRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private static final Logger logger = LoggerFactory.getLogger(PaymentKafkaListener.class);

    public PaymentKafkaListener(PaymentRepository paymentRepository, InboxRepository inboxRepository) {
        this.paymentRepository = paymentRepository;
        this.inboxRepository = inboxRepository;
    }

    @KafkaListener(topics = "OrderCreated", groupId = "saga-poc-group")
    @Transactional
    public void listen(ConsumerRecord<String, String> record) throws Exception {
        String messageId = record.key();
        String payload = record.value();


        if (messageId == null) {
            logger.info("Message received with null ID, generating one");
            // safety: if no key, generate one (but inbox dedup won't work across restarts)
            messageId = java.util.UUID.randomUUID().toString();
        }

        logger.info("Received new message, ID: {}, payload: {}", messageId, payload);

        if (inboxRepository.existsById(messageId)) {
            logger.info("Message with ID: {} already exists", messageId);
            return;
        }
        InboxMessage im = new InboxMessage();
        im.setMessageId(messageId);
        im.setType("OrderCreated");
        inboxRepository.save(im);
        logger.info("InboxMessage created with ID: {}", im.getMessageId());

        JsonNode node = objectMapper.readTree(payload);
        Long orderId = node.get("orderId").asLong();
        Double amount = node.get("amount").asDouble();

        PaymentEntity p = new PaymentEntity();
        p.setOrderId(orderId);
        p.setAmount(amount);
        p.setStatus("COMPLETED");

        ///  cadê a emissão do evento de que o evento foi concluído???
        paymentRepository.save(p);

        logger.info("Order {} has been payed with payment ID: {}", p.getOrderId(), p.getId());
    }
}
