package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilitySnapshotLifecycleTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void promotesDraftThroughReviewedToActiveAsSealedRevisions() {
        CapabilitySnapshot draft = draft();

        CapabilitySnapshot reviewed = CapabilitySnapshotLifecycle.transition(mapper, draft,
                CapabilitySnapshot.Lifecycle.REVIEWED, 2, "reviewer-a",
                Instant.parse("2026-07-22T01:00:00Z"), Instant.parse("2026-08-22T00:00:00Z"),
                "", Instant.parse("2026-07-22T01:00:01Z"));
        CapabilitySnapshot active = CapabilitySnapshotLifecycle.transition(mapper, reviewed,
                CapabilitySnapshot.Lifecycle.ACTIVE, 3, "owner-a",
                Instant.parse("2026-07-22T02:00:00Z"), Instant.parse("2026-08-22T00:00:00Z"),
                "", Instant.parse("2026-07-22T02:00:01Z"));

        assertThat(reviewed.lifecycle()).isEqualTo(CapabilitySnapshot.Lifecycle.REVIEWED);
        assertThat(reviewed.provenance().approvedBy()).isEqualTo("reviewer-a");
        assertThat(active.lifecycle()).isEqualTo(CapabilitySnapshot.Lifecycle.ACTIVE);
        assertThat(active.provenance().approvedBy()).isEqualTo("owner-a");
        assertThat(active.fingerprint()).isNotEqualTo(reviewed.fingerprint());
        CapabilitySnapshotIntegrity.verify(mapper, active);
    }

    @Test
    void blocksActivePromotionWhenRuntimeOrEffectIsUnresolved() {
        CapabilitySnapshot draft = draft().withFingerprint("");
        CapabilitySnapshot blocked = new CapabilitySnapshot(draft.schemaVersion(), draft.capabilityId(),
                draft.revision(), "", draft.kind(), draft.scope(), draft.source(),
                new CapabilityContract("", draft.contract().inputSchema(), draft.contract().outputSchema(),
                        draft.contract().errorModel(), EffectContract.unknown("effect declaration missing"),
                        draft.contract().determinism(), draft.contract().idempotency(), null,
                        draft.contract().compatibility(), draft.contract().security(), draft.contract().slo()),
                CapabilitySnapshot.RuntimeBinding.unavailable("effect contract is unresolved"),
                draft.dependencies(), draft.ownership(), draft.lifecycle(), draft.provenance(), draft.createdAt());
        blocked = CapabilitySnapshotIntegrity.seal(mapper, blocked);
        CapabilitySnapshot reviewed = CapabilitySnapshotLifecycle.transition(mapper, blocked,
                CapabilitySnapshot.Lifecycle.REVIEWED, 2, "reviewer-a",
                Instant.parse("2026-07-22T01:00:00Z"), null, "",
                Instant.parse("2026-07-22T01:00:01Z"));

        CapabilitySnapshot finalReviewed = reviewed;
        assertThatThrownBy(() -> CapabilitySnapshotLifecycle.transition(mapper, finalReviewed,
                CapabilitySnapshot.Lifecycle.ACTIVE, 3, "owner-a",
                Instant.parse("2026-07-22T02:00:00Z"), null, "",
                Instant.parse("2026-07-22T02:00:01Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ACTIVE snapshot requires ready runtime and resolved effect");
    }

    @Test
    void rejectsBehaviorDriftDuringReviewPromotion() {
        CapabilitySnapshot draft = draft();
        CapabilitySnapshot drifted = new CapabilitySnapshot(draft.schemaVersion(), draft.capabilityId(), 2,
                "", draft.kind(), draft.scope(), draft.source(), draft.contract(), draft.runtime(),
                draft.dependencies(), new CapabilitySnapshot.Ownership("another-owner", "team-a", "pager-a"),
                CapabilitySnapshot.Lifecycle.REVIEWED,
                draft.provenance().withApproval("reviewer-a", Instant.parse("2026-07-22T01:00:00Z"), null),
                Instant.parse("2026-07-22T01:00:01Z"));

        assertThatThrownBy(() -> CapabilitySnapshotLifecycle.validateAppend(draft, drifted))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("non-DRAFT lifecycle revision must preserve governed capability material");
    }

    @Test
    void revocationIsTerminalAndMakesRuntimeUnavailable() {
        CapabilitySnapshot draft = draft();
        CapabilitySnapshot revoked = CapabilitySnapshotLifecycle.transition(mapper, draft,
                CapabilitySnapshot.Lifecycle.REVOKED, 2, "", null, null,
                "aneKe:revocation:42", Instant.parse("2026-07-22T01:00:00Z"));

        assertThat(revoked.runtime().ready()).isFalse();
        assertThat(revoked.provenance().revocationRef()).isEqualTo("aneKe:revocation:42");
        assertThat(CapabilitySnapshotLifecycle.allowed(CapabilitySnapshot.Lifecycle.REVOKED,
                CapabilitySnapshot.Lifecycle.DRAFT)).isFalse();
        assertThatThrownBy(() -> CapabilitySnapshotLifecycle.transition(mapper, revoked,
                CapabilitySnapshot.Lifecycle.DRAFT, 3, "", null, null, "",
                Instant.parse("2026-07-22T02:00:00Z")))
                .hasMessage("lifecycle transition is not allowed: REVOKED -> DRAFT");
    }

    private CapabilitySnapshot draft() {
        String sourceFingerprint = "sha256:" + "a".repeat(64);
        CapabilityContract contract = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(), EffectContract.readOnly(List.of("resource:orders")),
                CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false, List.of("sg"), false),
                CapabilityContract.SloContract.unspecified());
        ArtifactProvenance provenance = new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER,
                List.of(), "tenant-a", "MIRROR_REHEARSAL", null, null, null, null,
                List.of(), "", null, null, "");
        CapabilitySnapshot snapshot = new CapabilitySnapshot("", "resource:orders.get", 1, "",
                CapabilitySnapshot.Kind.EXTERNAL,
                new CapabilitySnapshot.Scope("tenant-a", "org-a", "support", "test", "sg"),
                new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.RESOURCE,
                        "orders.get", sourceFingerprint), contract,
                new CapabilitySnapshot.RuntimeBinding("HTTP_RESOURCE", "orders.get@1",
                        sourceFingerprint, true, List.of()), List.of(),
                new CapabilitySnapshot.Ownership("owner-a", "team-a", "pager-a"),
                CapabilitySnapshot.Lifecycle.DRAFT, provenance,
                Instant.parse("2026-07-22T00:00:00Z"));
        return CapabilitySnapshotIntegrity.seal(mapper, snapshot);
    }
}
