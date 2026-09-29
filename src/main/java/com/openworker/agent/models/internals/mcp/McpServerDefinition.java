package com.openworker.agent.models.internals.mcp;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Configuration definition for an external MCP server, supporting both
 * stdio-based local subprocesses and HTTP/SSE endpoints.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record McpServerDefinition(
        String command,
        List<String> args,
        Map<String, String> env,
        String url,
        Map<String, String> headers
) {
    public boolean isStdio() {
        return command != null && !command.isBlank();
    }

    public boolean isSse() {
        return url != null && !url.isBlank();
    }
}
