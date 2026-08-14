package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalSnapshot;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.Objects;

/** Durable exact response for one same-suite implementation-conformance command. */
public record StoredCapabilityImplementationConformance(
        String schemaVersion,
        String requestFingerprint,
        CapabilityImplementationConformanceReport report,
        VisualRunEvidenceSeal attestation,
        CapabilityProposalSnapshot proposalSnapshot,
        Instant completedAt
) {
    /** Current stored conformance-result protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.storedCapabilityImplementationConformance.v1";

    /** Enforces exact cross-object identity before persistence. */
    public StoredCapabilityImplementationConformance {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        report = Objects.requireNonNull(report, "report");
        attestation = Objects.requireNonNull(attestation, "attestation");
        proposalSnapshot = Objects.requireNonNull(proposalSnapshot, "proposalSnapshot");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !requestFingerprint.matches("sha256:[a-f0-9]{64}")
                || !attestation.signed()
                || !report.fingerprint().equals(attestation.materialFingerprint())
                || !completedAt.equals(report.completedAt())
                || proposalSnapshot.evidenceState()
                != (report.status() == CapabilityImplementationConformanceReport.Status.PASSED
                ? CapabilityProposalSnapshot.EvidenceState.CONFORMANT
                : CapabilityProposalSnapshot.EvidenceState.IMPLEMENTED)
                || !proposalSnapshot.evidenceRefs().contains(report.artifactRef())
                || !proposalSnapshot.implementationBindingRef()
                .equals(report.implementationBindingRef())) {
            throw new IllegalArgumentException("stored implementation conformance is inconsistent");
        }
    }

    /** Verifies content addresses and detached report attestation. */
    public void verify(ObjectMapper mapper, VisualEvidenceSigner signer) {
        report.verify(mapper);
        proposalSnapshot.verify(mapper);
        if (!Objects.requireNonNull(signer, "signer")
                .verify(attestation, report.fingerprint()).valid()) {
            throw new IllegalArgumentException("implementation conformance attestation is invalid");
        }
    }
}
