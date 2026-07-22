package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Duration;
import java.time.Instant;

/**
 * Public, payload-free command for compiling one immutable Stage 1 mirror plan.
 *
 * <p>The caller identifies reviewed control-plane artifacts but cannot submit isolation booleans,
 * an execution purpose, allowed regions, lifecycle policy, or clearance. Those facts are derived
 * from the authenticated request and server policy. The fixture value and governed replay values
 * are resolved from authoritative stores and never cross this wire boundary.</p>
 *
 * @param schemaVersion request protocol version
 * @param planId stable idempotency identity inside the authenticated enterprise scope
 * @param graphName exact registered BLOGE graph name
 * @param expectedGraphArtifactFingerprint caller-reviewed graph artifact identity
 * @param capabilityClosure sealed root-plus-dependency capability closure
 * @param fixtureBundleRef exact existing governed fixture revision
 * @param maximumInvocations requested whole-run invocation budget, bounded by server policy
 * @param timeout requested whole-run logical timeout, bounded by server policy
 * @param certificationRequired whether non-certifiable fixture sources must fail compilation
 * @param expiresAt requested hard plan expiry, bounded by server policy
 */
public record MirrorPlanCreateRequest(
        String schemaVersion,
        String planId,
        String graphName,
        String expectedGraphArtifactFingerprint,
        CapabilityClosure capabilityClosure,
        MirrorArtifactRef fixtureBundleRef,
        int maximumInvocations,
        @JsonFormat(shape = JsonFormat.Shape.STRING) Duration timeout,
        boolean certificationRequired,
        Instant expiresAt
) {
    /** Current public create-command version. */
    public static final String SCHEMA_VERSION = "resourceGateway.mirrorPlanCreateRequest.v1";

    /** Applies the current version default; semantic validation belongs to the authenticated service. */
    public MirrorPlanCreateRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        planId = normalize(planId);
        graphName = normalize(graphName);
        expectedGraphArtifactFingerprint = normalize(expectedGraphArtifactFingerprint);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
