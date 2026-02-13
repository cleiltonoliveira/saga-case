package com.example.inventory.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryKafkaListener {

    @KafkaListener(topics = "OrderCreated", groupId = "saga-poc-group")
    public void listen(ConsumerRecord<String, String> record) {
        System.out.println("Inventory listener received: key=" + record.key() + " value=" + record.value());
    }
}

