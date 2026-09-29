package com.openworker.agent.tools;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
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
public class FileSearchTool implements AgentTool {

    private static final String TOOL_NAME = "file_search";
    private static final String DESCRIPTION = "Search for files by name glob pattern or search file contents for text patterns.";

    @Value("${agent.tools.file-search.max-results:50}")
    private Integer maxResults;

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
            @ToolParam(description = "Directory path to search in") String directoryPath,
            @ToolParam(description = "File glob pattern (e.g. '*.java', 'pom.xml')", required = false) String filePattern,
            @ToolParam(description = "Text query to find inside files", required = false) String textQuery) {
        try {
            Path root = Path.of(directoryPath);
            if (!Files.exists(root) || !Files.isDirectory(root)) {
                return "Error: Invalid directory path: " + directoryPath;
            }

            int limit = (maxResults != null && maxResults > 0) ? maxResults : 50;
            PathMatcher matcher = (filePattern != null && !filePattern.isBlank())
                    ? FileSystems.getDefault().getPathMatcher("glob:" + filePattern)
                    : null;

            List<String> results = new ArrayList<>();

            try (Stream<Path> stream = Files.walk(root, 10)) {
                List<Path> files = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> matcher == null || matcher.matches(p.getFileName()))
                        .toList();

                for (Path file : files) {
                    if (results.size() >= limit) {
                        break;
                    }

                    if (textQuery != null && !textQuery.isBlank()) {
                        try {
                            List<String> lines = Files.readAllLines(file);
                            for (int i = 0; i < lines.size(); i++) {
                                if (lines.get(i).contains(textQuery)) {
                                    results.add(file + ":" + (i + 1) + ": " + lines.get(i).trim());
                                    if (results.size() >= limit) {
                                        break;
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            log.error("Unreadable file: ", ex);
                        }
                    } else {
                        results.add(file.toString());
                    }
                }
            }

            if (results.isEmpty()) {
                return "No matching files or occurrences found.";
            }

            StringBuilder sb = new StringBuilder("Found ").append(results.size()).append(" matches:\n\n");
            results.forEach(r -> sb.append(r).append("\n"));
            return sb.toString();
        } catch (Exception ex) {
            log.error("Failed to search directory: {}", directoryPath, ex);
            return "Error searching files: " + ex.getMessage();
        }
    }
}
