package com.leanowtech.bloge.gateway.businessmirror.domain;

import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.CREATED_AT;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.EXPIRES_AT;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.MAPPER;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.SCOPE;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.businessIntent;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.candidateContract;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.fingerprint;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.proposalDraft;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.provenance;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.ref;
import static com.leanowtech.bloge.gateway.businessmirror.domain.BusinessMirrorDomainFixtures.simulationBinding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CapabilityProposalProtocolTest {
    @Test
    void allowsIncompleteDraftButBlocksPromotion() {
        CapabilityProposalDraft draft = new CapabilityProposalDraft("", "new-capability", 0,
                SCOPE, null, null, List.of(), List.of(), null, List.of(), List.of(), null,
                provenance(false), CapabilityProposalDraft.Lifecycle.DRAFT);

        assertThat(draft.readinessBlockers()).contains(
                "CAPABILITY_GAP_MISSING", "CANDIDATE_CONTRACT_MISSING",
                "FIXTURE_PACK_MISSING", "SIMULATION_BINDING_MISSING");
        assertThatThrownBy(() -> new CapabilityProposalDraft("", "new-capability", 0,
                SCOPE, null, null, List.of(), List.of(), null, List.of(), List.of(), null,
                provenance(false), CapabilityProposalDraft.Lifecycle.READY_FOR_REVIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Proposal is not ready");
    }

    @Test
    void rejectsUnsafeSimulationBindings() {
        MirrorArtifactRef resolver = ref("FIXTURE_RESOLVER_POLICY", "fixture-policy", '1');

        assertThatThrownBy(() -> new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                resolver, true, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("without external calls");
        assertThatThrownBy(() -> new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                resolver, false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("credentials");
        assertThatThrownBy(() -> new CapabilityProposalDraft.SimulationRuntimeBinding(null,
                resolver, false, false, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("egress");
    }

    @Test
    void rejectsUnknownBehaviorBeforeReview() {
        CapabilityContract unresolved = new CapabilityContract("", SchemaEnvelope.opaque(),
                SchemaEnvelope.opaque(), List.of(), EffectContract.unknown("not analyzed"),
                CapabilityContract.Determinism.NONDETERMINISTIC,
                CapabilityContract.IdempotencyContract.unknown(), null,
                CapabilityContract.CompatibilityPolicy.conservative(),
                CapabilityContract.SecurityContract.restricted(),
                CapabilityContract.SloContract.unspecified());

        assertThatThrownBy(() -> new CapabilityProposalDraft("", "trip-query", 1, SCOPE,
                businessIntent(), unresolved,
                List.of(ref("FIXTURE_BUNDLE", "fixtures", '2')),
                List.of(ref("TEST_SUITE", "acceptance", '3')), simulationBinding(),
                List.of(), List.of(), EXPIRES_AT, provenance(false),
                CapabilityProposalDraft.Lifecycle.READY_FOR_REVIEW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CANDIDATE_EFFECT_UNRESOLVED")
                .hasMessageContaining("CANDIDATE_IDEMPOTENCY_UNRESOLVED")
                .hasMessageContaining("CANDIDATE_TIMEOUT_UNRESOLVED")
                .hasMessageContaining("CANDIDATE_REGION_UNRESOLVED");
    }

    @Test
    void admitsCompleteProposalAndRequiresApprovalOnSubmission() {
        assertThat(proposalDraft(CapabilityProposalDraft.Lifecycle.READY_FOR_REVIEW,
                provenance(false)).readinessBlockers()).isEmpty();
        assertThatThrownBy(() -> proposalDraft(CapabilityProposalDraft.Lifecycle.SUBMITTED,
                provenance(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner approval");
        assertThat(proposalDraft(CapabilityProposalDraft.Lifecycle.SUBMITTED,
                provenance(true)).lifecycle()).isEqualTo(CapabilityProposalDraft.Lifecycle.SUBMITTED);
    }

    @Test
    void evidenceStateCannotBeClaimedWithoutItsArtifacts() {
        assertThatThrownBy(() -> snapshot(CapabilityProposalSnapshot.EvidenceState.SIMULATED,
                null, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("simulation evidence");
        assertThatThrownBy(() -> snapshot(CapabilityProposalSnapshot.EvidenceState.IMPLEMENTED,
                null, List.of(ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation", '4'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("implementation binding");
        assertThatThrownBy(() -> snapshot(CapabilityProposalSnapshot.EvidenceState.CONFORMANT,
                ref("PROPOSAL_IMPLEMENTATION_BINDING", "trip-platform", '5'),
                List.of(ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation", '6'))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("conformance evidence");
    }

    @Test
    void calibratedSnapshotRequiresIndependentEvidenceAndIsContentAddressed() {
        MirrorArtifactRef binding = ref("PROPOSAL_IMPLEMENTATION_BINDING", "trip-platform", '7');
        List<MirrorArtifactRef> evidence = List.of(
                ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation", '8'),
                ref("IMPLEMENTATION_CONFORMANCE_REPORT", "conformance", '9'),
                ref("AUTHORITATIVE_OUTCOME_OBSERVATION", "production-outcome", 'a'));
        CapabilityProposalSnapshot sealed = snapshot(
                CapabilityProposalSnapshot.EvidenceState.CALIBRATED, binding, evidence).seal(MAPPER);

        sealed.verify(MAPPER);
        assertThat(sealed.artifactRef().kind()).isEqualTo("CAPABILITY_PROPOSAL");

        CapabilityProposalSnapshot tampered = snapshot(
                CapabilityProposalSnapshot.EvidenceState.CONFORMANT, binding,
                List.of(ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation", '8'),
                        ref("IMPLEMENTATION_CONFORMANCE_REPORT", "other", 'b')))
                .withFingerprint(sealed.fingerprint());
        assertThatThrownBy(() -> tampered.verify(MAPPER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fingerprint mismatch");
    }

    @Test
    void proposalExpiryCannotPrecedeApproval() {
        ArtifactProvenance approvedLater = new ArtifactProvenance("",
                ArtifactProvenance.SourceType.OWNER, List.of(), SCOPE.tenantId(), "proposal",
                null, null, null, null, List.of(), "owner", CREATED_AT.plus(Duration.ofDays(10)),
                CREATED_AT.plus(Duration.ofDays(20)), "");

        assertThatThrownBy(() -> new CapabilityProposalDraft("", "trip-query", 1, SCOPE,
                businessIntent(), candidateContract(),
                List.of(ref("FIXTURE_BUNDLE", "fixtures", 'c')),
                List.of(ref("TEST_SUITE", "acceptance", 'd')), simulationBinding(),
                List.of(), List.of(), CREATED_AT.plus(Duration.ofDays(5)), approvedLater,
                CapabilityProposalDraft.Lifecycle.DRAFT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiry");
    }

    private static CapabilityProposalSnapshot snapshot(
            CapabilityProposalSnapshot.EvidenceState evidenceState,
            MirrorArtifactRef implementationBinding,
            List<MirrorArtifactRef> evidence) {
        return new CapabilityProposalSnapshot("", "trip-cancellation-attribution-query", 1, "",
                SCOPE, 1, fingerprint('e'), businessIntent(), candidateContract(),
                List.of(ref("FIXTURE_BUNDLE", "trip-cancellation-fixtures", 'f')),
                List.of(ref("SCENARIO_PACK", "cancellation-acceptance", '1')),
                simulationBinding(), implementationBinding, evidenceState, evidence,
                List.of("Fixture clock is deterministic"),
                List.of("No real Trip Platform request is made"), EXPIRES_AT,
                provenance(true), CREATED_AT);
    }
}
