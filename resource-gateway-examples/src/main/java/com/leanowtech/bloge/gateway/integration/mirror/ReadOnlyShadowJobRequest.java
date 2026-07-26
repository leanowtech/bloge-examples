package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free command for one durable read-only Shadow comparison.
 *
 * <p>The command freezes only authorization and execution coordinates. It does not claim that a
 * source was read, that a candidate ran, or that no write occurred. Those facts are produced by
 * the trusted data plane after admission and become the signed comparison's {@link
 * ReadOnlyShadowComparison.AccessProof}. Keeping the grant separate from the proof prevents a
 * caller-supplied boolean from masquerading as runtime isolation evidence.</p>
 *
 * @param schemaVersion exact submission protocol version
 * @param requestId caller-stable idempotency identity inside the complete scope
 * @param scope complete enterprise namespace
 * @param inventoryRef exact owner-approved Fidelity inventory
 * @param unitId exact inventory coverage unit
 * @param scenarioCaseRef exact classified Scenario case
 * @param targetCapabilityRef exact candidate capability revision
 * @param candidatePlanRef exact sealed Mirror plan used by the candidate
 * @param baselineBindingRef exact governed baseline connector generation
 * @param comparisonPolicyRef exact normalized-fact and typed-diff policy
 * @param accessGrant exact sampling, egress, and kill-switch authorization coordinates
 * @param deadlineAt absolute execution deadline interpreted against database time
 */
public record ReadOnlyShadowJobRequest(
        String schemaVersion,
        String requestId,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef candidatePlanRef,
        MirrorArtifactRef baselineBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        AccessGrant accessGrant,
        Instant deadlineAt
) {
    /** Current durable Shadow job submission protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowJobRequest.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates exact payload-free artifact coordinates and a bounded grant position. */
    public ReadOnlyShadowJobRequest {
        schemaVersion = version(schemaVersion);
        requestId = identifier(requestId, "requestId");
        scope = Objects.requireNonNull(scope, "scope");
        inventoryRef = requireKind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        unitId = identifier(unitId, "unitId");
        scenarioCaseRef = requireKind(
                scenarioCaseRef,
                "SCENARIO_CASE",
                "scenarioCaseRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        candidatePlanRef = requireKind(
                candidatePlanRef,
                "MIRROR_PLAN",
                "candidatePlanRef");
        baselineBindingRef = requireKind(
                baselineBindingRef,
                "SHADOW_BASELINE_BINDING",
                "baselineBindingRef");
        comparisonPolicyRef = requireKind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        accessGrant = Objects.requireNonNull(
                accessGrant, "accessGrant");
        deadlineAt = Objects.requireNonNull(
                deadlineAt, "deadlineAt");
    }

    /**
     * Authorization coordinates reserved by durable admission before any source access.
     *
     * @param accessMode read-only production access or an isolated safe sandbox
     * @param samplingGrantRef exact Data Governance sampling grant
     * @param egressAuthorityRef exact deployment egress attestation
     * @param killSwitchRef exact enabled kill-switch generation
     * @param sampleOrdinal one-based grant position reserved by this job
     * @param maximumSamples exact upper bound carried by the grant
     */
    public record AccessGrant(
            ReadOnlyShadowComparison.AccessMode accessMode,
            MirrorArtifactRef samplingGrantRef,
            MirrorArtifactRef egressAuthorityRef,
            MirrorArtifactRef killSwitchRef,
            long sampleOrdinal,
            long maximumSamples
    ) {
        /** Requires complete exact references and a bounded one-based sample position. */
        public AccessGrant {
            accessMode = Objects.requireNonNull(
                    accessMode, "accessMode");
            samplingGrantRef = requireKind(
                    samplingGrantRef,
                    "SHADOW_SAMPLING_GRANT",
                    "samplingGrantRef");
            egressAuthorityRef = requireKind(
                    egressAuthorityRef,
                    MirrorDeploymentIsolationAttestation
                            .ARTIFACT_KIND,
                    "egressAuthorityRef");
            killSwitchRef = requireKind(
                    killSwitchRef,
                    "SHADOW_KILL_SWITCH_STATE",
                    "killSwitchRef");
            if (sampleOrdinal < 1
                    || maximumSamples < 1
                    || sampleOrdinal > maximumSamples
                    || maximumSamples > 1_000_000_000L) {
                throw new IllegalArgumentException(
                        "Shadow sampling grant position is invalid");
            }
        }

        /**
         * Converts the reserved authorization into a runtime proof only after the trusted data
         * plane reports that it exposed no write credential and observed no write attempt.
         */
        public ReadOnlyShadowComparison.AccessProof zeroWriteProof() {
            return new ReadOnlyShadowComparison.AccessProof(
                    accessMode,
                    samplingGrantRef,
                    egressAuthorityRef,
                    killSwitchRef,
                    sampleOrdinal,
                    maximumSamples,
                    false,
                    0);
        }
    }

    private static String version(String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isEmpty()) {
            normalized = SCHEMA_VERSION;
        }
        if (!SCHEMA_VERSION.equals(normalized)) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow job request schemaVersion");
        }
        return normalized;
    }

    private static String identifier(
            String value,
            String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return normalized;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value,
            String kind,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + kind);
        }
        return exact;
    }
}
