package com.openworker.agent.models.internals.db;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
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
@Table("sessions")
public class SessionEntity implements Persistable<String> {
    @Id
    @Column("id")
    private String id;

    @Column("title")
    private String title;

    @Column("agent_mode")
    private String agentMode;

    @Column("model_provider")
    private String modelProvider;

    @Column("model_name")
    private String modelName;

    @Column("created_at")
    private Instant createdAt;

    @Column("updated_at")
    private Instant updatedAt;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return this.isNew;
    }
}

