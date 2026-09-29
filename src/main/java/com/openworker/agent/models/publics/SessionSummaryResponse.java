package com.openworker.agent.models.publics;

import java.time.Instant;

public record SessionSummaryResponse(
        String id,
        String title,
        String agentMode,
        String modelProvider,
        String modelName,
        Instant createdAt,
        Instant updatedAt) {

    public SessionSummaryResponse(String id, String title, String agentMode, Instant createdAt, Instant updatedAt) {
        this(id, title, agentMode, null, null, createdAt, updatedAt);
    }
}
