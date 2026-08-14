package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Objects;
import java.util.regex.Pattern;

/** Payload-free command selecting exact Proposal evidence and an expected runtime port generation. */
public record CapabilityImplementationBindingRequest(
        String schemaVersion,
        String expectedProposalDraftFingerprint,
        MirrorArtifactRef simulationEvidenceRef,
        MirrorArtifactRef targetCapabilityRef,
        String runtimePortRef,
        String expectedRuntimePortFingerprint,
        String expectedImplementationVersion,
        String expectedImplementationFingerprint
) {
    /** Current implementation-binding request protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityImplementationBindingRequest.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    /** Rejects mutable, incomplete, or wrong-kind coordinates. */
    public CapabilityImplementationBindingRequest {
        schemaVersion = normalized(schemaVersion).isBlank() ? SCHEMA_VERSION : normalized(schemaVersion);
        expectedProposalDraftFingerprint = normalized(expectedProposalDraftFingerprint);
        simulationEvidenceRef = requireKind(
                simulationEvidenceRef, "PROPOSAL_SIMULATION_EVIDENCE", "simulationEvidenceRef");
        targetCapabilityRef = requireKind(targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        runtimePortRef = normalized(runtimePortRef);
        expectedRuntimePortFingerprint = normalized(expectedRuntimePortFingerprint);
        expectedImplementationVersion = normalized(expectedImplementationVersion);
        expectedImplementationFingerprint = normalized(expectedImplementationFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(expectedProposalDraftFingerprint).matches()
                || !IDENTIFIER.matcher(runtimePortRef).matches()
                || !FINGERPRINT.matcher(expectedRuntimePortFingerprint).matches()
                || expectedImplementationVersion.isBlank()
                || expectedImplementationVersion.length() > 512
                || !FINGERPRINT.matcher(expectedImplementationFingerprint).matches()) {
            throw new IllegalArgumentException("implementation binding request is incomplete");
        }
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
