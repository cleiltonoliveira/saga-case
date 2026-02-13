package com.example.saga.common.domain;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inbox")
public class InboxMessage {
    @Id
    private String messageId;
    private Instant receivedAt = Instant.now();
    private String type;

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
}
