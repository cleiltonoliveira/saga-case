package com.example.saga.common.domain;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "inbox")
@Getter
@Setter
public class InboxMessage {
    @Id
    private String messageId;
    private Instant receivedAt = Instant.now();
    private String type;

}
