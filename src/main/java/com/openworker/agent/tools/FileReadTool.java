package com.openworker.agent.tools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.models.internals.services.ToolRiskClass;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class FileReadTool implements AgentTool {

    private final static String toolName = "file_read";
    private final static String description = """
            Reads lines from a text file on the local filesystem.
            IMPORTANT: Large files are returned in chunks of up to 200 lines at a time.
            The response header always shows 'lines X-Y of TOTAL' so you know the file size.
            If the file has more lines than returned, you MUST call this tool again with the
            next startLine value to read the remaining content — do NOT repeat the same call.
            Use startLine and endLine to read specific sections.
            ALWAYS include the 'path' parameter in EVERY call, including when paginating
            with startLine. Never omit 'path', even on follow-up paginated reads.
            """;

    @Value("${agent.tools.file-read.max-lines:200}")
    private Integer MAX_LINES;

    @Override
    public String getName() {
        return toolName;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public ToolRiskClass getRiskClass() {
        return ToolRiskClass.READ_ONLY;
    }

    @Override
    public ToolCallback toToolCallback() {
        return ToolCallbacks.from(this)[0];
    }

    @Tool(name = toolName, description = description)
    public String execute(
            @ToolParam(description = "Absolute path to the file to read. REQUIRED in every call, including paginated follow-ups.") String path,
            @ToolParam(description = "1-based starting line number to begin reading from (use to paginate large files)", required = false) Integer startLine,
            @ToolParam(description = "1-based ending line number inclusive; omit to read up to 200 lines from startLine", required = false) Integer endLine) {
        if (path == null || path.isBlank()) {
            return "Error: 'path' parameter is required and must not be empty. " +
                   "Always include the absolute file path in every call, including paginated follow-up reads.";
        }
        try {
            Path filePath = Path.of(path);
            if (!Files.exists(filePath)) {
                return "Error: File not found at path:" + path;
            }
            if (Files.isDirectory(filePath)) {
                return "Error: Path is a directory not a file: " + path;
            }

            // Count total lines efficiently without loading all content into memory
            long totalLines;
            try (var lineStream = Files.lines(filePath, StandardCharsets.UTF_8)) {
                totalLines = lineStream.count();
            }

            int start = (startLine != null && startLine > 0) ? startLine : 1;
            int end = (endLine != null && endLine >= start) ? (int) Math.min(endLine, totalLines)
                    : (int) Math.min((long) start + MAX_LINES - 1, totalLines);

            if (start > totalLines) {
                return "Error: Start line " + start + " is greater than total lines " + totalLines + " in file: " + path;
            }

            // Stream only the needed lines — avoids loading entire file into memory
            List<String> chunk;
            try (var lineStream = Files.lines(filePath, StandardCharsets.UTF_8)) {
                chunk = lineStream
                        .skip(start - 1)
                        .limit((long) end - start + 1)
                        .toList();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("File: ").append(path)
              .append(" (lines ").append(start).append("-").append(end)
              .append(" of ").append(totalLines).append(")\n\n");
            for (int i = 0; i < chunk.size(); i++) {
                sb.append(String.format("%4d: %s\n", start + i, chunk.get(i)));
            }
            if (end < totalLines) {
                sb.append("\n[FILE NOT FULLY READ] Lines ").append(end + 1).append("-").append(totalLines)
                  .append(" not yet read. Call file_read again with path=\"").append(path)
                  .append("\" and startLine=").append(end + 1).append(" to continue reading.");
            }
            return sb.toString();
        } catch (Exception ex) {
            log.error("Unable to perform file read due to ", ex);
            return "Error reading file: " + ex.getMessage();
        }
    }
}
