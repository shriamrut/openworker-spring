package com.openworker.agent.models.publics;

import java.time.Instant;
import java.util.List;

public record SessionResponse(
        String id,
        String title,
        String agentMode,
        String modelProvider,
        String modelName,
        Instant createdAt,
        Instant updatedAt,
        List<MessageResponse> messages) {

    public SessionResponse(String id, String title, String agentMode, Instant createdAt, Instant updatedAt, List<MessageResponse> messages) {
        this(id, title, agentMode, null, null, createdAt, updatedAt, messages);
    }
}
