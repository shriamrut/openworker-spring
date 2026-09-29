package com.openworker.agent.config;

import org.springframework.ai.mcp.DefaultMcpToolNamePrefixGenerator;
import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.extern.slf4j.Slf4j;

/**
 * Configuration for Model Context Protocol (MCP) tool resolution,
 * name collision handling, and dynamic filtering.
 */
@Configuration
@ConditionalOnClass(McpToolNamePrefixGenerator.class)
@Slf4j
public class McpConfig {

    /**
     * Ensures tools from different MCP servers have predictable, unique names
     * without accidental collision with built-in tools or other servers.
     */
    @Bean
    @ConditionalOnMissingBean(McpToolNamePrefixGenerator.class)
    public McpToolNamePrefixGenerator mcpToolNamePrefixGenerator() {
        log.info("Registering DefaultMcpToolNamePrefixGenerator for MCP tool resolution");
        return new DefaultMcpToolNamePrefixGenerator();
    }

    /**
     * Default tool filter permitting all discovered tools from active MCP connections.
     * Can be overridden by custom bean to exclude sensitive or deprecated MCP tools.
     */
    @Bean
    @ConditionalOnMissingBean(McpToolFilter.class)
    public McpToolFilter mcpToolFilter() {
        return (connectionInfo, tool) -> true;
    }

    @Bean
    @ConditionalOnMissingBean(com.fasterxml.jackson.databind.ObjectMapper.class)
    public com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
        return new com.fasterxml.jackson.databind.ObjectMapper();
    }
}
