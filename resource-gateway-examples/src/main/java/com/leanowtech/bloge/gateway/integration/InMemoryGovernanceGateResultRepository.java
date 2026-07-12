package com.leanowtech.bloge.gateway.integration;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory governance gate repository for focused tests and embedding. */
public class InMemoryGovernanceGateResultRepository implements GovernanceGateResultRepository {
    private final ConcurrentHashMap<String, GovernanceGateResult> results = new ConcurrentHashMap<>();

    @Override
    public Optional<GovernanceGateResult> find(String gateResultId) {
        return Optional.ofNullable(results.get(gateResultId));
    }

    @Override
    public List<GovernanceGateResult> forDraft(String draftId) {
        return results.values().stream()
                .filter(result -> result.target().draftId().equals(draftId))
                .sorted(Comparator.comparing(GovernanceGateResult::producedAt).reversed()
                        .thenComparing(GovernanceGateResult::gateResultId))
                .toList();
    }

    @Override
    public GovernanceGateResult create(GovernanceGateResult result) {
        GovernanceGateResult previous = results.putIfAbsent(result.gateResultId(), result);
        if (previous != null) {
            throw new IllegalArgumentException("Governance gate result already exists: " + result.gateResultId());
        }
        return result;
    }
}
