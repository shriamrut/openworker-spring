package com.openworker.agent.tools;

import java.util.Objects;

import org.springframework.ai.tool.ToolCallback;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.models.internals.services.ToolRiskClass;

/**
 * Adapter that exposes a Spring AI MCP {@link ToolCallback} as an {@link AgentTool}.
 * Following the security design of Claude Code and OpenWorker, external MCP tool calls
 * are treated as {@link ToolRiskClass#CONSEQUENTIAL} by default because they operate
 * outside the agent's internal sandbox boundary.
 */
public class McpAgentTool implements AgentTool {

    private final ToolCallback toolCallback;
    private final ToolRiskClass riskClass;

    public McpAgentTool(ToolCallback toolCallback) {
        this(toolCallback, ToolRiskClass.CONSEQUENTIAL);
    }

    public McpAgentTool(ToolCallback toolCallback, ToolRiskClass riskClass) {
        this.toolCallback = Objects.requireNonNull(toolCallback, "toolCallback must not be null");
        this.riskClass = riskClass != null ? riskClass : ToolRiskClass.CONSEQUENTIAL;
    }

    @Override
    public String getName() {
        return toolCallback.getToolDefinition() != null ? toolCallback.getToolDefinition().name() : "";
    }

    @Override
    public String getDescription() {
        return toolCallback.getToolDefinition() != null ? toolCallback.getToolDefinition().description() : "";
    }

    @Override
    public ToolRiskClass getRiskClass() {
        return this.riskClass;
    }

    @Override
    public ToolCallback toToolCallback() {
        return this.toolCallback;
    }

    @Override
    public String toString() {
        return "McpAgentTool[name=" + getName() + ", riskClass=" + riskClass + "]";
    }
}
