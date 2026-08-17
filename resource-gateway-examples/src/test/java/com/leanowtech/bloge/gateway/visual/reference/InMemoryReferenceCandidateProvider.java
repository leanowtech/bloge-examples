package com.leanowtech.bloge.gateway.visual.reference;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/** Mutable authority substitute for protocol tests; it stores metadata candidates only. */
final class InMemoryReferenceCandidateProvider implements ReferenceCandidateProvider {
    private final Map<String, ReferenceCandidate> candidates = new LinkedHashMap<>();
    private final Set<String> forbiddenSearchCoordinates = new java.util.HashSet<>();
    private final Set<String> forbiddenResolveIds = new java.util.HashSet<>();
    private final AtomicLong generation = new AtomicLong(1);

    synchronized InMemoryReferenceCandidateProvider add(ReferenceCandidate candidate) {
        candidates.put(coordinate(candidate), candidate);
        generation.incrementAndGet();
        return this;
    }

    synchronized InMemoryReferenceCandidateProvider forbidSearch(ReferenceCandidate candidate) {
        forbiddenSearchCoordinates.add(coordinate(candidate));
        generation.incrementAndGet();
        return this;
    }

    synchronized InMemoryReferenceCandidateProvider forbidResolve(String kind, String id) {
        forbiddenResolveIds.add(kind + "|" + id);
        generation.incrementAndGet();
        return this;
    }

    @Override
    public synchronized ProviderSnapshot snapshot(SearchRequest request) {
        List<ReferenceCandidate> visible = new ArrayList<>();
        for (ReferenceCandidate candidate : candidates.values()) {
            if (!forbiddenSearchCoordinates.contains(coordinate(candidate))) {
                visible.add(candidate);
            }
        }
        return new ProviderSnapshot(generation.get(), visible);
    }

    @Override
    public synchronized ProviderResolution resolve(ResolveRequest request) {
        if (forbiddenResolveIds.contains(request.kind() + "|" + request.id())) {
            return new ProviderResolution(ResolveResult.Status.FORBIDDEN, null);
        }
        ReferenceCandidate current = candidates.values().stream()
                .filter(candidate -> candidate.kind().equals(request.kind()))
                .filter(candidate -> candidate.id().equals(request.id()))
                .findFirst()
                .orElse(null);
        if (current == null) {
            return new ProviderResolution(ResolveResult.Status.NOT_FOUND, null);
        }
        if (!request.scope().matches(current.scope())) {
            return new ProviderResolution(ResolveResult.Status.FORBIDDEN, null);
        }
        if (!current.exactCoordinateEquals(request.kind(), request.id(), request.revision(), request.fingerprint())) {
            return new ProviderResolution(ResolveResult.Status.DRIFTED, current);
        }
        return new ProviderResolution(ResolveResult.Status.RESOLVED, current);
    }

    private static String coordinate(ReferenceCandidate candidate) {
        return candidate.kind() + "|" + candidate.id() + "|" + candidate.revision() + "|" + candidate.fingerprint();
    }
}
