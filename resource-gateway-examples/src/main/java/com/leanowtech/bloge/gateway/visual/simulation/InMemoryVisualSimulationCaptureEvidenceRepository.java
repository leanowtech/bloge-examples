package com.leanowtech.bloge.gateway.visual.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Bounded process-local capture store used by the example gateway.
 *
 * <p>Simulation evidence is intentionally ephemeral: it is a server receipt for the immediately
 * following authoring lifecycle, not a durable payload or an approval record. TTL and entry bounds
 * keep this fallback safe for the example deployment while tenant/draft/node keys prevent cross-
 * scope reuse. Deployments that need cross-replica capture can implement the same interface with a
 * durable, payload-free store.</p>
 */
public class InMemoryVisualSimulationCaptureEvidenceRepository
        implements VisualSimulationCaptureEvidenceRepository {
    private static final int DEFAULT_MAX_ENTRIES = 4_096;

    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration ttl;
    private final int maxEntries;
    private final Map<CaptureKey, VisualSimulationCaptureEvidence> captures = new LinkedHashMap<>();

    /** Creates a bounded store with application-safe defaults. */
    public InMemoryVisualSimulationCaptureEvidenceRepository(ObjectMapper mapper) {
        this(mapper, Clock.systemUTC(), VisualSimulationCaptureEvidence.DEFAULT_TTL,
                DEFAULT_MAX_ENTRIES);
    }

    /** Creates a bounded store with an injectable clock and TTL for deterministic tests. */
    public InMemoryVisualSimulationCaptureEvidenceRepository(ObjectMapper mapper,
                                                              Clock clock,
                                                              Duration ttl,
                                                              int maxEntries) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative() || maxEntries < 1) {
            throw new IllegalArgumentException("Capture store bounds must be positive");
        }
        this.maxEntries = maxEntries;
    }

    @Override
    public synchronized void recordSuccessfulSimulation(VisualGraphSimulationRequest request,
                                                          VisualGraphSimulationResponse response,
                                                          VisualOperatorCatalog catalog) {
        Instant now = clock.instant();
        purgeExpired(now);
        VisualSimulationCaptureEvidence.fromSuccessfulSimulation(
                        request, response, catalog, mapper, now, ttl)
                .forEach(evidence -> captures.put(
                        new CaptureKey(evidence.tenantId(), evidence.namespace(), evidence.environment(),
                                evidence.draftId(), evidence.nodeId()), evidence));
        trimToBound();
    }

    @Override
    public synchronized Optional<VisualSimulationCaptureEvidence> find(String tenantId,
                                                                         String namespace,
                                                                         String environment,
                                                                         String draftId,
                                                                         String nodeId) {
        Instant now = clock.instant();
        purgeExpired(now);
        CaptureKey key = new CaptureKey(tenantId, namespace, environment, draftId, nodeId);
        VisualSimulationCaptureEvidence evidence = captures.get(key);
        return evidence != null && evidence.activeAt(now)
                ? Optional.of(evidence) : Optional.empty();
    }

    private void purgeExpired(Instant now) {
        captures.entrySet().removeIf(entry -> !entry.getValue().activeAt(now));
    }

    private void trimToBound() {
        while (captures.size() > maxEntries) {
            Iterator<CaptureKey> oldest = captures.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private record CaptureKey(String tenantId, String namespace, String environment,
                              String draftId, String nodeId) {
    }
}
