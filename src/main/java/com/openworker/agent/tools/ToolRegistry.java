package com.openworker.agent.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.services.DynamicMcpServerManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Registry maintaining built-in agent tools and dynamically bridging
 * external Model Context Protocol (MCP) server tools into a unified catalog.
 * Supports both DynamicMcpServerManager and Spring AI's SyncMcpToolCallbackProvider.
 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> builtInToolsByName;
    private final ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider;
    private final ObjectProvider<DynamicMcpServerManager> dynamicMcpServerManagerProvider;

    @Autowired
    public ToolRegistry(
            List<AgentTool> tools,
            ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider,
            ObjectProvider<DynamicMcpServerManager> dynamicMcpServerManagerProvider) {
        this.builtInToolsByName = (tools != null ? tools : List.<AgentTool>of()).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        AgentTool::getName,
                        Function.identity(),
                        (existing, replacement) -> existing,
                        LinkedHashMap::new));
        this.mcpToolCallbackProvider = mcpToolCallbackProvider;
        this.dynamicMcpServerManagerProvider = dynamicMcpServerManagerProvider;
        log.info("Initialized ToolRegistry with {} built-in tools", builtInToolsByName.size());
    }

    public ToolRegistry(List<AgentTool> tools, ObjectProvider<SyncMcpToolCallbackProvider> mcpToolCallbackProvider) {
        this(tools, mcpToolCallbackProvider, null);
    }

    public ToolRegistry(List<AgentTool> tools) {
        this(tools, null, null);
    }

    /**
     * Retrieve a tool by its unique name, querying built-in tools first,
     * then querying any available MCP server tool callbacks.
     */
    public Optional<AgentTool> getTool(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        AgentTool builtIn = this.builtInToolsByName.get(name);
        if (builtIn != null) {
            return Optional.of(builtIn);
        }

        for (AgentTool mcpTool : getMcpTools()) {
            if (name.equals(mcpTool.getName())) {
                return Optional.of(mcpTool);
            }
        }
        return Optional.empty();
    }

    /**
     * Get all available tools, dynamically blending built-in tools with
     * active MCP server tools.
     */
    public List<AgentTool> getAllTools() {
        List<AgentTool> allTools = new ArrayList<>(builtInToolsByName.values());
        List<AgentTool> mcpTools = getMcpTools();
        for (AgentTool mcpTool : mcpTools) {
            if (!builtInToolsByName.containsKey(mcpTool.getName())) {
                allTools.add(mcpTool);
            } else {
                log.warn("MCP tool '{}' collides with built-in tool; built-in tool takes precedence",
                        mcpTool.getName());
            }
        }
        return Collections.unmodifiableList(allTools);
    }

    /**
     * Get only the built-in tools.
     */
    public List<AgentTool> getBuiltInTools() {
        return List.copyOf(builtInToolsByName.values());
    }

    /**
     * Get currently active tools provided by connected MCP servers.
     * Combines tools from DynamicMcpServerManager and any fallback SyncMcpToolCallbackProvider.
     */
    public List<AgentTool> getMcpTools() {
        Map<String, AgentTool> mcpTools = new LinkedHashMap<>();

        // 1. From DynamicMcpServerManager
        DynamicMcpServerManager dynamicManager = dynamicMcpServerManagerProvider != null
                ? dynamicMcpServerManagerProvider.getIfAvailable()
                : null;
        if (dynamicManager != null) {
            ToolCallback[] callbacks = dynamicManager.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    if (cb != null && cb.getToolDefinition() != null) {
                        mcpTools.putIfAbsent(cb.getToolDefinition().name(), new McpAgentTool(cb));
                    }
                }
            }
        }

        // 2. From any fallback Spring AI SyncMcpToolCallbackProvider bean
        SyncMcpToolCallbackProvider provider = mcpToolCallbackProvider != null
                ? mcpToolCallbackProvider.getIfAvailable()
                : null;
        if (provider != null) {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            if (callbacks != null) {
                for (ToolCallback cb : callbacks) {
                    if (cb != null && cb.getToolDefinition() != null) {
                        mcpTools.putIfAbsent(cb.getToolDefinition().name(), new McpAgentTool(cb));
                    }
                }
            }
        }

        return List.copyOf(mcpTools.values());
    }

    /**
     * Returns true if MCP support is active and a provider is available in context.
     */
    public boolean hasMcpProvider() {
        boolean hasDynamic = dynamicMcpServerManagerProvider != null && dynamicMcpServerManagerProvider.getIfAvailable() != null;
        boolean hasSpringAi = mcpToolCallbackProvider != null && mcpToolCallbackProvider.getIfAvailable() != null;
        return hasDynamic || hasSpringAi;
    }
}
