package com.openworker.agent.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.google.genai.Client;
import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

import io.micrometer.observation.ObservationRegistry;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class GoogleGenAiModelProvider implements ModelProvider {

    public static final String ID = "google-genai";

    private String name;
    private String apiKey;
    private String defaultModel;
    private List<String> models;

    private final ObservationRegistry observationRegistry;
    private final Map<String, ChatModel> chatModelCache = new ConcurrentHashMap<>();

    public GoogleGenAiModelProvider(
            @Value("${openworker.models.providers.google-genai.name:Google Gemini}") String name,
            @Value("${openworker.models.providers.google-genai.api-key:}") String apiKey,
            @Value("${openworker.models.providers.google-genai.default-model:gemini-1.5-flash}") String defaultModel,
            @Value("${openworker.models.providers.google-genai.models:}") List<String> models,
            ObjectProvider<ObservationRegistry> observationRegistryProvider) {
        this.name = name;
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
        return null; // Uses Google GenAI cloud SDK endpoint
    }

    @Override
    public ChatModel getChatModel(String modelName) {
        if (!isAvailable()) {
            throw new IllegalStateException("Google GenAI provider is not available: API key not configured. Please configure your API key via POST /api/models/providers/google-genai/config");
        }
        String effectiveModel = (modelName != null && !modelName.isBlank()) ? modelName : defaultModel;
        return chatModelCache.computeIfAbsent(effectiveModel, this::buildChatModel);
    }

    private ChatModel buildChatModel(String model) {
        log.info("Instantiating GoogleGenAiChatModel for model: {}", model);
        ObservationRegistry registry = (this.observationRegistry != null)
                ? this.observationRegistry
                : ObservationRegistry.NOOP;
        Client client = Client.builder().apiKey(apiKey).build();
        GoogleGenAiChatOptions options = GoogleGenAiChatOptions.builder()
                .model(model)
                .build();
        return GoogleGenAiChatModel.builder()
                .genAiClient(client)
                .options(options)
                .observationRegistry(registry)
                .build();
    }

    @Override
    public synchronized void updateConfig(UpdateProviderConfigRequest req) {
        if (req.apiKey() != null) {
            this.apiKey = req.apiKey().isBlank() ? null : req.apiKey();
        }
        if (req.defaultModel() != null && !req.defaultModel().isBlank()) {
            this.defaultModel = req.defaultModel();
            if (!models.contains(this.defaultModel)) {
                models.add(this.defaultModel);
            }
        }
        chatModelCache.clear();
        log.info("Updated configuration for Google GenAI provider: defaultModel={}, available={}", defaultModel, isAvailable());
    }
}
