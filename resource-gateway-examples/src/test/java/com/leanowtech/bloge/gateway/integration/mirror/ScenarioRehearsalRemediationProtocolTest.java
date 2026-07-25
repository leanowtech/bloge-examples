package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScenarioRehearsalRemediationProtocolTest {
    private static final String SHA_A =
            "sha256:" + "a".repeat(64);
    private static final String SHA_B =
            "sha256:" + "b".repeat(64);
    private static final String SHA_C =
            "sha256:" + "c".repeat(64);
    private static final String REMEDIATION_ID =
            "scenario-remediation-" + "d".repeat(64);
    private static final String PREDECESSOR_JOB_ID =
            "scenario-batch-" + "e".repeat(64);
    private static final String SUCCESSOR_JOB_ID =
            "scenario-batch-" + "f".repeat(64);

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void previewProposalAllowsOnlyExactRerunOrStrictPlanReplacement() {
        ScenarioRehearsalRemediationPreviewRequest exact =
                new ScenarioRehearsalRemediationPreviewRequest(
                        "",
                        "preview-a",
                        SHA_A,
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.RERUN_EXACT,
                        List.of(),
                        ticket(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode
                                .TRANSIENT_EXECUTION_RECHECK);

        assertThat(exact.schemaVersion()).isEqualTo(
                ScenarioRehearsalRemediationPreviewRequest
                        .SCHEMA_VERSION);
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationPreviewRequest(
                        "",
                        "preview-a",
                        SHA_A,
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.RERUN_EXACT,
                        List.of(replacement(0, "entry-a")),
                        ticket(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode
                                .TRANSIENT_EXECUTION_RECHECK))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("inconsistent");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationPreviewRequest(
                        "",
                        "preview-a",
                        SHA_A,
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.REPLACE_COMPILED_PLANS,
                        List.of(
                                replacement(1, "entry-b"),
                                replacement(0, "entry-a")),
                        ticket(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .ReasonCode.SCENARIO_REVISION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ordered");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationPreviewRequest
                        .PlanReplacement(
                        0,
                        "entry-a",
                        plan("plan-a", SHA_A),
                        plan("plan-a", SHA_A)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must change");
    }

    @Test
    void sealedPlanFreezesBlockedPredecessorAndCompleteSuccessor() {
        ScenarioRehearsalRemediationPreviewRequest.PlanReplacement
                replacement = replacement(0, "entry-a");
        ScenarioRehearsalBatchRequest successor =
                successor(replacement.replacementCompiledPlanRef());
        String successorFingerprint =
                ProtocolFingerprint.of(mapper, successor);
        ScenarioRehearsalRemediationPlan sealed =
                ScenarioRehearsalRemediationPlan.seal(
                        mapper,
                        new ScenarioRehearsalRemediationPlan(
                                "",
                                "",
                                scope(),
                                REMEDIATION_ID,
                                "preview-a",
                                PREDECESSOR_JOB_ID,
                                SHA_A,
                                SHA_B,
                                ScenarioRehearsalBatchJob.Status
                                        .FAILED,
                                List.of(
                                        "REHEARSAL_FAILED",
                                        "BLOCKER_ASSERTION_FAILED"),
                                ScenarioRehearsalRemediationPreviewRequest
                                        .Strategy
                                        .REPLACE_COMPILED_PLANS,
                                List.of(replacement),
                                successor,
                                successorFingerprint,
                                ticket(),
                                ScenarioRehearsalRemediationPlan
                                        .ApprovalPolicy.twoPerson(),
                                Instant.parse(
                                        "2026-07-25T10:00:00Z"),
                                Instant.parse(
                                        "2026-07-26T10:00:00Z")));

        sealed.verify(mapper);
        assertThat(sealed.planFingerprint())
                .startsWith("sha256:");
        assertThat(sealed.successorRequest().requestId())
                .isEqualTo(REMEDIATION_ID);
        assertThat(sealed.predecessorBlockers())
                .containsExactly(
                        "BLOCKER_ASSERTION_FAILED",
                        "REHEARSAL_FAILED");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationPlan
                        .ApprovalPolicy(
                        List.of(
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER),
                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("independent reviewer");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationPlan(
                        "",
                        "",
                        scope(),
                        REMEDIATION_ID,
                        "preview-a",
                        PREDECESSOR_JOB_ID,
                        SHA_A,
                        SHA_B,
                        ScenarioRehearsalBatchJob.Status.SUCCEEDED,
                        List.of(),
                        ScenarioRehearsalRemediationPreviewRequest
                                .Strategy.REPLACE_COMPILED_PLANS,
                        List.of(replacement),
                        successor,
                        successorFingerprint,
                        ticket(),
                        ScenarioRehearsalRemediationPlan
                                .ApprovalPolicy.twoPerson(),
                        Instant.parse("2026-07-25T10:00:00Z"),
                        Instant.parse("2026-07-26T10:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blocked predecessor");
    }

    @Test
    void approvalFactsFormAnImmutableActorBoundHashChain() {
        ScenarioRehearsalRemediationApproval first =
                ScenarioRehearsalRemediationApproval.seal(
                        mapper,
                        approval(
                                1,
                                "",
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-a"));
        ScenarioRehearsalRemediationApproval second =
                ScenarioRehearsalRemediationApproval.seal(
                        mapper,
                        approval(
                                2,
                                first.approvalFingerprint(),
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.INDEPENDENT_REVIEWER,
                                "reviewer-b"));

        first.verify(mapper);
        second.verify(mapper);
        assertThat(second.previousApprovalFingerprint())
                .isEqualTo(first.approvalFingerprint());
        assertThatThrownBy(() ->
                approval(
                        2,
                        "",
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.INDEPENDENT_REVIEWER,
                        "reviewer-b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generation chain");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationApprovalCommand(
                        "",
                        "approve-a",
                        SHA_A,
                        0,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Role.OWNER,
                        ScenarioRehearsalRemediationApprovalCommand
                                .Decision.REJECT,
                        ticket(),
                        ScenarioRehearsalRemediationApprovalCommand
                                .ReasonCode.APPROVED_AS_REVIEWED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("differ");
    }

    @Test
    void submitAndReceiptBindTheApprovedHeadToADifferentSuccessor() {
        ScenarioRehearsalRemediationSubmitCommand command =
                new ScenarioRehearsalRemediationSubmitCommand(
                        "",
                        "submit-a",
                        SHA_A,
                        2,
                        SHA_B,
                        null);
        String commandFingerprint =
                ProtocolFingerprint.of(mapper, command);
        ScenarioRehearsalRemediationReceipt receipt =
                ScenarioRehearsalRemediationReceipt.seal(
                        mapper,
                        new ScenarioRehearsalRemediationReceipt(
                                "",
                                "",
                                commandFingerprint,
                                scope(),
                                REMEDIATION_ID,
                                SHA_A,
                                PREDECESSOR_JOB_ID,
                                SUCCESSOR_JOB_ID,
                                SHA_C,
                                2,
                                SHA_B,
                                "owner-a",
                                "",
                                Instant.parse(
                                        "2026-07-25T11:00:00Z")));

        receipt.verify(mapper);
        assertThat(receipt.approvalHeadFingerprint())
                .isEqualTo(command.expectedApprovalHeadFingerprint());
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationSubmitCommand(
                        "",
                        "submit-a",
                        SHA_A,
                        1,
                        SHA_B,
                        null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("two approval");
        assertThatThrownBy(() ->
                new ScenarioRehearsalRemediationReceipt(
                        "",
                        "",
                        commandFingerprint,
                        scope(),
                        REMEDIATION_ID,
                        SHA_A,
                        PREDECESSOR_JOB_ID,
                        PREDECESSOR_JOB_ID,
                        SHA_C,
                        2,
                        SHA_B,
                        "owner-a",
                        "",
                        Instant.parse(
                                "2026-07-25T11:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    private static ScenarioRehearsalRemediationApproval approval(
            long generation,
            String previous,
            ScenarioRehearsalRemediationApprovalCommand.Role role,
            String actor) {
        return new ScenarioRehearsalRemediationApproval(
                "",
                "",
                SHA_C,
                scope(),
                REMEDIATION_ID,
                SHA_A,
                generation,
                previous,
                role,
                ScenarioRehearsalRemediationApprovalCommand
                        .Decision.APPROVE,
                ticket(),
                ScenarioRehearsalRemediationApprovalCommand
                        .ReasonCode.APPROVED_AS_REVIEWED,
                actor,
                "",
                Instant.parse("2026-07-25T10:30:00Z")
                        .plusSeconds(generation));
    }

    private static ScenarioRehearsalBatchRequest successor(
            MirrorArtifactRef plan) {
        return new ScenarioRehearsalBatchRequest(
                "",
                REMEDIATION_ID,
                List.of(
                        new ScenarioRehearsalBatchRequest.Entry(
                                "entry-a", plan)));
    }

    private static ScenarioRehearsalRemediationPreviewRequest
            .PlanReplacement replacement(
            int index,
            String entryId) {
        return new ScenarioRehearsalRemediationPreviewRequest
                .PlanReplacement(
                index,
                entryId,
                plan("plan-old-" + index, SHA_A),
                plan("plan-new-" + index, SHA_B));
    }

    private static MirrorArtifactRef plan(
            String id,
            String fingerprint) {
        return new MirrorArtifactRef(
                "COMPILED_REHEARSAL_PLAN",
                id,
                1,
                fingerprint);
    }

    private static MirrorArtifactRef ticket() {
        return new MirrorArtifactRef(
                "GOVERNANCE_REVIEW_TICKET",
                "ticket-a",
                1,
                SHA_C);
    }

    private static CapabilitySnapshot.Scope scope() {
        return new CapabilitySnapshot.Scope(
                "tenant-a",
                "org-a",
                "project-a",
                "test",
                "sg");
    }
}
