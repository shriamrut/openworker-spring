package com.openworker.agent.providers;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.openworker.agent.models.publics.UpdateProviderConfigRequest;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class ModelProviderRegistry {

    private final Map<String, ModelProvider> providers = new ConcurrentHashMap<>();

    public ModelProviderRegistry(List<ModelProvider> providerList) {
        if (providerList != null) {
            for (ModelProvider provider : providerList) {
                providers.put(provider.getProviderId().toLowerCase(), provider);
                log.info("Registered ModelProvider: id='{}', name='{}', available={}",
                        provider.getProviderId(), provider.getDisplayName(), provider.isAvailable());
            }
        }
    }

    public Optional<ModelProvider> getProvider(String providerId) {
        if (providerId == null || providerId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(providerId.toLowerCase().trim()));
    }

    public Collection<ModelProvider> getAllProviders() {
        return providers.values();
    }

    public boolean hasProvider(String providerId) {
        return providerId != null && providers.containsKey(providerId.toLowerCase().trim());
    }

    public void updateProviderConfig(String providerId, UpdateProviderConfigRequest req) {
        ModelProvider provider = getProvider(providerId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown model provider: " + providerId));
        provider.updateConfig(req);
    }
}
