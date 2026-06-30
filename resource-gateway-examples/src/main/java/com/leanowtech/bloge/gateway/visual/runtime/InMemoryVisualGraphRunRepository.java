package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory visual graph run history repository for tests and local overrides.
 */
public class InMemoryVisualGraphRunRepository implements VisualGraphRunRepository {

    private final Map<String, VisualGraphRunRecord> records = new ConcurrentHashMap<>();

    @Override
    public Collection<VisualGraphRunRecord> all() {
        return records.values().stream()
                .sorted(Comparator.comparing(VisualGraphRunRecord::createdAt).reversed()
                        .thenComparing(VisualGraphRunRecord::runId))
                .toList();
    }

    @Override
    public Optional<VisualGraphRunRecord> find(String runId) {
        return Optional.ofNullable(records.get(runId));
    }

    @Override
    public VisualGraphRunRecord create(VisualGraphRunRecord record) {
        String runId = record.runId().isBlank() ? UUID.randomUUID().toString() : record.runId();
        VisualGraphRunRecord stored = record.withIdentity(runId, Instant.now());
        VisualGraphRunRecord previous = records.putIfAbsent(runId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Visual graph run already exists: " + runId);
        }
        return stored;
    }
}
