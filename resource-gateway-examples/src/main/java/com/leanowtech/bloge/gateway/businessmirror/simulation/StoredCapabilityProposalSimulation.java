package com.leanowtech.bloge.gateway.businessmirror.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/** Durable exact response for one completed Proposal simulation command. */
public record StoredCapabilityProposalSimulation(
        String schemaVersion,
        String requestFingerprint,
        CapabilityProposalSimulationEvidence evidence,
        VisualRunEvidenceSeal attestation,
        CapabilityProposalSnapshot proposalSnapshot,
        Instant completedAt
) {
    /** Current durable simulation result protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.storedCapabilityProposalSimulation.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces one signed evidence and Proposal-snapshot identity closure. */
    public StoredCapabilityProposalSimulation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        evidence = Objects.requireNonNull(evidence, "evidence");
        attestation = Objects.requireNonNull(attestation, "attestation");
        proposalSnapshot = Objects.requireNonNull(proposalSnapshot, "proposalSnapshot");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !attestation.signed()
                || !evidence.fingerprint().equals(attestation.materialFingerprint())
                || !evidence.proposalDraftRef().id().equals(proposalSnapshot.proposalId())
                || evidence.proposalDraftRef().revision()
                != proposalSnapshot.sourceDraftRevision()
                || !proposalSnapshot.evidenceRefs().contains(evidence.artifactRef())
                || !completedAt.equals(evidence.completedAt())
                || !completedAt.equals(proposalSnapshot.createdAt())) {
            throw new IllegalArgumentException("Stored Proposal simulation is inconsistent");
        }
    }

    /** Recomputes content addresses and verifies the detached aggregate signature. */
    public void verify(ObjectMapper mapper, VisualEvidenceSigner signer) {
        evidence.verify(mapper);
        proposalSnapshot.verify(mapper);
        VisualEvidenceSigner.Verification verification = Objects.requireNonNull(signer, "signer")
                .verify(attestation, evidence.fingerprint());
        if (!verification.valid()) {
            throw new IllegalArgumentException("Proposal simulation attestation is invalid");
        }
    }
}
