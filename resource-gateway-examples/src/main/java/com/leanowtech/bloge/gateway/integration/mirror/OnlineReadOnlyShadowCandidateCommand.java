package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free command for one sealed online Shadow candidate execution.
 *
 * <p>The command binds the candidate to the exact signed baseline observation, regional payload
 * vault receipt, and request-context fingerprint. The candidate authority resolves the payload
 * inside its isolated trust domain; Resource Gateway never transports a business value,
 * credential, endpoint, or response body.</p>
 *
 * @param schemaVersion exact command wire version
 * @param executionId stable logical execution identity across worker retries
 * @param requestId durable Resource Gateway request identity
 * @param scope complete enterprise scope
 * @param inventoryRef exact owner-approved fidelity inventory
 * @param unitId exact inventory coverage unit
 * @param scenarioCaseRef exact immutable scenario request source
 * @param targetCapabilityRef exact capability under comparison
 * @param candidatePlanRef exact sealed candidate plan
 * @param comparisonPolicyRef exact payload-free normalization policy
 * @param baselineObservationRef exact independently verified baseline observation
 * @param payloadVaultReceiptRef exact regional payload-vault receipt
 * @param requestContextFingerprint canonical request identity shared with the baseline
 * @param accessGrant exact sampling, egress, and kill-switch coordinates
 * @param admissionFingerprint exact online authority admission identity
 * @param admittedAt trusted authority admission time
 * @param deadlineAt earliest job, authority, or candidate deadline
 */
public record OnlineReadOnlyShadowCandidateCommand(
        String schemaVersion,
        String executionId,
        String requestId,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef inventoryRef,
        String unitId,
        MirrorArtifactRef scenarioCaseRef,
        MirrorArtifactRef targetCapabilityRef,
        MirrorArtifactRef candidatePlanRef,
        MirrorArtifactRef comparisonPolicyRef,
        MirrorArtifactRef baselineObservationRef,
        MirrorArtifactRef payloadVaultReceiptRef,
        String requestContextFingerprint,
        ReadOnlyShadowJobRequest.AccessGrant accessGrant,
        String admissionFingerprint,
        Instant admittedAt,
        Instant deadlineAt
) {
    /** Current payload-free online candidate command protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.onlineReadOnlyShadowCandidateCommand.v1";
    /** Maximum canonical command admitted to hashing or transport. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            160 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile(
                    "[A-Za-z0-9][A-Za-z0-9@._:/-]{0,511}");

    /** Validates complete scope, source-pair, plan, admission, and deadline closure. */
    public OnlineReadOnlyShadowCandidateCommand {
        schemaVersion = required(
                schemaVersion, "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported online candidate command schemaVersion");
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
        candidatePlanRef = kind(
                candidatePlanRef,
                "MIRROR_PLAN",
                "candidatePlanRef");
        comparisonPolicyRef = kind(
                comparisonPolicyRef,
                "SHADOW_COMPARISON_POLICY",
                "comparisonPolicyRef");
        baselineObservationRef = kind(
                baselineObservationRef,
                OnlineReadOnlyShadowBaselineObservation
                        .ARTIFACT_KIND,
                "baselineObservationRef");
        payloadVaultReceiptRef = kind(
                payloadVaultReceiptRef,
                "PAYLOAD_VAULT_RECEIPT",
                "payloadVaultReceiptRef");
        requestContextFingerprint = fingerprint(
                requestContextFingerprint,
                "requestContextFingerprint");
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
                    "online candidate deadline must follow admission");
        }
    }

    /**
     * Recomputes the exact candidate command content address.
     *
     * <p>Candidate evidence uses this fingerprint as its request identity. That closes every
     * source, plan, grant, and time coordinate under the independently verified evidence seal and
     * makes an altered retry conflict instead of silently reusing a previous run.</p>
     *
     * @param mapper canonical protocol mapper
     * @return complete candidate command fingerprint
     */
    public String commandFingerprint(
            ObjectMapper mapper) {
        return ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"),
                this,
                MAXIMUM_CANONICAL_BYTES);
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
}
