package com.openworker.agent.models.publics;

import java.util.List;

public record ProviderSummaryResponse(
        String id,
        String name,
        boolean available,
        String statusMessage,
        String defaultModel,
        List<String> models,
        String baseUrl,
        boolean isCurrentDefault) {
}
