package com.openworker.agent.interfaces;

import java.util.Map;

import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.internals.services.PermissionDecision;

public interface PermissionEngine {
    PermissionDecision evaluate(AgentMode mode, String toolName, Map<String, Object> arguments);
}
