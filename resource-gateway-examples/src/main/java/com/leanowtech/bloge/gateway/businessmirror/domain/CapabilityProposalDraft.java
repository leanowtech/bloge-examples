package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Mutable business specification for a capability that may not have a real implementation.
 *
 * <p>Only the simulation binding is authorable here. Implementation and evidence state are
 * separate server- or governance-owned facts and cannot be forged through this draft.</p>
 */
public record CapabilityProposalDraft(
        String schemaVersion,
        String proposalId,
        long revision,
        CapabilitySnapshot.Scope scope,
        BusinessIntent businessIntent,
        CapabilityContract candidateContract,
        List<MirrorArtifactRef> fixturePackRefs,
        List<MirrorArtifactRef> businessAcceptanceSuiteRefs,
        SimulationRuntimeBinding simulationRuntimeBinding,
        List<String> assumptions,
        List<String> limitations,
        Instant expiresAt,
        ArtifactProvenance provenance,
        Lifecycle lifecycle
) {
    /** Current mutable Proposal authoring protocol. */
    public static final String SCHEMA_VERSION = "bloge.capabilityProposalDraft.v1";

    /** Proposal authoring lifecycle, separate from implementation and governance status. */
    public enum Lifecycle {
        DRAFT,
        READY_FOR_REVIEW,
        SUBMITTED,
        SUPERSEDED
    }

    /** Business intent and value hypothesis for a missing capability. */
    public record BusinessIntent(
            String capabilityGap,
            String expectedValue,
            List<MirrorArtifactRef> applicableScenarioRefs,
            List<MirrorArtifactRef> candidatePackageRefs,
            List<MirrorArtifactRef> candidateGraphRefs,
            String owner
    ) {
        /** Allows an incomplete local DRAFT while retaining exact reference semantics. */
        public BusinessIntent {
            capabilityGap = BusinessMirrorProtocolSupport.normalized(capabilityGap);
            expectedValue = BusinessMirrorProtocolSupport.normalized(expectedValue);
            applicableScenarioRefs = BusinessMirrorProtocolSupport.exactRefs(
                    applicableScenarioRefs, Set.of("SCENARIO_CASE"), "applicableScenarioRefs");
            candidatePackageRefs = BusinessMirrorProtocolSupport.exactRefs(
                    candidatePackageRefs, Set.of("DOMAIN_CAPABILITY_PACKAGE"), "candidatePackageRefs");
            candidateGraphRefs = BusinessMirrorProtocolSupport.exactRefs(
                    candidateGraphRefs, Set.of("GRAPH_DRAFT"), "candidateGraphRefs");
            owner = BusinessMirrorProtocolSupport.normalized(owner);
        }

        /** @return intentionally incomplete business intent for a new draft */
        public static BusinessIntent empty() {
            return new BusinessIntent("", "", List.of(), List.of(), List.of(), "");
        }
    }

    /**
     * Fail-closed simulation-only runtime binding.
     *
     * @param kind fixed SIMULATION_ONLY kind
     * @param resolverPolicyRef exact Fixture resolver policy
     * @param realExternalCallsAllowed must remain false
     * @param externalCredentialsAllowed must remain false
     * @param networkEgressAllowed must remain false
     */
    public record SimulationRuntimeBinding(
            Kind kind,
            MirrorArtifactRef resolverPolicyRef,
            boolean realExternalCallsAllowed,
            boolean externalCredentialsAllowed,
            boolean networkEgressAllowed
    ) {
        /** Only SIMULATION_ONLY, fully isolated bindings are valid. */
        public SimulationRuntimeBinding {
            kind = kind == null ? Kind.SIMULATION_ONLY : kind;
            resolverPolicyRef = BusinessMirrorProtocolSupport.exactRef(
                    resolverPolicyRef, "FIXTURE_RESOLVER_POLICY", "resolverPolicyRef");
            if (kind != Kind.SIMULATION_ONLY
                    || realExternalCallsAllowed
                    || externalCredentialsAllowed
                    || networkEgressAllowed) {
                throw new IllegalArgumentException(
                        "Proposal runtime must be SIMULATION_ONLY without external calls, credentials, or egress");
            }
        }

        /** Fixed first-generation Proposal runtime kind. */
        public enum Kind {
            SIMULATION_ONLY
        }
    }

    /** Normalizes collections and enforces readiness at lifecycle transitions. */
    public CapabilityProposalDraft {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        proposalId = BusinessMirrorProtocolSupport.identifier(proposalId, "proposalId");
        if (revision < 0) {
            throw new IllegalArgumentException("proposal draft revision must not be negative");
        }
        scope = java.util.Objects.requireNonNull(scope, "scope");
        businessIntent = businessIntent == null ? BusinessIntent.empty() : businessIntent;
        fixturePackRefs = BusinessMirrorProtocolSupport.exactRefs(
                fixturePackRefs, Set.of("FIXTURE_BUNDLE"), "fixturePackRefs");
        businessAcceptanceSuiteRefs = BusinessMirrorProtocolSupport.exactRefs(
                businessAcceptanceSuiteRefs,
                Set.of("TEST_SUITE", "SCENARIO_PACK"),
                "businessAcceptanceSuiteRefs");
        assumptions = BusinessMirrorProtocolSupport.normalizedList(assumptions, "assumptions");
        limitations = BusinessMirrorProtocolSupport.normalizedList(limitations, "limitations");
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? Lifecycle.DRAFT : lifecycle;
        if (!scope.tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException("Proposal provenance tenant must match Proposal scope");
        }
        if (expiresAt != null && provenance.approvedAt() != null
                && expiresAt.isBefore(provenance.approvedAt())) {
            throw new IllegalArgumentException("Proposal expiry must not precede approval");
        }
        List<String> blockers = readinessBlockers(businessIntent, candidateContract,
                fixturePackRefs, businessAcceptanceSuiteRefs, simulationRuntimeBinding, expiresAt);
        if (lifecycle != Lifecycle.DRAFT && lifecycle != Lifecycle.SUPERSEDED && !blockers.isEmpty()) {
            throw new IllegalArgumentException("Proposal is not ready: " + String.join(",", blockers));
        }
        if (lifecycle == Lifecycle.SUBMITTED
                && (revision < 1 || provenance.approvedBy().isBlank() || provenance.approvedAt() == null)) {
            throw new IllegalArgumentException("submitted Proposal requires persisted revision and owner approval");
        }
    }

    /** @return deterministic authoring blocker codes */
    public List<String> readinessBlockers() {
        return readinessBlockers(businessIntent, candidateContract, fixturePackRefs,
                businessAcceptanceSuiteRefs, simulationRuntimeBinding, expiresAt);
    }

    private static List<String> readinessBlockers(
            BusinessIntent businessIntent,
            CapabilityContract candidateContract,
            List<MirrorArtifactRef> fixturePackRefs,
            List<MirrorArtifactRef> businessAcceptanceSuiteRefs,
            SimulationRuntimeBinding simulationRuntimeBinding,
            Instant expiresAt) {
        List<String> blockers = new ArrayList<>();
        if (businessIntent.capabilityGap().isBlank()) {
            blockers.add("CAPABILITY_GAP_MISSING");
        }
        if (businessIntent.expectedValue().isBlank()) {
            blockers.add("EXPECTED_VALUE_MISSING");
        }
        if (businessIntent.applicableScenarioRefs().isEmpty()) {
            blockers.add("APPLICABLE_SCENARIO_MISSING");
        }
        if (businessIntent.candidatePackageRefs().isEmpty()) {
            blockers.add("CANDIDATE_PACKAGE_MISSING");
        }
        if (businessIntent.candidateGraphRefs().isEmpty()) {
            blockers.add("CANDIDATE_GRAPH_MISSING");
        }
        if (businessIntent.owner().isBlank()) {
            blockers.add("PROPOSAL_OWNER_MISSING");
        }
        if (candidateContract == null) {
            blockers.add("CANDIDATE_CONTRACT_MISSING");
        } else {
            if (candidateContract.effect().mode() == EffectContract.Mode.UNKNOWN) {
                blockers.add("CANDIDATE_EFFECT_UNRESOLVED");
            }
            if (candidateContract.idempotency().mode()
                    == CapabilityContract.IdempotencyMode.UNKNOWN) {
                blockers.add("CANDIDATE_IDEMPOTENCY_UNRESOLVED");
            }
            if (candidateContract.slo().timeout() == null) {
                blockers.add("CANDIDATE_TIMEOUT_UNRESOLVED");
            }
            if (candidateContract.security().allowedRegions().isEmpty()) {
                blockers.add("CANDIDATE_REGION_UNRESOLVED");
            }
        }
        if (fixturePackRefs.isEmpty()) {
            blockers.add("FIXTURE_PACK_MISSING");
        }
        if (businessAcceptanceSuiteRefs.isEmpty()) {
            blockers.add("BUSINESS_ACCEPTANCE_SUITE_MISSING");
        }
        if (simulationRuntimeBinding == null) {
            blockers.add("SIMULATION_BINDING_MISSING");
        }
        if (expiresAt == null) {
            blockers.add("PROPOSAL_EXPIRY_MISSING");
        }
        return List.copyOf(blockers);
    }
}
