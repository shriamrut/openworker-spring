package com.openworker.agent.models.publics;

public record UpdateProviderConfigRequest(
        String apiKey,
        String baseUrl,
        String defaultModel) {
}
