package com.openworker.agent;


public record AgentEvent(AgentEventType type, String content, String toolName, String toolCallId) {}