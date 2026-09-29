package com.openworker.agent.models.publics;

import java.time.Instant;

public record MessageResponse(
        Long id,
        String sessionId,
        String messageType,
        String content,
        Instant createdAt) {
}
