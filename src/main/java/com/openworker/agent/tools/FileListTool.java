package com.openworker.agent.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.models.internals.services.ToolRiskClass;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FileListTool implements AgentTool {

    private static final String TOOL_NAME = "file_list";
    private static final String DESCRIPTION = "List files and subdirectories within a directory path.";

    @Value("${agent.tools.file-list.max-entries:200}")
    private Integer maxEntries;

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    public String getDescription() {
        return DESCRIPTION;
    }

    @Override
    public ToolRiskClass getRiskClass() {
        return ToolRiskClass.READ_ONLY;
    }

    @Override
    public ToolCallback toToolCallback() {
        return ToolCallbacks.from(this)[0];
    }

    @Tool(name = TOOL_NAME, description = DESCRIPTION)
    public String execute(
            @ToolParam(description = "Absolute directory path to inspect") String path,
            @ToolParam(description = "Whether to recursively list directory contents (max depth 3, default false)", required = false) Boolean recursive) {
        try {
            Path dirPath = Path.of(path);
            if (!Files.exists(dirPath)) {
                return "Error: Directory not found: " + path;
            }
            if (!Files.isDirectory(dirPath)) {
                return "Error: Path is not a directory: " + path;
            }

            int maxDepth = Boolean.TRUE.equals(recursive) ? 3 : 1;
            int limit = (maxEntries != null && maxEntries > 0) ? maxEntries : 200;

            StringBuilder sb = new StringBuilder();
            sb.append("Directory listing for: ").append(path).append("\n\n");

            try (Stream<Path> stream = Files.walk(dirPath, maxDepth)) {
                List<Path> entries = stream
                        .filter(p -> !p.equals(dirPath))
                        .limit(limit)
                        .toList();

                for (Path entry : entries) {
                    Path relative = dirPath.relativize(entry);
                    boolean isDir = Files.isDirectory(entry);
                    long size = isDir ? 0 : Files.size(entry);
                    sb.append(String.format("%-6s %10s  %s\n", isDir ? "[DIR]" : "[FILE]", isDir ? "-" : size + " B",
                            relative));
                }

                if (entries.size() >= limit) {
                    sb.append("\n... [Output capped at ").append(limit).append(" items]");
                }
            }

            return sb.toString();
        } catch (Exception ex) {
            log.error("Failed to list directory: {}", path, ex);
            return "Error listing directory: " + ex.getMessage();
        }
    }
}
