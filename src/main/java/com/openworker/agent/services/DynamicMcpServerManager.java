package com.openworker.agent.services;

import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.mcp.McpToolFilter;
import org.springframework.ai.mcp.McpToolNamePrefixGenerator;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openworker.agent.models.internals.mcp.McpServerDefinition;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dynamic manager for Model Context Protocol (MCP) servers.
 * Parses a unified JSON configuration file (e.g. mcp-servers.json or mcp-server.json)
 * supporting both stdio (subprocess) and HTTP/SSE servers, handles fault tolerance,
 * and allows dynamic runtime reloading and add/remove operations without application restarts.
 */
@Service
@Slf4j
public class DynamicMcpServerManager implements DisposableBean {

    public record McpServerInfo(
            String name,
            String type,
            String status,
            String target,
            List<String> tools
    ) {}

    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<McpToolNamePrefixGenerator> prefixGeneratorProvider;
    private final ObjectProvider<McpToolFilter> filterProvider;

    @Value("${openworker.mcp.servers-configuration:classpath:mcp-servers.json}")
    private String configLocation;

    @Value("${openworker.mcp.request-timeout:20s}")
    private Duration requestTimeout;

    private final Map<String, McpServerDefinition> currentDefinitions = new ConcurrentHashMap<>();
    private final Map<String, McpSyncClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, String> serverStatuses = new ConcurrentHashMap<>();
    private volatile SyncMcpToolCallbackProvider toolCallbackProvider;

    public DynamicMcpServerManager(
            ResourceLoader resourceLoader,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ObjectProvider<McpToolNamePrefixGenerator> prefixGeneratorProvider,
            ObjectProvider<McpToolFilter> filterProvider) {
        this.resourceLoader = resourceLoader;
        this.objectMapper = (objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null)
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper();
        this.prefixGeneratorProvider = prefixGeneratorProvider;
        this.filterProvider = filterProvider;
    }

    @PostConstruct
    public void init() {
        reload();
    }

    /**
     * Dynamically reload all MCP servers defined in the JSON configuration.
     * Closes existing connections and establishes new ones.
     */
    public synchronized void reload() {
        Resource resource = resolveConfigResource();
        log.info("Loading MCP servers from configuration: {}", resource != null ? resource.getDescription() : configLocation);
        closeActiveClients();
        currentDefinitions.clear();

        Map<String, McpServerDefinition> definitions = loadDefinitions(resource);
        currentDefinitions.putAll(definitions);

        if (definitions.isEmpty()) {
            log.info("No MCP servers configured or configuration file is empty");
            this.toolCallbackProvider = null;
            return;
        }

        for (Map.Entry<String, McpServerDefinition> entry : definitions.entrySet()) {
            String serverName = entry.getKey();
            McpServerDefinition def = entry.getValue();
            connectServer(serverName, def);
        }

        rebuildToolCallbackProvider();
        log.info("MCP server initialization completed. Active servers: {} / {}",
                activeClients.size(), definitions.size());
    }

    /**
     * Add or update an MCP server dynamically at runtime.
     *
     * @param name Name of the server
     * @param def  Server definition (stdio or sse)
     * @param persist Whether to write the update to the configuration file on disk
     * @return true if connection was successfully established
     */
    public synchronized boolean addServer(String name, McpServerDefinition def, boolean persist) {
        if (name == null || name.isBlank() || def == null) {
            throw new IllegalArgumentException("Server name and definition must not be null or blank");
        }

        // Close any existing client with the same name
        McpSyncClient existing = activeClients.remove(name);
        if (existing != null) {
            try {
                existing.close();
            } catch (Exception ignored) {}
        }

        currentDefinitions.put(name, def);
        boolean connected = connectServer(name, def);
        rebuildToolCallbackProvider();

        if (persist) {
            persistDefinitionsToFile();
        }

        return connected;
    }

    /**
     * Remove an MCP server dynamically at runtime.
     *
     * @param name Name of the server
     * @param persist Whether to update the configuration file on disk
     * @return true if the server was found and removed
     */
    public synchronized boolean removeServer(String name, boolean persist) {
        if (name == null || !currentDefinitions.containsKey(name)) {
            return false;
        }

        currentDefinitions.remove(name);
        serverStatuses.remove(name);

        McpSyncClient client = activeClients.remove(name);
        if (client != null) {
            try {
                client.close();
            } catch (Exception ex) {
                log.warn("Error closing MCP client {}: {}", name, ex.getMessage());
            }
        }

        rebuildToolCallbackProvider();

        if (persist) {
            persistDefinitionsToFile();
        }

        return true;
    }

    private boolean connectServer(String serverName, McpServerDefinition def) {
        try {
            McpSyncClient client = createClient(serverName, def);
            if (client != null) {
                activeClients.put(serverName, client);
                serverStatuses.put(serverName, "CONNECTED (" + (def.isStdio() ? "stdio" : "sse") + ")");
                log.info("Successfully connected to MCP server '{}'", serverName);
                return true;
            }
        } catch (Exception ex) {
            String errorMsg = "Failed to connect: " + ex.getMessage();
            serverStatuses.put(serverName, errorMsg);
            log.warn("Could not connect to MCP server '{}': {}. Skipping to ensure application availability.",
                    serverName, ex.getMessage());
        }
        return false;
    }

    private McpSyncClient createClient(String serverName, McpServerDefinition def) {
        if (def.isStdio()) {
            ServerParameters.Builder paramBuilder = ServerParameters.builder(def.command());
            if (def.args() != null && !def.args().isEmpty()) {
                paramBuilder.args(def.args());
            }
            if (def.env() != null && !def.env().isEmpty()) {
                paramBuilder.env(def.env());
            }

            StdioClientTransport transport = new StdioClientTransport(
                    paramBuilder.build(),
                    new JacksonMcpJsonMapper(JsonMapper.shared())
            );

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .clientInfo(new McpSchema.Implementation("OpenWorker-" + serverName, "1.0.0"))
                    .build();

            client.initialize();
            return client;
        } else if (def.isSse()) {
            HttpClientSseClientTransport.Builder sseBuilder = HttpClientSseClientTransport.builder(def.url());
            if (def.headers() != null && !def.headers().isEmpty()) {
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder();
                def.headers().forEach(reqBuilder::header);
                sseBuilder.requestBuilder(reqBuilder);
            }

            HttpClientSseClientTransport transport = sseBuilder.build();

            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .clientInfo(new McpSchema.Implementation("OpenWorker-" + serverName, "1.0.0"))
                    .build();

            client.initialize();
            return client;
        } else {
            log.warn("MCP server '{}' has neither 'command' (stdio) nor 'url' (sse) configured. Skipping.", serverName);
            serverStatuses.put(serverName, "IGNORED: missing command or url");
            return null;
        }
    }

    private Resource resolveConfigResource() {
        if (configLocation != null && !configLocation.isBlank()) {
            Resource r = resourceLoader.getResource(configLocation);
            if (r.exists()) {
                return r;
            }
        }
        List<String> fallbacks = List.of(
                "file:./mcp-servers.json",
                "file:./src/main/resources/mcp-servers.json",
                "classpath:mcp-servers.json",
                "file:./mcp-server.json",
                "file:./src/main/resources/mcp-server.json",
                "classpath:mcp-server.json"
        );
        for (String loc : fallbacks) {
            Resource r = resourceLoader.getResource(loc);
            if (r.exists()) {
                return r;
            }
        }
        return resourceLoader.getResource(configLocation != null ? configLocation : "classpath:mcp-servers.json");
    }

    private Map<String, McpServerDefinition> loadDefinitions(Resource resource) {
        Map<String, McpServerDefinition> result = new LinkedHashMap<>();
        if (resource == null || !resource.exists()) {
            log.warn("MCP servers configuration resource does not exist");
            return result;
        }

        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            JsonNode serversNode = root.has("mcpServers") ? root.get("mcpServers") : root;

            if (serversNode != null && serversNode.isObject()) {
                Iterator<Map.Entry<String, JsonNode>> fields = serversNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    String name = field.getKey();
                    McpServerDefinition def = objectMapper.treeToValue(field.getValue(), McpServerDefinition.class);
                    if (def != null) {
                        result.put(name, def);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to parse MCP servers configuration from '{}': {}", resource.getDescription(), e.getMessage());
        }
        return result;
    }

    private void persistDefinitionsToFile() {
        List<Path> candidatePaths = List.of(
                Path.of("src/main/resources/mcp-servers.json"),
                Path.of("mcp-servers.json"),
                Path.of("src/main/resources/mcp-server.json"),
                Path.of("mcp-server.json")
        );

        Path target = null;
        for (Path p : candidatePaths) {
            if (Files.exists(p)) {
                target = p;
                break;
            }
        }
        if (target == null) {
            target = Path.of("mcp-servers.json");
        }

        try {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("mcpServers", currentDefinitions);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(target.toFile(), wrapper);
            log.info("Persisted {} MCP server definitions to {}", currentDefinitions.size(), target.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Could not persist MCP server definitions to disk: {}", e.getMessage());
        }
    }

    private void rebuildToolCallbackProvider() {
        if (activeClients.isEmpty()) {
            this.toolCallbackProvider = null;
            return;
        }

        SyncMcpToolCallbackProvider.Builder builder = SyncMcpToolCallbackProvider.builder()
                .mcpClients(new ArrayList<>(activeClients.values()));

        McpToolNamePrefixGenerator prefixGen = prefixGeneratorProvider.getIfAvailable();
        if (prefixGen != null) {
            builder.toolNamePrefixGenerator(prefixGen);
        }

        McpToolFilter filter = filterProvider.getIfAvailable();
        if (filter != null) {
            builder.toolFilter(filter);
        }

        this.toolCallbackProvider = builder.build();
    }

    private void closeActiveClients() {
        for (Map.Entry<String, McpSyncClient> entry : activeClients.entrySet()) {
            try {
                entry.getValue().close();
                log.debug("Closed MCP client connection: {}", entry.getKey());
            } catch (Exception ex) {
                log.warn("Error closing MCP client {}: {}", entry.getKey(), ex.getMessage());
            }
        }
        activeClients.clear();
        serverStatuses.clear();
    }

    /**
     * Get all active tool callbacks discovered from all currently connected MCP servers.
     */
    public ToolCallback[] getToolCallbacks() {
        SyncMcpToolCallbackProvider provider = this.toolCallbackProvider;
        if (provider == null) {
            return new ToolCallback[0];
        }
        try {
            ToolCallback[] callbacks = provider.getToolCallbacks();
            return callbacks != null ? callbacks : new ToolCallback[0];
        } catch (Exception e) {
            log.warn("Error retrieving tool callbacks from MCP provider: {}", e.getMessage());
            return new ToolCallback[0];
        }
    }

    /**
     * Get real-time connection statuses for all configured MCP servers.
     */
    public Map<String, String> getServerStatuses() {
        return Collections.unmodifiableMap(serverStatuses);
    }

    /**
     * Get all current server definitions.
     */
    public Map<String, McpServerDefinition> getCurrentDefinitions() {
        return Collections.unmodifiableMap(currentDefinitions);
    }

    /**
     * Get detailed status, transport type, target, and discovered tools for each server.
     */
    public List<McpServerInfo> getServerDetails() {
        List<McpServerInfo> details = new ArrayList<>();
        for (Map.Entry<String, McpServerDefinition> entry : currentDefinitions.entrySet()) {
            String name = entry.getKey();
            McpServerDefinition def = entry.getValue();
            String type = def.isStdio() ? "stdio" : (def.isSse() ? "sse" : "unknown");
            String status = serverStatuses.getOrDefault(name, "UNKNOWN");
            String target = def.isStdio()
                    ? (def.command() + (def.args() != null && !def.args().isEmpty() ? " " + String.join(" ", def.args()) : ""))
                    : def.url();

            List<String> tools = new ArrayList<>();
            McpSyncClient client = activeClients.get(name);
            if (client != null) {
                try {
                    McpSchema.ListToolsResult listResult = client.listTools();
                    if (listResult != null && listResult.tools() != null) {
                        for (McpSchema.Tool t : listResult.tools()) {
                            tools.add(t.name());
                        }
                    }
                } catch (Exception e) {
                    log.debug("Could not list tools for client '{}': {}", name, e.getMessage());
                }
            }

            details.add(new McpServerInfo(name, type, status, target, tools));
        }
        return Collections.unmodifiableList(details);
    }

    /**
     * Get number of currently active MCP connections.
     */
    public int getActiveServerCount() {
        return activeClients.size();
    }

    @Override
    public void destroy() {
        closeActiveClients();
    }
}
