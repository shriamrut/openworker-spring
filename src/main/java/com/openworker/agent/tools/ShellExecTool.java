package com.openworker.agent.tools;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

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
public class ShellExecTool implements AgentTool {

    private static final String TOOL_NAME = "shell_exec";
    private static final String DESCRIPTION = "Execute a shell command in a working directory and capture its output.";

    @Value("${agent.tools.shell-exec.timeout-seconds:30}")
    private Integer defaultTimeoutSeconds;

    @Value("${agent.tools.shell-exec.max-output-chars:10000}")
    private Integer maxOutputChars;

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
            @ToolParam(description = "Command string to execute (e.g. 'ls -la', 'mvn test')") String command,
            @ToolParam(description = "Working directory path (optional, defaults to project root)", required = false) String workingDir,
            @ToolParam(description = "Command timeout in seconds (default 30)", required = false) Integer timeoutSeconds) {

        int timeout = (timeoutSeconds != null && timeoutSeconds > 0)
                ? timeoutSeconds
                : (defaultTimeoutSeconds != null ? defaultTimeoutSeconds : 30);
        int maxChars = (maxOutputChars != null && maxOutputChars > 0) ? maxOutputChars : 10_000;

        File cwd = (workingDir != null && !workingDir.isBlank()) ? new File(workingDir) : new File(".");

        if (!cwd.exists() || !cwd.isDirectory()) {
            return "Error: Working directory does not exist: " + workingDir;
        }

        try {
            boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
            ProcessBuilder pb = isWindows
                    ? new ProcessBuilder("cmd.exe", "/c", command)
                    : new ProcessBuilder("bash", "-c", command);

            pb.directory(cwd);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < maxChars) {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean finished = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Error: Command timed out after " + timeout + " seconds.\nPartial Output:\n" + output;
            }

            int exitCode = process.exitValue();
            return String.format("Exit Code: %d\n\nOutput:\n%s", exitCode,
                    output.isEmpty() ? "(No output)" : output.toString());
        } catch (Exception ex) {
            log.error("Failed to execute command: {}", command, ex);
            return "Error executing command: " + ex.getMessage();
        }
    }
}
