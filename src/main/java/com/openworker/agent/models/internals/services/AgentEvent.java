package com.openworker.agent.models.internals.services;

public record AgentEvent(AgentEventType type, String content, String toolName, String toolCallId) {
}