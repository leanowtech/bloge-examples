package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

final class BusinessMirrorDomainFixtures {
    static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");
    static final Instant CREATED_AT = Instant.parse("2026-08-14T02:00:00Z");
    static final Instant EXPIRES_AT = Instant.parse("2026-11-14T02:00:00Z");
    static final ObjectMapper MAPPER = JsonMapper.builder().findAndAddModules().build();

    private BusinessMirrorDomainFixtures() {
    }

    static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    static MirrorArtifactRef ref(String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(fingerprint));
    }

    static ArtifactProvenance provenance(boolean approved) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), "customer-service-business-mirror", null, null, null, null,
                List.of(), approved ? "service-owner" : "",
                approved ? CREATED_AT.minus(Duration.ofHours(1)) : null,
                approved ? EXPIRES_AT : null, "");
    }

    static BusinessAssetRef asset(BusinessAssetRef.Layer layer,
                                  BusinessAssetRef.Kind kind,
                                  String id,
                                  char fingerprint) {
        return new BusinessAssetRef(layer, kind, id, 1, fingerprint(fingerprint),
                "customer-service-registry", SCOPE);
    }

    static DomainCapabilityPackageDraft.BusinessDefinition businessDefinition(
            DomainCapabilityPackageDraft.RiskClass riskClass) {
        return new DomainCapabilityPackageDraft.BusinessDefinition(
                "ride-cancellation", ref("PROBLEM_TAXONOMY", "trip-problems", 'a'),
                "TRIP.CANCELLATION.FEE", "Resolve disputed cancellation fees",
                "Return an explainable and policy-compliant resolution", riskClass,
                "cancellation-service-owner", List.of("risk-owner", "support-operations"));
    }

    static DomainCapabilityPackageDraft packageDraft(
            DomainCapabilityPackageDraft.Lifecycle lifecycle,
            DomainCapabilityPackageDraft.RiskClass riskClass,
            ArtifactProvenance provenance) {
        return new DomainCapabilityPackageDraft("", "cancellation-fee-resolution", 1, SCOPE,
                businessDefinition(riskClass), ref("CONTRACT", "cancellation-fee-package", 'b'),
                List.of(ref("CAPABILITY", "trip-query", 'c')),
                List.of(ref("GRAPH_DRAFT", "cancellation-fee-resolution", 'd')),
                List.of(), List.of(ref("STATE_MODEL", "trip-lifecycle", 'e')),
                List.of(ref("EFFECT_CONTRACT", "refund-effect", 'f')),
                ref("SCENARIO_INVENTORY", "cancellation-scenarios", '1'),
                List.of(ref("SCENARIO_PACK", "cancellation-core", '2')),
                List.of(asset(BusinessAssetRef.Layer.L1_SERVICE_DESIGN,
                        BusinessAssetRef.Kind.SOLUTION, "cancellation-fee-solution", '3')),
                List.of(asset(BusinessAssetRef.Layer.L2_SERVICE_CARRIER,
                        BusinessAssetRef.Kind.WORKFLOW, "cancellation-fee-workflow", '4')),
                List.of(asset(BusinessAssetRef.Layer.L3_APPLICATION,
                        BusinessAssetRef.Kind.CHANNEL_APPLICATION, "support-console", '5')),
                ref("DOMAIN_FIDELITY_INVENTORY", "cancellation-fidelity", '6'),
                List.of(ref("OUTCOME_DEFINITION", "fair-resolution", '7')),
                List.of("Cash refund remains virtual during rehearsal"),
                List.of("Trip facts are supplied by certified fixtures"), EXPIRES_AT,
                provenance, lifecycle);
    }

    static CapabilityContract candidateContract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), EffectContract.readOnly(List.of("trip/*")),
                CapabilityContract.Determinism.DETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of("sg"), false),
                new CapabilityContract.SloContract(Duration.ofSeconds(2), 0.999d, 500L,
                        "trip-platform-owner"));
    }

    static CapabilityProposalDraft.BusinessIntent businessIntent() {
        return new CapabilityProposalDraft.BusinessIntent(
                "Customer service cannot query cancellation fee attribution without Trip Platform",
                "Enable independent, high-fidelity cancellation dispute rehearsal",
                List.of(ref("SCENARIO_CASE", "driver-arrived-rider-cancelled", '8')),
                List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-fee-resolution", '9')),
                List.of(ref("GRAPH_DRAFT", "cancellation-fee-resolution", 'a')),
                "cancellation-service-owner");
    }

    static CapabilityProposalDraft.SimulationRuntimeBinding simulationBinding() {
        return new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                ref("FIXTURE_RESOLVER_POLICY", "cancellation-fixture-policy", 'b'),
                false, false, false);
    }

    static CapabilityProposalDraft proposalDraft(CapabilityProposalDraft.Lifecycle lifecycle,
                                                  ArtifactProvenance provenance) {
        return new CapabilityProposalDraft("", "trip-cancellation-attribution-query", 1, SCOPE,
                businessIntent(), candidateContract(),
                List.of(ref("FIXTURE_BUNDLE", "trip-cancellation-fixtures", 'c')),
                List.of(ref("SCENARIO_PACK", "cancellation-acceptance", 'd')),
                simulationBinding(), List.of("Fixture clock is deterministic"),
                List.of("No real Trip Platform request is made"), EXPIRES_AT,
                provenance, lifecycle);
    }
}
