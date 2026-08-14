package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Mutable business-authoring root for one customer problem and its complete executable projections.
 *
 * <p>The draft owns business semantics and exact references. It never copies Graph, Fixture,
 * payload, or evidence content, and it never carries ANEKE's authoritative governance state.</p>
 *
 * @param schemaVersion package-draft protocol version
 * @param packageId stable package identity inside its enterprise scope
 * @param revision optimistic authoring revision; zero is an unsaved local draft
 * @param scope complete enterprise namespace
 * @param businessDefinition customer problem, risk, outcome, and ownership intent
 * @param packageContractRef exact package-level Contract, when authored
 * @param capabilityRefs exact frozen capabilities used by the package
 * @param graphRefs exact GraphDraft revisions used as executable projections
 * @param proposalRefs exact capability proposals used by candidate paths
 * @param stateModelRefs exact state models
 * @param effectModelRefs exact effect/write specifications
 * @param scenarioInventoryRef exact owner-governed scenario denominator
 * @param scenarioPackRefs exact executable Scenario packs
 * @param solutionRefs L1 solution assets
 * @param carrierRefs L2 SOP, Workflow, and Agent assets
 * @param channelRefs L3 application/channel assets
 * @param fidelityInventoryRef exact fidelity denominator
 * @param outcomeDefinitionRefs exact independent business-outcome definitions
 * @param limitations explicit known limitations
 * @param assumptions explicit business or implementation assumptions
 * @param expiresAt optional authoring review horizon
 * @param provenance authoring source and approval lineage
 * @param lifecycle authoring lifecycle owned by Resource Gateway
 */
public record DomainCapabilityPackageDraft(
        String schemaVersion,
        String packageId,
        long revision,
        CapabilitySnapshot.Scope scope,
        BusinessDefinition businessDefinition,
        MirrorArtifactRef packageContractRef,
        List<MirrorArtifactRef> capabilityRefs,
        List<MirrorArtifactRef> graphRefs,
        List<MirrorArtifactRef> proposalRefs,
        List<MirrorArtifactRef> stateModelRefs,
        List<MirrorArtifactRef> effectModelRefs,
        MirrorArtifactRef scenarioInventoryRef,
        List<MirrorArtifactRef> scenarioPackRefs,
        List<BusinessAssetRef> solutionRefs,
        List<BusinessAssetRef> carrierRefs,
        List<BusinessAssetRef> channelRefs,
        MirrorArtifactRef fidelityInventoryRef,
        List<MirrorArtifactRef> outcomeDefinitionRefs,
        List<String> limitations,
        List<String> assumptions,
        Instant expiresAt,
        ArtifactProvenance provenance,
        Lifecycle lifecycle
) {
    /** Current mutable Package authoring protocol. */
    public static final String SCHEMA_VERSION = "bloge.domainCapabilityPackageDraft.v1";

    /** Authoring states that do not duplicate ANEKE governance lifecycle. */
    public enum Lifecycle {
        DRAFT,
        READY_FOR_REVIEW,
        SUBMITTED,
        SUPERSEDED
    }

    /** Risk class of the customer problem represented by the package. */
    public enum RiskClass {
        LOW,
        MEDIUM,
        HIGH,
        CRITICAL
    }

    /**
     * Business-owned definition above executable Graph topology.
     *
     * @param domainId stable customer-business domain
     * @param problemTaxonomyRef exact problem taxonomy
     * @param problemCode stable taxonomy-local problem code
     * @param businessGoal expected service capability
     * @param expectedOutcome business-readable outcome intent
     * @param riskClass business risk
     * @param accountableOwner accountable owner
     * @param collaboratingOwners sorted supporting owners
     */
    public record BusinessDefinition(
            String domainId,
            MirrorArtifactRef problemTaxonomyRef,
            String problemCode,
            String businessGoal,
            String expectedOutcome,
            RiskClass riskClass,
            String accountableOwner,
            List<String> collaboratingOwners
    ) {
        /** Allows incomplete local DRAFT values while preserving deterministic collections. */
        public BusinessDefinition {
            domainId = BusinessMirrorProtocolSupport.normalized(domainId);
            if (!domainId.isEmpty()) {
                domainId = BusinessMirrorProtocolSupport.identifier(domainId, "domainId");
            }
            problemTaxonomyRef = BusinessMirrorProtocolSupport.optionalRef(
                    problemTaxonomyRef, "PROBLEM_TAXONOMY", "problemTaxonomyRef");
            problemCode = BusinessMirrorProtocolSupport.normalized(problemCode);
            businessGoal = BusinessMirrorProtocolSupport.normalized(businessGoal);
            expectedOutcome = BusinessMirrorProtocolSupport.normalized(expectedOutcome);
            riskClass = riskClass == null ? RiskClass.CRITICAL : riskClass;
            accountableOwner = BusinessMirrorProtocolSupport.normalized(accountableOwner);
            collaboratingOwners = BusinessMirrorProtocolSupport.normalizedList(
                    collaboratingOwners, "collaboratingOwners");
        }

        /** @return intentionally incomplete definition for a new local draft */
        public static BusinessDefinition empty() {
            return new BusinessDefinition("", null, "", "", "", RiskClass.CRITICAL, "", List.of());
        }
    }

    /** Normalizes exact references and enforces readiness without making DRAFT creation impossible. */
    public DomainCapabilityPackageDraft {
        schemaVersion = BusinessMirrorProtocolSupport.version(schemaVersion, SCHEMA_VERSION);
        packageId = BusinessMirrorProtocolSupport.identifier(packageId, "packageId");
        if (revision < 0) {
            throw new IllegalArgumentException("package draft revision must not be negative");
        }
        scope = java.util.Objects.requireNonNull(scope, "scope");
        businessDefinition = businessDefinition == null ? BusinessDefinition.empty() : businessDefinition;
        packageContractRef = BusinessMirrorProtocolSupport.optionalRef(
                packageContractRef, "CONTRACT", "packageContractRef");
        capabilityRefs = BusinessMirrorProtocolSupport.exactRefs(
                capabilityRefs, Set.of("CAPABILITY"), "capabilityRefs");
        graphRefs = BusinessMirrorProtocolSupport.exactRefs(
                graphRefs, Set.of("GRAPH_DRAFT"), "graphRefs");
        proposalRefs = BusinessMirrorProtocolSupport.exactRefs(
                proposalRefs, Set.of("CAPABILITY_PROPOSAL"), "proposalRefs");
        stateModelRefs = BusinessMirrorProtocolSupport.exactRefs(
                stateModelRefs, Set.of("STATE_MODEL"), "stateModelRefs");
        effectModelRefs = BusinessMirrorProtocolSupport.exactRefs(
                effectModelRefs, Set.of("EFFECT_CONTRACT", "WRITE_EFFECT"), "effectModelRefs");
        scenarioInventoryRef = BusinessMirrorProtocolSupport.optionalRef(
                scenarioInventoryRef, "SCENARIO_INVENTORY", "scenarioInventoryRef");
        scenarioPackRefs = BusinessMirrorProtocolSupport.exactRefs(
                scenarioPackRefs, Set.of("SCENARIO_PACK"), "scenarioPackRefs");
        solutionRefs = businessRefs(scope, solutionRefs, BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                Set.of(BusinessAssetRef.Kind.SOLUTION), "solutionRefs");
        carrierRefs = businessRefs(scope, carrierRefs, BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                Set.of(BusinessAssetRef.Kind.SOP, BusinessAssetRef.Kind.AGENT,
                        BusinessAssetRef.Kind.WORKFLOW), "carrierRefs");
        channelRefs = businessRefs(scope, channelRefs, BusinessAssetRef.Layer.L3_APPLICATION,
                Set.of(BusinessAssetRef.Kind.CHANNEL_APPLICATION), "channelRefs");
        fidelityInventoryRef = BusinessMirrorProtocolSupport.optionalRef(
                fidelityInventoryRef, "DOMAIN_FIDELITY_INVENTORY", "fidelityInventoryRef");
        outcomeDefinitionRefs = BusinessMirrorProtocolSupport.exactRefs(
                outcomeDefinitionRefs, Set.of("OUTCOME_DEFINITION"), "outcomeDefinitionRefs");
        limitations = BusinessMirrorProtocolSupport.normalizedList(limitations, "limitations");
        assumptions = BusinessMirrorProtocolSupport.normalizedList(assumptions, "assumptions");
        provenance = java.util.Objects.requireNonNull(provenance, "provenance");
        lifecycle = lifecycle == null ? Lifecycle.DRAFT : lifecycle;

        if (!scope.tenantId().equals(provenance.tenantId())) {
            throw new IllegalArgumentException("package provenance tenant must match package scope");
        }
        if (expiresAt != null && provenance.approvedAt() != null
                && expiresAt.isBefore(provenance.approvedAt())) {
            throw new IllegalArgumentException("package expiry must not precede approval");
        }
        List<String> blockers = readinessBlockers(businessDefinition, packageContractRef,
                capabilityRefs, graphRefs, stateModelRefs, effectModelRefs, scenarioInventoryRef,
                scenarioPackRefs, solutionRefs, carrierRefs, channelRefs, fidelityInventoryRef,
                outcomeDefinitionRefs);
        if (lifecycle != Lifecycle.DRAFT && lifecycle != Lifecycle.SUPERSEDED && !blockers.isEmpty()) {
            throw new IllegalArgumentException("package is not ready: " + String.join(",", blockers));
        }
        if (lifecycle == Lifecycle.SUBMITTED
                && (revision < 1 || provenance.approvedBy().isBlank() || provenance.approvedAt() == null)) {
            throw new IllegalArgumentException("submitted package requires persisted revision and owner approval");
        }
    }

    /**
     * Returns stable blocker codes used by readiness compilation and authoring UX.
     *
     * @return deterministic blocker codes without business payload values
     */
    public List<String> readinessBlockers() {
        return readinessBlockers(businessDefinition, packageContractRef, capabilityRefs, graphRefs,
                stateModelRefs, effectModelRefs, scenarioInventoryRef, scenarioPackRefs,
                solutionRefs, carrierRefs, channelRefs, fidelityInventoryRef, outcomeDefinitionRefs);
    }

    /** Returns this authoring value with a repository-assigned optimistic revision. */
    public DomainCapabilityPackageDraft withRevision(long value) {
        return new DomainCapabilityPackageDraft(schemaVersion, packageId, value, scope,
                businessDefinition, packageContractRef, capabilityRefs, graphRefs, proposalRefs,
                stateModelRefs, effectModelRefs, scenarioInventoryRef, scenarioPackRefs,
                solutionRefs, carrierRefs, channelRefs, fidelityInventoryRef,
                outcomeDefinitionRefs, limitations, assumptions, expiresAt, provenance, lifecycle);
    }

    private static List<String> readinessBlockers(
            BusinessDefinition businessDefinition,
            MirrorArtifactRef packageContractRef,
            List<MirrorArtifactRef> capabilityRefs,
            List<MirrorArtifactRef> graphRefs,
            List<MirrorArtifactRef> stateModelRefs,
            List<MirrorArtifactRef> effectModelRefs,
            MirrorArtifactRef scenarioInventoryRef,
            List<MirrorArtifactRef> scenarioPackRefs,
            List<BusinessAssetRef> solutionRefs,
            List<BusinessAssetRef> carrierRefs,
            List<BusinessAssetRef> channelRefs,
            MirrorArtifactRef fidelityInventoryRef,
            List<MirrorArtifactRef> outcomeDefinitionRefs) {
        List<String> blockers = new ArrayList<>();
        if (businessDefinition.domainId().isBlank()) {
            blockers.add("BUSINESS_DOMAIN_MISSING");
        }
        if (businessDefinition.problemTaxonomyRef() == null) {
            blockers.add("PROBLEM_TAXONOMY_MISSING");
        }
        if (businessDefinition.problemCode().isBlank()) {
            blockers.add("PROBLEM_CODE_MISSING");
        }
        if (businessDefinition.businessGoal().isBlank()) {
            blockers.add("BUSINESS_GOAL_MISSING");
        }
        if (businessDefinition.expectedOutcome().isBlank()) {
            blockers.add("EXPECTED_OUTCOME_MISSING");
        }
        if (businessDefinition.accountableOwner().isBlank()) {
            blockers.add("ACCOUNTABLE_OWNER_MISSING");
        }
        if (packageContractRef == null) {
            blockers.add("PACKAGE_CONTRACT_MISSING");
        }
        if (capabilityRefs.isEmpty() && graphRefs.isEmpty()) {
            blockers.add("EXECUTABLE_PROJECTION_MISSING");
        }
        if (scenarioInventoryRef == null) {
            blockers.add("SCENARIO_INVENTORY_MISSING");
        }
        if (scenarioPackRefs.isEmpty()) {
            blockers.add("SCENARIO_PACK_MISSING");
        }
        if (solutionRefs.isEmpty()) {
            blockers.add("SOLUTION_BINDING_MISSING");
        }
        if (carrierRefs.isEmpty()) {
            blockers.add("SERVICE_CARRIER_BINDING_MISSING");
        }
        if (channelRefs.isEmpty()) {
            blockers.add("CHANNEL_BINDING_MISSING");
        }
        if (fidelityInventoryRef == null) {
            blockers.add("FIDELITY_INVENTORY_MISSING");
        }
        if (outcomeDefinitionRefs.isEmpty()) {
            blockers.add("OUTCOME_DEFINITION_MISSING");
        }
        if ((businessDefinition.riskClass() == RiskClass.HIGH
                || businessDefinition.riskClass() == RiskClass.CRITICAL)
                && stateModelRefs.isEmpty()) {
            blockers.add("HIGH_RISK_STATE_MODEL_MISSING");
        }
        if ((businessDefinition.riskClass() == RiskClass.HIGH
                || businessDefinition.riskClass() == RiskClass.CRITICAL)
                && effectModelRefs.isEmpty()) {
            blockers.add("HIGH_RISK_EFFECT_MODEL_MISSING");
        }
        return List.copyOf(blockers);
    }

    private static List<BusinessAssetRef> businessRefs(
            CapabilitySnapshot.Scope scope,
            List<BusinessAssetRef> values,
            BusinessAssetRef.Layer layer,
            Set<BusinessAssetRef.Kind> kinds,
            String field) {
        List<BusinessAssetRef> exact = BusinessMirrorProtocolSupport.sortedUnique(
                values,
                Comparator.comparing(BusinessAssetRef::id)
                        .thenComparingLong(BusinessAssetRef::revision)
                        .thenComparing(BusinessAssetRef::fingerprint),
                value -> value.kind() + ":" + value.id() + ":" + value.revision(),
                field);
        for (BusinessAssetRef ref : exact) {
            if (ref.layer() != layer || !kinds.contains(ref.kind())) {
                throw new IllegalArgumentException(field + " contains an incompatible business asset");
            }
            if (!BusinessMirrorProtocolSupport.sameScope(scope, ref.scope())) {
                throw new IllegalArgumentException(field + " must not cross enterprise scope");
            }
        }
        return exact;
    }
}
