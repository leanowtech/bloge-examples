package com.leanowtech.bloge.gateway.visual.resource;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory contract registry for the example application.
 */
public class InMemoryResourceDesignContractRegistry implements ResourceDesignContractRegistry {

    private final Map<String, ResourceDesignContract> contracts = new ConcurrentHashMap<>();

    @Override
    public Collection<ResourceDesignContract> all() {
        return contracts.values().stream()
                .sorted(Comparator.comparing(ResourceDesignContract::resourceId))
                .toList();
    }

    @Override
    public Optional<ResourceDesignContract> findByResourceId(String resourceId) {
        return Optional.ofNullable(contracts.get(resourceId));
    }

    @Override
    public ResourceDesignContract upsert(ResourceDesignContract contract) {
        contracts.put(contract.resourceId(), contract);
        return contract;
    }

    @Override
    public void deleteByResourceId(String resourceId) {
        contracts.remove(resourceId);
    }
}
