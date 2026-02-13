package com.example.order.controller;

import com.example.order.domain.OrderEntity;
import com.example.order.repository.OrderRepository;
import com.example.saga.common.domain.OutboxMessage;
import com.example.saga.common.repo.OutboxRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
public class OrderController {
    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;

    public OrderController(OrderRepository orderRepository, OutboxRepository outboxRepository) {
        this.orderRepository = orderRepository;
        this.outboxRepository = outboxRepository;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderEntity> create(@RequestParam Double amount) {
        OrderEntity o = new OrderEntity();
        o.setAmount(amount);
        o.setStatus("CREATED");
        OrderEntity saved = orderRepository.save(o);

        OutboxMessage m = new OutboxMessage();
        m.setAggregateType("order");
        m.setAggregateId(String.valueOf(saved.getId()));
        m.setType("OrderCreated");
        // payload: simple json with orderId and amount
        m.setPayload("{\"orderId\":" + saved.getId() + ",\"amount\":" + saved.getAmount() + "}");
        outboxRepository.save(m);

        return ResponseEntity.ok(saved);
    }
}
