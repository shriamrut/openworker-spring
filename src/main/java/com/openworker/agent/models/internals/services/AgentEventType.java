package com.openworker.agent.models.internals.services;

public enum AgentEventType {
    STARTED,
    NARRATION,
    TOOL_CALL,
    TOOL_RESULT,
    PERMISSION_REQUIRED,
    COMPLETED,
    ERROR;
}