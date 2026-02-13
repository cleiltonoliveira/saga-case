package com.example.payment.kafka;

import com.example.payment.domain.PaymentEntity;
import com.example.payment.repository.PaymentRepository;
import com.example.saga.common.domain.InboxMessage;
import com.example.saga.common.repo.InboxRepository;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
            // safety: if no key, generate one (but inbox dedup won't work across restarts)
            messageId = java.util.UUID.randomUUID().toString();
        }

        if (inboxRepository.existsById(messageId)) {
            return;
        }
        InboxMessage im = new InboxMessage();
        im.setMessageId(messageId);
        im.setType("OrderCreated");
        inboxRepository.save(im);

        JsonNode node = objectMapper.readTree(payload);
        Long orderId = node.get("orderId").asLong();
        Double amount = node.get("amount").asDouble();

        PaymentEntity p = new PaymentEntity();
        p.setOrderId(orderId);
        p.setAmount(amount);
        p.setStatus("COMPLETED");
        paymentRepository.save(p);
    }
}
