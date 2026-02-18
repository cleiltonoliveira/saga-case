package com.example.inventory.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryKafkaListener {
    private static final Logger logger = LoggerFactory.getLogger(InventoryKafkaListener.class);

    @KafkaListener(topics = "OrderCreated", groupId = "saga-poc-group")
    public void listen(ConsumerRecord<String, String> record) {
       logger.info("Inventory listener received: key={} value={}", record.key(), record.value());
    }
}

