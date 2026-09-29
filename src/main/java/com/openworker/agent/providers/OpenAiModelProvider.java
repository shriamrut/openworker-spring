package com.openworker.agent.providers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OpenAiModelProvider implements ModelProvider {

    public static final String ID = "openai";

    private String name;
    private String baseUrl;
    private String apiKey;
    private String defaultModel;
    private List<String> models;

    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    public OpenAiModelProvider(
            @Value("${openworker.models.providers.openai.name:OpenAI / Compatible}") String name,
            @Value("${openworker.models.providers.openai.base-url:http://localhost:1234/v1}") String baseUrl,
            @Value("${openworker.models.providers.openai.api-key:lm-studio}") String apiKey,
            @Value("${openworker.models.providers.openai.default-model:google/gemma-4-e2b}") String defaultModel,
            @Value("${openworker.models.providers.openai.models:}") List<String> models,
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
        return (apiKey != null && !apiKey.isBlank()) || (baseUrl != null && !baseUrl.isBlank());
    }

    @Override
    public String getStatusMessage() {
        return isAvailable() ? "Configured and ready" : "Base URL or API key required";
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
        log.info("Instantiating OpenAiChatModel for model: {} at baseUrl: {}", model, baseUrl);
        ObservationRegistry registry = (this.observationRegistry != null)
                ? this.observationRegistry
                : ObservationRegistry.NOOP;
        String effectiveApiKey = (apiKey != null && !apiKey.isBlank()) ? apiKey : "dummy";
        OpenAIClient client = OpenAiSetup.setupSyncClient(
                baseUrl,
                effectiveApiKey,
                null, null, null, null, false, false,
                model, Duration.ofSeconds(60), 3, null, Map.of(), registry, null, List.of()
        );
        OpenAIClientAsync clientAsync = OpenAiSetup.setupAsyncClient(
                baseUrl,
                effectiveApiKey,
                null, null, null, null, false, false,
                model, Duration.ofSeconds(60), 3, null, Map.of(), registry, null, List.of()
        );
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(baseUrl)
                .apiKey(effectiveApiKey)
                .model(model)
                .timeout(Duration.ofSeconds(60))
                .build();
        return OpenAiChatModel.builder()
                .openAiClient(client)
                .openAiClientAsync(clientAsync)
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
        log.info("Updated configuration for OpenAI provider: baseUrl={}, defaultModel={}", baseUrl, defaultModel);
    }
}
