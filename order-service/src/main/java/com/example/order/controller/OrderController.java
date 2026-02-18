package com.example.order.controller;

import com.example.order.domain.OrderEntity;
import com.example.order.repository.OrderRepository;
import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;

    private static final Logger logger = LoggerFactory.getLogger(OrderController.class);

    public OrderController(OrderRepository orderRepository, OutboxRepository outboxRepository) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderEntity> create(@RequestParam Double amount) {

        logger.info("Received new order, amount: {}", amount);

        OrderEntity o = new OrderEntity();
        o.setAmount(amount);
        o.setStatus("CREATED");
        OrderEntity saved = orderRepository.save(o);

        logger.info("Order created: {}", o.getId());

        OutboxMessage m = new OutboxMessage();
        m.setAggregateType("order");
        m.setAggregateId(String.valueOf(saved.getId()));
        m.setType("OrderCreated");
        m.setPayload("{\"orderId\":" + saved.getId() + ",\"amount\":" + saved.getAmount() + "}");
        outboxRepository.save(m);

        logger.info("OutboxMessage created: {}", m.getId());

        return ResponseEntity.ok(saved);
    }
}





