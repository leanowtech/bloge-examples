package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.util.Objects;

/** Exact command for comparing one attested implementation with its accepted simulation. */
public record CapabilityImplementationConformanceRequest(
        String schemaVersion,
        MirrorArtifactRef implementationBindingRef,
        MirrorArtifactRef simulationEvidenceRef,
        String expectedProposalDraftFingerprint
) {
    /** Current implementation-conformance command protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityImplementationConformanceRequest.v1";

    /** Rejects aliases and mutable "latest" coordinates at the transport-independent boundary. */
    public CapabilityImplementationConformanceRequest {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        implementationBindingRef = requireKind(
                implementationBindingRef, "PROPOSAL_IMPLEMENTATION_BINDING");
        simulationEvidenceRef = requireKind(
                simulationEvidenceRef, "PROPOSAL_SIMULATION_EVIDENCE");
        expectedProposalDraftFingerprint = expectedProposalDraftFingerprint == null
                ? "" : expectedProposalDraftFingerprint.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !expectedProposalDraftFingerprint.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("implementation conformance request is invalid");
        }
    }

    private static MirrorArtifactRef requireKind(MirrorArtifactRef ref, String kind) {
        MirrorArtifactRef exact = Objects.requireNonNull(ref, kind);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException("reference must have kind " + kind);
        }
        return exact;
    }
}
