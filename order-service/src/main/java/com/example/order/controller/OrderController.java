package com.example.order.controller;

import com.example.order.domain.OrderEntity;
import com.example.order.repository.OrderRepository;
import com.example.order.service.OrderService;
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

private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;

    }

    @PostMapping
    @Transactional
    public ResponseEntity<OrderEntity> create(@RequestParam Double amount) {



        return ResponseEntity.ok(orderService.create(amount));
    }
}





