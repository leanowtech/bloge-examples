package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Objects;
import java.util.regex.Pattern;

/** Payload-free command selecting the exact graph context for one Proposal revision simulation. */
public record CapabilityProposalSimulationRequest(
        String schemaVersion,
        String expectedProposalDraftFingerprint,
        MirrorArtifactRef packageRef,
        MirrorArtifactRef graphRef,
        MirrorArtifactRef targetCapabilityRef
) {
    /** Current public simulation command protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityProposalSimulationRequest.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Normalizes the version and rejects references that cannot identify the simulation context. */
    public CapabilityProposalSimulationRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        expectedProposalDraftFingerprint = normalized(expectedProposalDraftFingerprint);
        packageRef = requireKind(packageRef, "DOMAIN_CAPABILITY_PACKAGE", "packageRef");
        graphRef = requireKind(graphRef, "GRAPH_DRAFT", "graphRef");
        targetCapabilityRef = requireKind(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(expectedProposalDraftFingerprint).matches()) {
            throw new IllegalArgumentException("unsupported Proposal simulation request version");
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
