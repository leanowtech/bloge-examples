package com.leanowtech.bloge.gateway.visual.golden;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory golden case repository for tests and local overrides.
 */
public class InMemoryVisualGraphGoldenCaseRepository implements VisualGraphGoldenCaseRepository {

    private final Map<String, VisualGraphGoldenCase> cases = new ConcurrentHashMap<>();

    @Override
    public Collection<VisualGraphGoldenCase> all() {
        return cases.values().stream()
                .sorted(Comparator.comparing(VisualGraphGoldenCase::createdAt).reversed()
                        .thenComparing(VisualGraphGoldenCase::caseId))
                .toList();
    }

    @Override
    public Optional<VisualGraphGoldenCase> find(String caseId) {
        return Optional.ofNullable(cases.get(caseId));
    }

    @Override
    public VisualGraphGoldenCase save(VisualGraphGoldenCase testCase) {
        String caseId = testCase.caseId().isBlank() ? UUID.randomUUID().toString() : testCase.caseId();
        VisualGraphGoldenCase stored = testCase.withIdentity(caseId, testCase.createdAt() == null
                ? Instant.now()
                : testCase.createdAt());
        cases.put(caseId, stored);
        return stored;
    }

    @Override
    public boolean delete(String caseId) {
        return caseId != null && cases.remove(caseId) != null;
    }
}
