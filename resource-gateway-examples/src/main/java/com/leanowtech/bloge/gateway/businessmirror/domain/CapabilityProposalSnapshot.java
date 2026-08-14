package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Immutable Proposal fact combining one exact authoring draft with server-derived evidence state.
 */
public record CapabilityProposalSnapshot(
        String schemaVersion,
        String proposalId,
        long revision,
        String fingerprint,
        CapabilitySnapshot.Scope scope,
        long sourceDraftRevision,
        String sourceDraftFingerprint,
        CapabilityProposalDraft.BusinessIntent businessIntent,
        CapabilityContract candidateContract,
        List<MirrorArtifactRef> fixturePackRefs,
        List<MirrorArtifactRef> businessAcceptanceSuiteRefs,
        CapabilityProposalDraft.SimulationRuntimeBinding simulationRuntimeBinding,
        MirrorArtifactRef implementationBindingRef,
        EvidenceState evidenceState,
        List<MirrorArtifactRef> evidenceRefs,
        List<String> assumptions,
        List<String> limitations,
        Instant expiresAt,
        ArtifactProvenance provenance,
        Instant createdAt
) {
    /** Current immutable Proposal snapshot protocol. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityProposalSnapshot.v1";

    /** State derived from exact implementation and evidence artifacts, never from an author command. */
    public enum EvidenceState {
        NOT_RUN,
        SIMULATED,
        IMPLEMENTED,
        CONFORMANT,
        CALIBRATED
    }

    /** Enforces evidence monotonicity and immutable Proposal coordinates. */
    public CapabilityProposalSnapshot {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        proposalId = BusinessMirrorProtocolSupport.identifier(proposalId, "proposalId");
        if (revision < 1 || sourceDraftRevision < 1) {
            throw new IllegalArgumentException("Proposal snapshot and source revisions must be positive");
        }
        fingerprint = BusinessMirrorProtocolSupport.optionalFingerprint(fingerprint, "fingerprint");
        scope = java.util.Objects.requireNonNull(scope, "scope");
        sourceDraftFingerprint = BusinessMirrorProtocolSupport.fingerprint(
                sourceDraftFingerprint, "sourceDraftFingerprint");
        businessIntent = java.util.Objects.requireNonNull(businessIntent, "businessIntent");
        candidateContract = java.util.Objects.requireNonNull(candidateContract, "candidateContract");
        fixturePackRefs = BusinessMirrorProtocolSupport.exactRefs(
                fixturePackRefs, Set.of("FIXTURE_BUNDLE"), "fixturePackRefs");
        businessAcceptanceSuiteRefs = BusinessMirrorProtocolSupport.exactRefs(
                businessAcceptanceSuiteRefs,
                Set.of("TEST_SUITE", "SCENARIO_PACK"),
                "businessAcceptanceSuiteRefs");
        if (fixturePackRefs.isEmpty() || businessAcceptanceSuiteRefs.isEmpty()) {
            throw new IllegalArgumentException("Proposal snapshot requires Fixture and acceptance suites");
        }
        simulationRuntimeBinding = java.util.Objects.requireNonNull(
                simulationRuntimeBinding, "simulationRuntimeBinding");
        implementationBindingRef = BusinessMirrorProtocolSupport.optionalRef(
                implementationBindingRef,
                "PROPOSAL_IMPLEMENTATION_BINDING",
                "implementationBindingRef");
        evidenceState = evidenceState == null ? EvidenceState.NOT_RUN : evidenceState;
        evidenceRefs = BusinessMirrorProtocolSupport.immutableRefs(evidenceRefs, "evidenceRefs");
        assumptions = BusinessMirrorProtocolSupport.normalizedList(assumptions, "assumptions");
        limitations = BusinessMirrorProtocolSupport.normalizedList(limitations, "limitations");
        expiresAt = java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("Proposal snapshot expiry must be after creation");
        }
        if (!scope.tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException("Proposal provenance tenant must match Proposal scope");
        }
        verifyEvidenceState(evidenceState, implementationBindingRef, evidenceRefs);
    }

    /** @return exact content-addressed Proposal reference */
    public MirrorArtifactRef artifactRef() {
        if (fingerprint.isBlank()) {
            throw new IllegalStateException("Capability Proposal snapshot is not content-addressed");
        }
        return new MirrorArtifactRef("CAPABILITY_PROPOSAL", proposalId, revision, fingerprint);
    }

    /** @return identical Proposal snapshot with a replacement fingerprint */
    public CapabilityProposalSnapshot withFingerprint(String value) {
        return new CapabilityProposalSnapshot(schemaVersion, proposalId, revision, value, scope,
                sourceDraftRevision, sourceDraftFingerprint, businessIntent, candidateContract,
                fixturePackRefs, businessAcceptanceSuiteRefs, simulationRuntimeBinding,
                implementationBindingRef, evidenceState, evidenceRefs, assumptions, limitations,
                expiresAt, provenance, createdAt);
    }

    /** @return content-addressed Proposal snapshot */
    public CapabilityProposalSnapshot seal(ObjectMapper mapper) {
        return withFingerprint(ProtocolFingerprint.ofBounded(
                java.util.Objects.requireNonNull(mapper, "mapper"), withFingerprint(""),
                BusinessMirrorProtocolSupport.MAXIMUM_CANONICAL_BYTES));
    }

    /** Recomputes and verifies the Proposal content address. */
    public void verify(ObjectMapper mapper) {
        if (fingerprint.isBlank() || !fingerprint.equals(seal(mapper).fingerprint())) {
            throw new IllegalArgumentException("Capability Proposal snapshot fingerprint mismatch");
        }
    }

    private static void verifyEvidenceState(EvidenceState state,
                                            MirrorArtifactRef implementationBindingRef,
                                            List<MirrorArtifactRef> evidenceRefs) {
        boolean simulated = hasKind(evidenceRefs, "PROPOSAL_SIMULATION_EVIDENCE");
        boolean conformance = hasKind(evidenceRefs, "IMPLEMENTATION_CONFORMANCE_REPORT");
        boolean calibration = hasKind(evidenceRefs, "DOMAIN_FIDELITY_PROFILE")
                || hasKind(evidenceRefs, "AUTHORITATIVE_OUTCOME_OBSERVATION");
        if (state == EvidenceState.NOT_RUN && !evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("NOT_RUN Proposal must not carry evidence");
        }
        if (state.ordinal() >= EvidenceState.SIMULATED.ordinal() && !simulated) {
            throw new IllegalArgumentException("SIMULATED or later Proposal requires simulation evidence");
        }
        if (state.ordinal() >= EvidenceState.IMPLEMENTED.ordinal() && implementationBindingRef == null) {
            throw new IllegalArgumentException("IMPLEMENTED or later Proposal requires implementation binding");
        }
        if (state.ordinal() >= EvidenceState.CONFORMANT.ordinal() && !conformance) {
            throw new IllegalArgumentException("CONFORMANT or later Proposal requires conformance evidence");
        }
        if (state == EvidenceState.CALIBRATED && !calibration) {
            throw new IllegalArgumentException("CALIBRATED Proposal requires independent calibration evidence");
        }
    }

    private static boolean hasKind(List<MirrorArtifactRef> refs, String kind) {
        return refs.stream().anyMatch(ref -> kind.equals(ref.kind()));
    }
}
