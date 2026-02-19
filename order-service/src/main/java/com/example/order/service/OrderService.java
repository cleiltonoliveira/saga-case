package com.example.order.service;

import com.example.order.domain.OrderEntity;
import com.example.order.repository.OrderRepository;
import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OrderService {
    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OrderRepository orderRepository;

    public OrderService(OutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate, OrderRepository orderRepository) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.orderRepository = orderRepository;
    }

    public OrderEntity create(Double amount) {
        logger.info("Received new order, amount: {}", amount);

        OrderEntity o = new OrderEntity();
        o.setAmount(amount);
        o.setStatus("CREATED");
        OrderEntity saved = orderRepository.save(o);

        logger.info("Order created: {}", o.getId());

        OutboxMessage m = new OutboxMessage();
        m.setAggregateType("order");
        m.setAggregateId(String.valueOf(saved.getId()));
        m.setType("order_created");
        m.setPayload("{\"orderId\":" + saved.getId() + ",\"amount\":" + saved.getAmount() + "}");
        outboxRepository.save(m);

        logger.info("OutboxMessage created: {}", m.getId());

        return saved;
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void pollOutbox() {
        List<OutboxMessage> pending = outboxRepository.findByTypeAndPublishedFalseOrderByCreatedAtAsc("order_created");

        if (!pending.isEmpty()) {
            logger.info("{} pending OutboxMessage of type order_created to process", pending.size());
        }
        for (OutboxMessage m : pending) {
            String topic = m.getType();
            String messageId = java.util.UUID.randomUUID().toString();
            var result = kafkaTemplate.send(topic, messageId, m.getPayload());

            // if result processado, aslva
            m.setPublished(true);
            outboxRepository.save(m);

            logger.info("Message {} published", messageId);
        }
    }
}
