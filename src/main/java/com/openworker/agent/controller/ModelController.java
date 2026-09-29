package com.openworker.agent.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openworker.agent.models.publics.ActiveModelResponse;
import com.openworker.agent.models.publics.ProviderSummaryResponse;
import com.openworker.agent.models.publics.SetActiveModelRequest;
import com.openworker.agent.models.publics.UpdateProviderConfigRequest;
import com.openworker.agent.providers.ModelProviderRegistry;
import com.openworker.agent.services.ChatModelRouter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelProviderRegistry providerRegistry;
    private final ChatModelRouter chatModelRouter;

    /**
     * List all model providers, their statuses, default models, and available models.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<ProviderSummaryResponse>> listProviders() {
        String activeProvider = chatModelRouter.getActiveProvider();
        List<ProviderSummaryResponse> summaries = providerRegistry.getAllProviders().stream()
                .map(p -> p.getSummary(p.getProviderId().equalsIgnoreCase(activeProvider)))
                .toList();
        return ResponseEntity.ok(summaries);
    }

    /**
     * Get the globally active default model provider and model.
     */
    @GetMapping("/active")
    public ResponseEntity<ActiveModelResponse> getActiveModel() {
        return ResponseEntity.ok(new ActiveModelResponse(
                chatModelRouter.getActiveProvider(),
                chatModelRouter.getActiveModel()
        ));
    }

    /**
     * Switch the globally active default model provider and model.
     */
    @PostMapping("/active")
    public ResponseEntity<ActiveModelResponse> setActiveModel(@RequestBody SetActiveModelRequest request) {
        log.info("Switching active model default to provider='{}', model='{}'",
                request.provider(), request.model());
        chatModelRouter.setActiveDefault(request.provider(), request.model());
        return ResponseEntity.ok(new ActiveModelResponse(
                chatModelRouter.getActiveProvider(),
                chatModelRouter.getActiveModel()
        ));
    }

    /**
     * Update credentials or endpoint configuration for a specific provider at runtime.
     */
    @PostMapping("/providers/{providerId}/config")
    public ResponseEntity<ProviderSummaryResponse> updateProviderConfig(
            @PathVariable String providerId,
            @RequestBody UpdateProviderConfigRequest request) {
        log.info("Updating runtime configuration for provider: {}", providerId);
        providerRegistry.updateProviderConfig(providerId, request);
        var provider = providerRegistry.getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown provider: " + providerId));
        boolean isDefault = provider.getProviderId().equalsIgnoreCase(chatModelRouter.getActiveProvider());
        return ResponseEntity.ok(provider.getSummary(isDefault));
    }
}
