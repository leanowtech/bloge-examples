package com.leanowtech.bloge.gateway.testing.world.draft;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Small deterministic repository used by the local service and focused tests. */
public final class InMemoryWorldDraftCandidateRepository implements WorldDraftCandidateRepository {
    private final ConcurrentHashMap<Key, AtomicReference<WorldDraftCandidate>> values =
            new ConcurrentHashMap<>();

    @Override
    public WorldDraftCandidate create(WorldDraftCandidate candidate) {
        if (candidate == null || candidate.revision() != 1
                || candidate.state() != WorldDraftState.CAPTURED) throw invalid();
        if (values.putIfAbsent(new Key(candidate.tenantId(), candidate.candidateId()),
                new AtomicReference<>(candidate)) != null) {
            throw new WorldDraftCandidateException(WorldDraftCandidateException.Code.CAS_CONFLICT);
        }
        return candidate;
    }

    @Override
    public Optional<WorldDraftCandidate> find(String tenantId, String candidateId) {
        if (tenantId == null || candidateId == null) return Optional.empty();
        AtomicReference<WorldDraftCandidate> value = values.get(new Key(tenantId, candidateId));
        return value == null ? Optional.empty() : Optional.of(value.get());
    }

    @Override
    public boolean compareAndSet(WorldDraftCandidate expected, WorldDraftCandidate replacement) {
        if (expected == null || replacement == null
                || !expected.tenantId().equals(replacement.tenantId())
                || !expected.candidateId().equals(replacement.candidateId())
                || replacement.revision() != expected.revision() + 1
                || !expected.state().mayAdvanceTo(replacement.state())) return false;
        AtomicReference<WorldDraftCandidate> value = values.get(new Key(expected.tenantId(), expected.candidateId()));
        return value != null && value.compareAndSet(expected, replacement);
    }

    private record Key(String tenantId, String candidateId) { }

    private static WorldDraftCandidateException invalid() {
        return new WorldDraftCandidateException(WorldDraftCandidateException.Code.INVALID_INPUT);
    }
}
