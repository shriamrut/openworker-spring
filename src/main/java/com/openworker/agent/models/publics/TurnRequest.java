package com.openworker.agent.models.publics;

import com.openworker.agent.models.internals.services.AgentMode;

public record TurnRequest(String prompt, AgentMode mode, String provider, String model) {
    public TurnRequest(String prompt, AgentMode mode) {
        this(prompt, mode, null, null);
    }
}
