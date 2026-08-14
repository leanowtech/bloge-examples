package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Immutable server-attested binding from one Proposal revision to one executable runtime port. */
public record CapabilityImplementationBinding(
        String schemaVersion,
        String bindingId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        MirrorArtifactRef proposalDraftRef,
        MirrorArtifactRef simulationEvidenceRef,
        MirrorArtifactRef targetCapabilityRef,
        String candidateContractFingerprint,
        String runtimePortRef,
        String runtimePortFingerprint,
        String implementationVersion,
        String implementationFingerprint,
        String runtimeOwner,
        List<String> allowedRegions,
        boolean readOnly,
        boolean stateless,
        Instant runtimeAttestedAt,
        Instant expiresAt,
        Instant createdAt
) {
    /** Current immutable implementation-binding protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.capabilityImplementationBinding.v1";

    /** Enforces exact identity, runtime safety, and bounded validity. */
    public CapabilityImplementationBinding {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        bindingId = BusinessMirrorProtocolSupport.identifier(bindingId, "bindingId");
        if (revision < 1) {
            throw new IllegalArgumentException("implementation binding revision must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        proposalDraftRef = BusinessMirrorProtocolSupport.exactRef(
                proposalDraftRef, "CAPABILITY_PROPOSAL_DRAFT", "proposalDraftRef");
        simulationEvidenceRef = BusinessMirrorProtocolSupport.exactRef(
                simulationEvidenceRef, "PROPOSAL_SIMULATION_EVIDENCE", "simulationEvidenceRef");
        targetCapabilityRef = BusinessMirrorProtocolSupport.exactRef(
                targetCapabilityRef, "CAPABILITY", "targetCapabilityRef");
        candidateContractFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                candidateContractFingerprint, "candidateContractFingerprint");
        runtimePortRef = BusinessMirrorProtocolSupport.identifier(runtimePortRef, "runtimePortRef");
        runtimePortFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                runtimePortFingerprint, "runtimePortFingerprint");
        implementationVersion = BusinessMirrorProtocolSupport.normalized(implementationVersion);
        implementationFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                implementationFingerprint, "implementationFingerprint");
        runtimeOwner = BusinessMirrorProtocolSupport.normalized(runtimeOwner);
        allowedRegions = BusinessMirrorProtocolSupport.normalizedList(
                allowedRegions, "allowedRegions");
        runtimeAttestedAt = Objects.requireNonNull(runtimeAttestedAt, "runtimeAttestedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        if (implementationVersion.isBlank() || runtimeOwner.isBlank()
                || implementationVersion.length() > 512 || runtimeOwner.length() > 512
                || allowedRegions.isEmpty() || allowedRegions.size() > 128
                || allowedRegions.stream().anyMatch(region -> region.length() > 128)
                || !readOnly || !stateless
                || runtimeAttestedAt.isAfter(createdAt) || !expiresAt.isAfter(createdAt)
                || !allowedRegions.contains(scope.region())) {
            throw new IllegalArgumentException("implementation binding is unsafe or incomplete");
        }
    }

    /** @return exact content-addressed binding reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("implementation binding is not content-addressed");
        }
        return new MirrorArtifactRef(
                "PROPOSAL_IMPLEMENTATION_BINDING", bindingId, revision, fingerprint);
    }

    /** @return content-addressed immutable binding */
    public CapabilityImplementationBinding seal(ObjectMapper mapper) {
        CapabilityImplementationBinding material = withFingerprint("");
        return material.withFingerprint(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(mapper, "mapper"), material, 4 * 1024 * 1024));
    }

    /** Recomputes and verifies the binding content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("implementation binding fingerprint mismatch");
        }
    }

    private CapabilityImplementationBinding withFingerprint(String value) {
        return new CapabilityImplementationBinding(schemaVersion, bindingId, revision, value,
                scope, proposalDraftRef, simulationEvidenceRef, targetCapabilityRef,
                candidateContractFingerprint, runtimePortRef, runtimePortFingerprint,
                implementationVersion, implementationFingerprint, runtimeOwner, allowedRegions,
                readOnly, stateless, runtimeAttestedAt, expiresAt, createdAt);
    }
}
