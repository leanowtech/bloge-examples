package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

final class BusinessMirrorAuthoringFixtures {
    static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "ride-hailing", "customer-service", "cancellation", "test", "sg");

    private BusinessMirrorAuthoringFixtures() {
    }

    static DomainCapabilityPackageDraft draft(String packageId, long revision, String assumption) {
        return draft(SCOPE, packageId, revision, assumption);
    }

    static DomainCapabilityPackageDraft draft(
            CapabilitySnapshot.Scope scope, String packageId, long revision, String assumption) {
        return new DomainCapabilityPackageDraft("", packageId, revision, scope,
                new DomainCapabilityPackageDraft.BusinessDefinition(
                        "ride-cancellation", null, "", "", "",
                        DomainCapabilityPackageDraft.RiskClass.HIGH,
                        "cancellation-owner", List.of()),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), null,
                List.of(), List.of(), List.of(), List.of(), null, List.of(), List.of(),
                List.of(assumption), null, provenance(scope),
                DomainCapabilityPackageDraft.Lifecycle.DRAFT);
    }

    static IntegrationRequestContext identity() {
        return identity(SCOPE, "alice", "correlation-1");
    }

    static IntegrationRequestContext identity(
            CapabilitySnapshot.Scope scope, String actor, String correlation) {
        return new IntegrationRequestContext(scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), "WORKLOAD", actor, "",
                "BUSINESS_MIRROR_AUTHORING", correlation, Set.of("business-mirror-authors"),
                "CONFIDENTIAL", "");
    }

    static CapabilityProposalDraft proposal(
            String proposalId, long revision, String expectedValue) {
        return proposal(SCOPE, proposalId, revision, expectedValue);
    }

    static CapabilityProposalDraft proposal(
            CapabilitySnapshot.Scope scope,
            String proposalId,
            long revision,
            String expectedValue) {
        return new CapabilityProposalDraft("", proposalId, revision, scope,
                new CapabilityProposalDraft.BusinessIntent(
                        "Cancellation attribution capability is unavailable",
                        expectedValue,
                        List.of(ref("SCENARIO_CASE", "driver-arrived-rider-cancelled", '1')),
                        List.of(ref("DOMAIN_CAPABILITY_PACKAGE", "cancellation-fee", '2')),
                        List.of(ref("GRAPH_DRAFT", "cancellation-fee", '3')),
                        "cancellation-owner"),
                new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                        List.of(), EffectContract.readOnly(List.of("trip/*")),
                        CapabilityContract.Determinism.DETERMINISTIC,
                        new CapabilityContract.IdempotencyContract(
                                CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                        null, CapabilityContract.CompatibilityPolicy.conservative(),
                        new CapabilityContract.SecurityContract(
                                CapabilityContract.DataClassification.CONFIDENTIAL, false,
                                List.of(scope.region()), false),
                        new CapabilityContract.SloContract(
                                Duration.ofSeconds(2), 0.999d, 500L, "trip-platform-owner")),
                List.of(ref("FIXTURE_BUNDLE", "cancellation-fixtures", '4')),
                List.of(ref("SCENARIO_PACK", "cancellation-acceptance", '5')),
                new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                        ref("FIXTURE_RESOLVER_POLICY", "cancellation-policy", '6'),
                        false, false, false),
                List.of("Fixture clock is frozen"),
                List.of("No Trip Platform request is made"),
                Instant.parse("2026-11-14T02:00:00Z"), provenance(scope),
                CapabilityProposalDraft.Lifecycle.DRAFT);
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1,
                "sha256:" + String.valueOf(value).repeat(64));
    }

    private static ArtifactProvenance provenance(CapabilitySnapshot.Scope scope) {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                scope.tenantId(), "business-mirror-authoring-test", null, null, null, null,
                List.of(), "", null, null, "");
    }
}
