package com.leanowtech.bloge.gateway.testing.planning;

import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;

import java.time.Instant;
import java.util.Objects;

/**
 * Exact server-internal inputs for one pure MirrorPlan compilation.
 *
 * <p>The request contains the already selected graph, capability closure, FixtureBundle, and
 * governed replay closure. The compiler never resolves mutable repositories. API adapters must
 * authenticate, authorize, and freeze those artifacts before constructing this value.</p>
 *
 * @param planId stable caller idempotency identity
 * @param graph exact executable BLOGE graph
 * @param graphArtifactFingerprint exact graph artifact fingerprint
 * @param capabilityClosure sealed root-plus-dependency closure
 * @param fixtureBundle exact existing FixtureBundle revision
 * @param replayPayloads exact governed replay payload closure
 * @param policy server-authorized isolation and execution policy
 * @param scenarioPackRef optional exact scenario pack; reserved until rehearsal runtime is active
 * @param compiledAt server compilation time
 * @param expiresAt requested hard plan expiry
 */
public record MirrorPlanCompilationRequest(
        String planId,
        Graph graph,
        String graphArtifactFingerprint,
        CapabilityClosure capabilityClosure,
        FixtureBundle fixtureBundle,
        ResolvedReplayPayloads replayPayloads,
        MirrorPlan.ExecutionPolicy policy,
        MirrorArtifactRef scenarioPackRef,
        Instant compiledAt,
        Instant expiresAt
) {
    /** Freezes required inputs without resolving any mutable dependency. */
    public MirrorPlanCompilationRequest {
        planId = required(planId, "planId");
        graph = Objects.requireNonNull(graph, "graph");
        graphArtifactFingerprint = required(graphArtifactFingerprint, "graphArtifactFingerprint");
        capabilityClosure = Objects.requireNonNull(capabilityClosure, "capabilityClosure");
        fixtureBundle = Objects.requireNonNull(fixtureBundle, "fixtureBundle");
        replayPayloads = replayPayloads == null ? ResolvedReplayPayloads.empty() : replayPayloads;
        policy = Objects.requireNonNull(policy, "policy");
        if (scenarioPackRef != null && !"SCENARIO_PACK".equals(scenarioPackRef.kind())) {
            throw new IllegalArgumentException("scenarioPackRef must reference SCENARIO_PACK");
        }
        compiledAt = Objects.requireNonNull(compiledAt, "compiledAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
