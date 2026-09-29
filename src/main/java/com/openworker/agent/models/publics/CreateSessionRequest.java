package com.openworker.agent.models.publics;

import com.openworker.agent.models.internals.services.AgentMode;

public record CreateSessionRequest(String title, AgentMode initialMode, String provider, String model) {
    public CreateSessionRequest(String title, AgentMode initialMode) {
        this(title, initialMode, null, null);
    }
}
