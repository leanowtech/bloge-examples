package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityProtocolTest {
    private static final String SOURCE_FINGERPRINT = "sha256:" + "a".repeat(64);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void sealsAndVerifiesTheCompleteNormalizedSnapshot() {
        CapabilitySnapshot unsealed = externalSnapshot("", CapabilitySnapshot.Lifecycle.ACTIVE,
                new CapabilitySnapshot.Ownership(" owner-a ", " team-a ", " pager-a "));

        CapabilitySnapshot first = CapabilitySnapshotIntegrity.seal(mapper, unsealed);
        CapabilitySnapshot second = CapabilitySnapshotIntegrity.seal(mapper, unsealed);

        assertThat(first.fingerprint()).startsWith("sha256:").isEqualTo(second.fingerprint());
        assertThat(first.ownership()).isEqualTo(
                new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a"));
        CapabilitySnapshotIntegrity.verify(mapper, first);
    }

    @Test
    void detectsOwnershipContractAndSourceTampering() {
        CapabilitySnapshot sealed = CapabilitySnapshotIntegrity.seal(mapper,
                externalSnapshot("", CapabilitySnapshot.Lifecycle.REVIEWED,
                        new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a")));
        CapabilitySnapshot tampered = new CapabilitySnapshot(
                sealed.schemaVersion(), sealed.capabilityId(), sealed.revision(), sealed.fingerprint(),
                sealed.kind(), sealed.source(), sealed.contract(), sealed.runtime(), sealed.dependencies(),
                new CapabilitySnapshot.Ownership("owner-b", "team-a", "pager-a"), sealed.lifecycle(),
                sealed.provenance(), sealed.createdAt());

        assertThatThrownBy(() -> CapabilitySnapshotIntegrity.verify(mapper, tampered))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("capability snapshot fingerprint mismatch");
    }

    @Test
    void effectContractPreservesUnknownAsCriticalAndRejectsContradictorySets() {
        EffectContract unknown = EffectContract.unknown("operator effect is not declared");

        assertThat(unknown.mode()).isEqualTo(EffectContract.Mode.UNKNOWN);
        assertThat(unknown.riskLevel()).isEqualTo(EffectContract.RiskLevel.CRITICAL);
        assertThat(unknown.requiresApproval()).isTrue();
        assertThatThrownBy(() -> new EffectContract("", EffectContract.Mode.READ_ONLY,
                List.of("resource:orders"), List.of("resource:orders"), List.of(), null,
                false, EffectContract.RiskLevel.LOW, EffectContract.Derivation.DECLARED, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("READ_ONLY effect must not declare writeSet");
        assertThatThrownBy(() -> new EffectContract("", EffectContract.Mode.UNKNOWN,
                List.of(), List.of(), List.of(), null, true, EffectContract.RiskLevel.LOW,
                EffectContract.Derivation.STATIC_ANALYSIS, List.of("missing declaration")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("external, mixed, and unknown effects cannot be LOW risk");
    }

    @Test
    void provenanceRequiresLineageAndConsistentApprovalCoordinates() {
        assertThatThrownBy(() -> new ArtifactProvenance("", ArtifactProvenance.SourceType.RECORDED,
                List.of(), "tenant-a", "MIRROR_REHEARSAL", null, null, 10L,
                new ArtifactProvenance.Confidence(0.8, 0.7, 0.9, "wilson-v1"), List.of(),
                "", null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("recorded and inferred provenance require sourceRefs");
        assertThatThrownBy(() -> new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER,
                List.of(), "tenant-a", "MIRROR_REHEARSAL", null, null, null, null,
                List.of(), "owner-a", null, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("approvedBy and approvedAt must be supplied together");
        assertThatThrownBy(() -> new ArtifactProvenance.Confidence(0.4, 0.5, 0.9, "wilson-v1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lowerBound <= point");
    }

    @Test
    void capabilityKindsEnforceDependencyBoundaries() {
        MirrorArtifactRef child = new MirrorArtifactRef("CAPABILITY", "operator:risk", 1,
                "sha256:" + "b".repeat(64));
        CapabilitySnapshot.Dependency dependency = new CapabilitySnapshot.Dependency(
                "risk", child, true, List.of());

        assertThatThrownBy(() -> new CapabilitySnapshot("", "resource:orders", 1, "",
                CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", SOURCE_FINGERPRINT), contract(),
                CapabilitySnapshot.RuntimeBinding.unavailable("not assembled"), List.of(dependency),
                CapabilitySnapshot.Ownership.unassigned(), CapabilitySnapshot.Lifecycle.DRAFT,
                provenance(), Instant.parse("2026-07-22T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("EXTERNAL capability must not declare dependencies");
        assertThatThrownBy(() -> new CapabilitySnapshot("", "graph:empty", 1, "",
                CapabilitySnapshot.Kind.COMPOSED,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                        "empty", SOURCE_FINGERPRINT), contract(),
                CapabilitySnapshot.RuntimeBinding.unavailable("not assembled"), List.of(),
                CapabilitySnapshot.Ownership.unassigned(), CapabilitySnapshot.Lifecycle.DRAFT,
                provenance(), Instant.parse("2026-07-22T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("COMPOSED capability requires at least one dependency");
    }

    private static CapabilitySnapshot externalSnapshot(String fingerprint,
                                                        CapabilitySnapshot.Lifecycle lifecycle,
                                                        CapabilitySnapshot.Ownership ownership) {
        return new CapabilitySnapshot("", "resource:orders.get", 3, fingerprint,
                CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", SOURCE_FINGERPRINT), contract(),
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "orders.get@3",
                        "sha256:" + "c".repeat(64), true, List.of()), List.of(), ownership,
                lifecycle, provenance(), Instant.parse("2026-07-22T00:00:00Z"));
    }

    private static CapabilityContract contract() {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(new CapabilityContract.ErrorContract("ORDERS.NOT_FOUND",
                        CapabilityContract.ErrorCategory.NOT_FOUND, false, SchemaEnvelope.opaque())),
                EffectContract.readOnly(List.of("resource:orders")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.DETERMINISTIC, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, true,
                        List.of("sg"), true),
                new CapabilityContract.SloContract(Duration.ofSeconds(3), 0.999, 150L, "orders-team"));
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                "tenant-a", "MIRROR_REHEARSAL", null, null, null, null,
                List.of(), "owner-a", Instant.parse("2026-07-22T00:00:00Z"),
                Instant.parse("2027-07-22T00:00:00Z"), "");
    }
}
