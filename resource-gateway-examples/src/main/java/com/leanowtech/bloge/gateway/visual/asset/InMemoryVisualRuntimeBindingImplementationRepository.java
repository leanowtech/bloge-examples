package com.leanowtech.bloge.gateway.visual.asset;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory runtime implementation binding repository for tests and local overrides.
 */
public class InMemoryVisualRuntimeBindingImplementationRepository
        implements VisualRuntimeBindingImplementationRepository {

    private final ConcurrentHashMap<String, VisualRuntimeBindingImplementationBinding> bindings =
            new ConcurrentHashMap<>();

    @Override
    public Collection<VisualRuntimeBindingImplementationBinding> all() {
        return bindings.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeBindingImplementationBinding::createdAt)
                        .reversed()
                        .thenComparing(VisualRuntimeBindingImplementationBinding::bindingId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeBindingImplementationBinding> find(String bindingId) {
        return Optional.ofNullable(bindings.get(bindingId));
    }

    @Override
    public VisualRuntimeBindingImplementationBinding create(VisualRuntimeBindingImplementationBinding binding) {
        String bindingId = binding.bindingId().isBlank() ? UUID.randomUUID().toString() : binding.bindingId();
        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding stored = binding.withIdentity(bindingId, 1, now, now);
        VisualRuntimeBindingImplementationBinding previous = bindings.putIfAbsent(bindingId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime binding implementation already exists: " + bindingId);
        }
        return stored;
    }

    @Override
    public VisualRuntimeBindingImplementationBinding update(VisualRuntimeBindingImplementationBinding binding) {
        if (binding == null || binding.bindingId().isBlank()) {
            throw new IllegalArgumentException("Runtime binding implementation id is required for update.");
        }
        if (!bindings.containsKey(binding.bindingId())) {
            throw new IllegalArgumentException("Runtime binding implementation does not exist: " + binding.bindingId());
        }
        bindings.put(binding.bindingId(), binding);
        return binding;
    }
}
