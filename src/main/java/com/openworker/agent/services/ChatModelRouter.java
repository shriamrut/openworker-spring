package com.openworker.agent.services;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import com.openworker.agent.providers.ModelProvider;
import com.openworker.agent.providers.ModelProviderRegistry;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

@Slf4j
@Primary
@Service
public class ChatModelRouter implements ChatModel {

    private final ModelProviderRegistry registry;
    private volatile String activeDefaultProvider;
    private volatile String activeDefaultModel;

    private final Map<String, ChatClient> chatClientCache = new ConcurrentHashMap<>();

    public ChatModelRouter(
            ModelProviderRegistry registry,
            @Value("${openworker.models.default-provider:openai}") String defaultProvider,
            @Value("${openworker.models.default-model:google/gemma-4-e2b}") String defaultModel) {
        this.registry = registry;
        this.activeDefaultProvider = defaultProvider;
        this.activeDefaultModel = defaultModel;
        log.info("Initialized ChatModelRouter with defaultProvider='{}', defaultModel='{}'",
                activeDefaultProvider, activeDefaultModel);
    }

    public String getActiveProvider() {
        return activeDefaultProvider;
    }

    public String getActiveModel() {
        return activeDefaultModel;
    }

    public synchronized void setActiveDefault(String providerId, String modelName) {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID cannot be empty");
        }
        ModelProvider provider = registry.getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model provider: " + providerId));

        this.activeDefaultProvider = provider.getProviderId();
        if (modelName != null && !modelName.isBlank()) {
            this.activeDefaultModel = modelName;
        } else {
            this.activeDefaultModel = provider.getDefaultModel();
        }
        chatClientCache.clear();
        log.info("Switched active default model to provider='{}', model='{}'",
                activeDefaultProvider, activeDefaultModel);
    }

    public ChatModel getChatModel(String providerId, String modelName) {
        String effectiveProviderId = (providerId != null && !providerId.isBlank())
                ? providerId : activeDefaultProvider;
        ModelProvider provider = registry.getProvider(effectiveProviderId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model provider: " + effectiveProviderId));

        if (!provider.isAvailable()) {
            throw new IllegalStateException("Model provider '" + effectiveProviderId + "' is not available: "
                    + provider.getStatusMessage());
        }

        String effectiveModelName = (modelName != null && !modelName.isBlank())
                ? modelName
                : (effectiveProviderId.equalsIgnoreCase(activeDefaultProvider) && activeDefaultModel != null
                        ? activeDefaultModel
                        : provider.getDefaultModel());

        return provider.getChatModel(effectiveModelName);
    }

    public ChatClient getChatClient(String providerId, String modelName, String defaultSystemPrompt) {
        String effectiveProviderId = (providerId != null && !providerId.isBlank())
                ? providerId.toLowerCase() : activeDefaultProvider.toLowerCase();
        String effectiveModelName = (modelName != null && !modelName.isBlank())
                ? modelName : activeDefaultModel;
        String promptKey = defaultSystemPrompt != null ? String.valueOf(defaultSystemPrompt.hashCode()) : "none";
        String cacheKey = effectiveProviderId + ":" + effectiveModelName + ":" + promptKey;

        return chatClientCache.computeIfAbsent(cacheKey, k -> {
            ChatModel chatModel = getChatModel(effectiveProviderId, effectiveModelName);
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            if (defaultSystemPrompt != null && !defaultSystemPrompt.isBlank()) {
                builder.defaultSystem(defaultSystemPrompt);
            }
            return builder.build();
        });
    }

    public ChatClient resolveChatClient(
            String sessionProvider,
            String sessionModel,
            String turnProvider,
            String turnModel,
            String defaultSystemPrompt) {
        // Priority: turn override > session setting > active default
        String provider = turnProvider != null && !turnProvider.isBlank()
                ? turnProvider
                : (sessionProvider != null && !sessionProvider.isBlank() ? sessionProvider : activeDefaultProvider);

        String model = turnModel != null && !turnModel.isBlank()
                ? turnModel
                : (sessionModel != null && !sessionModel.isBlank() ? sessionModel : activeDefaultModel);

        return getChatClient(provider, model, defaultSystemPrompt);
    }

    public ChatModel resolveChatModel(
            String sessionProvider,
            String sessionModel,
            String turnProvider,
            String turnModel) {
        String provider = turnProvider != null && !turnProvider.isBlank()
                ? turnProvider
                : (sessionProvider != null && !sessionProvider.isBlank() ? sessionProvider : activeDefaultProvider);

        String model = turnModel != null && !turnModel.isBlank()
                ? turnModel
                : (sessionModel != null && !sessionModel.isBlank() ? sessionModel : activeDefaultModel);

        return getChatModel(provider, model);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        return getChatModel(activeDefaultProvider, activeDefaultModel).call(prompt);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return getChatModel(activeDefaultProvider, activeDefaultModel).stream(prompt);
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return getChatModel(activeDefaultProvider, activeDefaultModel).getDefaultOptions();
    }
}
