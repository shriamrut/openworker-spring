package com.openworker.agent.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.openworker.agent.models.internals.mcp.McpServerDefinition;
import com.openworker.agent.services.DynamicMcpServerManager;
import com.openworker.agent.services.DynamicMcpServerManager.McpServerInfo;

import lombok.extern.slf4j.Slf4j;

/**
 * REST API for dynamic Model Context Protocol (MCP) server management.
 * Supports runtime server status inspection, dynamic reload from mcp-servers.json,
 * and live adding/removing of stdio and HTTP/SSE MCP servers.
 */
@RestController
@RequestMapping("/api/mcp")
@Slf4j
public class McpServerController {

    public record AddServerRequest(
            String name,
            McpServerDefinition definition,
            Boolean persist
    ) {}

    private final DynamicMcpServerManager dynamicMcpServerManager;

    public McpServerController(DynamicMcpServerManager dynamicMcpServerManager) {
        this.dynamicMcpServerManager = dynamicMcpServerManager;
    }

    /**
     * List all configured MCP servers, connection status, target, and discovered tools.
     */
    @GetMapping("/servers")
    public ResponseEntity<List<McpServerInfo>> listServers() {
        return ResponseEntity.ok(dynamicMcpServerManager.getServerDetails());
    }

    /**
     * Trigger dynamic reload of all MCP servers from the configuration JSON file.
     */
    @PostMapping("/reload")
    public ResponseEntity<Map<String, Object>> reloadServers() {
        log.info("Received request to dynamically reload MCP servers");
        dynamicMcpServerManager.reload();
        return ResponseEntity.ok(Map.of(
                "status", "RELOADED",
                "activeServers", dynamicMcpServerManager.getActiveServerCount(),
                "servers", dynamicMcpServerManager.getServerDetails()
        ));
    }

    /**
     * Dynamically add or update an MCP server.
     */
    @PostMapping("/servers")
    public ResponseEntity<Map<String, Object>> addServer(@RequestBody AddServerRequest request) {
        if (request.name() == null || request.name().isBlank() || request.definition() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "name and definition are required"));
        }

        boolean persist = request.persist() == null || request.persist();
        boolean connected = dynamicMcpServerManager.addServer(request.name(), request.definition(), persist);

        return ResponseEntity.status(connected ? HttpStatus.CREATED : HttpStatus.ACCEPTED).body(Map.of(
                "name", request.name(),
                "connected", connected,
                "serverDetails", dynamicMcpServerManager.getServerDetails()
        ));
    }

    /**
     * Dynamically remove an MCP server and disconnect its client.
     */
    @DeleteMapping("/servers/{name}")
    public ResponseEntity<Void> removeServer(
            @PathVariable String name,
            @RequestParam(defaultValue = "true") boolean persist) {
        boolean removed = dynamicMcpServerManager.removeServer(name, persist);
        if (removed) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.notFound().build();
    }
}
