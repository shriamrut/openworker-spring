package com.openworker.agent.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class AnthropicModelProvider implements ModelProvider {

    public static final String ID = "anthropic";

    private String name;
    private String baseUrl;
    private String apiKey;
    private String defaultModel;
    private List<String> models;

    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    public AnthropicModelProvider(
            @Value("${openworker.models.providers.anthropic.name:Anthropic Claude}") String name,
            @Value("${openworker.models.providers.anthropic.base-url:https://api.anthropic.com}") String baseUrl,
            @Value("${openworker.models.providers.anthropic.api-key:}") String apiKey,
            @Value("${openworker.models.providers.anthropic.default-model:claude-3-5-sonnet-20241022}") String defaultModel,
            @Value("${openworker.models.providers.anthropic.models:}") List<String> models,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.models = new ArrayList<>(models != null && !models.isEmpty() ? models : List.of(defaultModel));
        this.observationRegistry = (observationRegistryProvider != null && observationRegistryProvider.getIfAvailable() != null)
                ? observationRegistryProvider.getIfAvailable()
                : ObservationRegistry.NOOP;
    }

    @Override
    public String getProviderId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String getStatusMessage() {
        return isAvailable() ? "Configured and ready" : "API key not configured";
    }

    @Override
    public String getDefaultModel() {
        return defaultModel;
    }

    @Override
    public List<String> getAvailableModels() {
        return new ArrayList<>(models);
    }

    @Override
    public String getBaseUrl() {
        return baseUrl;
    }

    @Override
    public ChatModel getChatModel(String modelName) {
        if (!isAvailable()) {
            throw new IllegalStateException("Anthropic provider is not available: API key not configured. Please configure your API key via POST /api/models/providers/anthropic/config");
        }
        String effectiveModel = (modelName != null && !modelName.isBlank()) ? modelName : defaultModel;
        return chatModelCache.computeIfAbsent(effectiveModel, this::buildChatModel);
    }

    private ChatModel buildChatModel(String model) {
        log.info("Instantiating AnthropicChatModel for model: {} at baseUrl: {}", model, baseUrl);
        ObservationRegistry registry = (this.observationRegistry != null)
                ? this.observationRegistry
                : ObservationRegistry.NOOP;
        AnthropicChatOptions options = AnthropicChatOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl : "https://api.anthropic.com")
                .model(model)
                .build();
        return AnthropicChatModel.builder()
                .options(options)
                .observationRegistry(registry)
                .build();
    }

    @Override
    public synchronized void updateConfig(UpdateProviderConfigRequest req) {
        if (req.apiKey() != null) {
            this.apiKey = req.apiKey().isBlank() ? null : req.apiKey();
        }
        if (req.baseUrl() != null && !req.baseUrl().isBlank()) {
            this.baseUrl = req.baseUrl();
        }
        if (req.defaultModel() != null && !req.defaultModel().isBlank()) {
            this.defaultModel = req.defaultModel();
            if (!models.contains(this.defaultModel)) {
                models.add(this.defaultModel);
            }
        }
        chatModelCache.clear();
        log.info("Updated configuration for Anthropic provider: defaultModel={}, available={}", defaultModel, isAvailable());
    }
}
