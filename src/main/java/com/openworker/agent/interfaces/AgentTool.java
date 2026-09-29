package com.openworker.agent.interfaces;

import org.springframework.ai.tool.ToolCallback;

import com.openworker.agent.models.internals.services.ToolRiskClass;

public interface AgentTool {
    String getName();

    String getDescription();

    ToolRiskClass getRiskClass();

    ToolCallback toToolCallback();
}
