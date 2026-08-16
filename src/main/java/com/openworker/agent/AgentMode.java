package com.openworker.agent;

public enum AgentMode {
    DISCUSS, // Read-only: LLM explores but cannot do write / shell tools
    PLAN, // Read-only: LLM always designs a strategy / and invoke propose plan first
    FULL; // Write & Shell execution active. Automode
}