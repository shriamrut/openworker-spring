package com.openworker.agent.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OllamaModelProvider implements ModelProvider {

    public static final String ID = "ollama";

    private String name;
    private String baseUrl;
    private String defaultModel;
    private List<String> models;

    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    public OllamaModelProvider(
            @Value("${openworker.models.providers.ollama.name:Ollama}") String name,
            @Value("${openworker.models.providers.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${openworker.models.providers.ollama.default-model:llama3.2:1b}") String defaultModel,
            @Value("${openworker.models.providers.ollama.models:}") List<String> models,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.name = name;
        this.baseUrl = baseUrl;
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
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public String getStatusMessage() {
        return isAvailable() ? "Configured and ready" : "Base URL required";
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
        String effectiveModel = (modelName != null && !modelName.isBlank()) ? modelName : defaultModel;
        return chatModelCache.computeIfAbsent(effectiveModel, this::buildChatModel);
    }

    private ChatModel buildChatModel(String model) {
        log.info("Instantiating OllamaChatModel for model: {} at baseUrl: {}", model, baseUrl);
        ObservationRegistry registry = (this.observationRegistry != null)
                ? this.observationRegistry
                : ObservationRegistry.NOOP;
        OllamaApi ollamaApi = OllamaApi.builder().baseUrl(baseUrl).build();
        OllamaChatOptions options = OllamaChatOptions.builder()
                .model(model)
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(options)
                .observationRegistry(registry)
                .build();
    }

    @Override
    public synchronized void updateConfig(UpdateProviderConfigRequest req) {
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
        log.info("Updated configuration for Ollama provider: baseUrl={}, defaultModel={}", baseUrl, defaultModel);
    }
}
