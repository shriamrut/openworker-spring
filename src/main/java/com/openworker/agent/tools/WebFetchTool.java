package com.openworker.agent.tools;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.jsoup.Jsoup;
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
public class WebFetchTool implements AgentTool {

    private static final String TOOL_NAME = "web_fetch";
    private static final String DESCRIPTION = "Fetch and extract clean text content from a web URL.";

    @Value("${agent.tools.web-fetch.max-content-length:10000}")
    private Integer maxContentLength;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

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
    public String execute(@ToolParam(description = "HTTP or HTTPS URL to fetch") String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "OpenWorker/1.0")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 400) {
                return "Error: HTTP request failed with status " + response.statusCode();
            }

            String body = response.body();
            String text = Jsoup.parse(body).text();

            int limit = (maxContentLength != null && maxContentLength > 0) ? maxContentLength : 10_000;
            if (text.length() > limit) {
                text = text.substring(0, limit) + "\n... [Truncated]";
            }

            return String.format("Status: %d\nURL: %s\n\nContent:\n%s", response.statusCode(), url, text);
        } catch (Exception ex) {
            log.error("Failed to fetch URL: {}", url, ex);
            return "Error fetching URL: " + ex.getMessage();
        }
    }
}
