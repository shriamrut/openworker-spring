package com.openworker.agent.providers;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;

import com.openworker.agent.models.publics.ProviderSummaryResponse;
import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

public interface ModelProvider {

    String getProviderId();

    String getDisplayName();

    boolean isAvailable();

    String getStatusMessage();

    String getDefaultModel();

    List<String> getAvailableModels();

    String getBaseUrl();

    ChatModel getChatModel(String modelName);

    void updateConfig(UpdateProviderConfigRequest req);

    default ProviderSummaryResponse getSummary(boolean isCurrentDefault) {
        return new ProviderSummaryResponse(
                getProviderId(),
                getDisplayName(),
                isAvailable(),
                getStatusMessage(),
                getDefaultModel(),
                getAvailableModels(),
                getBaseUrl(),
                isCurrentDefault
        );
    }
}
