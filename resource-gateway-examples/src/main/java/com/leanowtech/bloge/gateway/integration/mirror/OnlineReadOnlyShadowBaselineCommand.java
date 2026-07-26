package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free command sent to one regional online baseline sidecar.
 *
 * <p>The sidecar resolves the exact scenario request, production baseline binding, workload
 * identity, and vault policy inside its own trust domain. Resource Gateway sends no business
 * request value, endpoint, credential, or response payload.</p>
 *
 * @param schemaVersion exact command wire version
 * @param executionId stable source idempotency identity across worker retries
 * @param requestId durable Resource Gateway request identity
 * @param scope complete enterprise scope
 * @param inventoryRef exact owner-approved fidelity inventory
 * @param unitId exact inventory coverage unit
 * @param scenarioCaseRef exact immutable scenario request source
 * @param targetCapabilityRef exact capability under comparison
 * @param baselineBindingRef exact governed production read binding
 * @param comparisonPolicyRef exact payload-free normalization policy
 * @param accessGrant exact sampling, egress, and kill-switch coordinates
 * @param admissionFingerprint exact online authority admission identity
 * @param admittedAt trusted authority admission time
 * @param deadlineAt earliest job, authority, or connector deadline
 */
public record OnlineReadOnlyShadowBaselineCommand(
        String schemaVersion,
        String executionId,
        String requestId,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef baselineBindingRef,
        MirrorArtifactRef comparisonPolicyRef,
        ReadOnlyShadowJobRequest.AccessGrant accessGrant,
        String admissionFingerprint,
        Instant admittedAt,
        Instant deadlineAt
) {
    /** Current payload-free online baseline command protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowBaselineCommand.v1";
    /** Maximum canonical command admitted to hashing or transport. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            128 * 1024;
    private static final int MAXIMUM_IDEMPOTENCY_BYTES =
            8 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates complete scope, exact references, admission, and source deadline. */
    public OnlineReadOnlyShadowBaselineCommand {
        schemaVersion = required(
                schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported online baseline command schemaVersion");
        }
        executionId = identifier(
                executionId, "executionId");
        requestId = identifier(
                requestId, "requestId");
        scope = Objects.requireNonNull(scope, "scope");
        inventoryRef = kind(
                inventoryRef,
                DomainFidelityInventory.ARTIFACT_KIND,
                "inventoryRef");
        unitId = identifier(unitId, "unitId");
        scenarioCaseRef = kind(
                scenarioCaseRef,
                "SCENARIO_CASE",
                "scenarioCaseRef");
        targetCapabilityRef = kind(
                targetCapabilityRef,
                "CAPABILITY",
                "targetCapabilityRef");
        baselineBindingRef = kind(
                baselineBindingRef,
                "SHADOW_BASELINE_BINDING",
                "baselineBindingRef");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        accessGrant = Objects.requireNonNull(
                accessGrant, "accessGrant");
        admissionFingerprint = fingerprint(
                admissionFingerprint,
                "admissionFingerprint");
        admittedAt = Objects.requireNonNull(
                admittedAt, "admittedAt");
        deadlineAt = Objects.requireNonNull(
                deadlineAt, "deadlineAt");
        if (!deadlineAt.isAfter(admittedAt)) {
            throw new IllegalArgumentException(
                    "online baseline deadline must follow admission");
        }
    }

    /**
     * Recomputes the exact sidecar command content address.
     *
     * @param mapper canonical protocol mapper
     * @return command fingerprint
     */
    public String commandFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                this,
                MAXIMUM_CANONICAL_BYTES);
    }

    /**
     * Derives a payload-free idempotency fingerprint for evidence.
     *
     * @param mapper canonical protocol mapper
     * @return domain-separated idempotency fingerprint
     */
    public String idempotencyKeyFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                new IdempotencyMaterial(
                        "RESOURCE_GATEWAY_ONLINE_READ_ONLY_SHADOW_BASELINE_IDEMPOTENCY_V1",
                        scope,
                        executionId),
                MAXIMUM_IDEMPOTENCY_BYTES);
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef exact =
                Objects.requireNonNull(value, field);
        if (!expected.equals(exact.kind())) {
            throw new IllegalArgumentException(
                    field + " has an invalid artifact kind");
        }
        return exact;
    }

    private static String identifier(
            String value,
            String field) {
        String exact = required(value, field);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(
            String value,
            String field) {
        String exact = required(value, field);
        if (!exact.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " is invalid");
        }
        return exact;
    }

    private static String required(
            String value,
            String field) {
        String exact = value == null
                ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " is blank or unbounded");
        }
        return exact;
    }

    private record IdempotencyMaterial(
            String domain,
            CapabilitySnapshot.Scope scope,
            String executionId
    ) {
    }
}
