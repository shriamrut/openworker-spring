package com.openworker.agent.impl;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.interfaces.PermissionEngine;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.internals.services.PermissionDecision;
import com.openworker.agent.models.internals.services.ToolRiskClass;
import com.openworker.agent.tools.ToolRegistry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
@RequiredArgsConstructor
public class PermissionEngineImpl implements PermissionEngine {

    private final ToolRegistry toolRegistry;

    @Override
    public PermissionDecision evaluate(AgentMode mode, String toolName, Map<String, Object> arguments) {
        Optional<AgentTool> toolOpt = toolRegistry.getTool(toolName);
        if (toolOpt.isEmpty()) {
            log.warn("Permission denied: Tool '{}' is not registered", toolName);
            return PermissionDecision.deny("Unknown tool: " + toolName);
        }
        AgentTool tool = toolOpt.get();
        ToolRiskClass risk = tool.getRiskClass();

        if (mode == AgentMode.DISCUSS) {
            return PermissionDecision.askUser("Tool '" + toolName + "' requires user approval in DISCUSS mode");
        }

        if (risk == ToolRiskClass.READ_ONLY) {
            return PermissionDecision.allow();
        }

        return switch (mode) {
            case PLAN -> PermissionDecision.deny("Mutating tool " + toolName + " is not permitted in " + mode);
            case FULL -> PermissionDecision.allow();
            default -> PermissionDecision.deny("Unknown mode: " + mode);
        };
    }
}
