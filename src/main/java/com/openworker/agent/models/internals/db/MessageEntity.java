package com.openworker.agent.models.internals.db;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("messages")
public class MessageEntity {
    @Id
    @Column("id")
    private Long id;

    @Column("session_id")
    private String sessionId;

    @Column("message_type")
    private String messageType; // "USER", "ASSISTANT", "SYSTEM"

    @Column("content")
    private String content;

    @Column("created_at")
    private Instant createdAt;

}
