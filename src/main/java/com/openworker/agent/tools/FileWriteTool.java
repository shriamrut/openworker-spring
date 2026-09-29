package com.openworker.agent.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.models.internals.services.ToolRiskClass;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class FileWriteTool implements AgentTool {

    private static final String TOOL_NAME = "file_write";
    private static final String DESCRIPTION = "Create or overwrite a file with the specified content.";

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
        return ToolRiskClass.CONSEQUENTIAL;
    }

    @Override
    public ToolCallback toToolCallback() {
        return ToolCallbacks.from(this)[0];
    }

    @Tool(name = TOOL_NAME, description = DESCRIPTION)
    public String execute(
            @ToolParam(description = "Absolute path to the destination file") String path,
            @ToolParam(description = "The complete text content to write") String content,
            @ToolParam(description = "Whether to overwrite if the file already exists (default true)", required = false) Boolean overwrite) {
        try {
            Path targetPath = Path.of(path);
            boolean exists = Files.exists(targetPath);

            if (exists && Boolean.FALSE.equals(overwrite)) {
                return "Error: File already exists and overwrite is set to false: " + path;
            }

            if (targetPath.getParent() != null) {
                Files.createDirectories(targetPath.getParent());
            }

            Files.writeString(
                    targetPath,
                    content,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

            return "Successfully " + (exists ? "overwrote" : "created") + " file: " + path + " (" + content.length()
                    + " chars)";
        } catch (Exception ex) {
            log.error("Failed to write to file: {}", path, ex);
            return "Error writing file: " + ex.getMessage();
        }
    }
}
