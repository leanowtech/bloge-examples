package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class ScenarioRehearsalRemediationComparisonTestFixtures {
    static final CapabilitySnapshot.Scope SCOPE =
            new CapabilitySnapshot.Scope(
                    "tenant-a",
                    "org-a",
                    "project-a",
                    "test",
                    "sg");
    static final String REMEDIATION_ID =
            "scenario-remediation-" + "1".repeat(64);
    static final String PREDECESSOR_ID =
            "scenario-batch-" + "2".repeat(64);
    static final String SUCCESSOR_ID =
            "scenario-batch-" + "3".repeat(64);
    static final String PREDECESSOR_SEED =
            fingerprint('4');
    static final String SUCCESSOR_SEED =
            fingerprint('5');
    private static final Instant GENERATED_AT =
            Instant.parse("2026-07-25T10:00:00Z");
    private static final Instant EXPIRES_AT =
            Instant.parse("2026-07-26T10:00:00Z");

    private ScenarioRehearsalRemediationComparisonTestFixtures() {
    }

    static Fixture resolved(ObjectMapper mapper) {
        MirrorArtifactRef oldPlan =
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "refund-plan-v1",
                        '6');
        MirrorArtifactRef newPlan =
                ref(
                        "COMPILED_REHEARSAL_PLAN",
                        "refund-plan-v2",
                        '7');
        ScenarioRehearsalBatchRequest successorRequest =
                new ScenarioRehearsalBatchRequest(
                        "",
                        REMEDIATION_ID,
                        List.of(
                                new ScenarioRehearsalBatchRequest
                                        .Entry(
                                        "refund-happy-path",
                                        newPlan)));
        String successorRequestFingerprint =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        successorRequest,
                        ScenarioRehearsalRemediationPlan
                                .MAXIMUM_CANONICAL_BYTES);
        List<String> predecessorBlockers =
                List.of(
                        "BATCH_ITEM_FAILED",
                        "BATCH_STATUS_FAILED",
                        "CHILD_WORKBOOK_BLOCKED");
        ScenarioRehearsalRemediationPlan plan =
                ScenarioRehearsalRemediationPlan.seal(
                        mapper,
                        new ScenarioRehearsalRemediationPlan(
                                "",
                                "",
                                SCOPE,
                                REMEDIATION_ID,
                                "preview-a",
                                PREDECESSOR_ID,
                                PREDECESSOR_SEED,
                                fingerprint('8'),
                                ScenarioRehearsalBatchJob.Status
                                        .FAILED,
                                predecessorBlockers,
                                ScenarioRehearsalRemediationPreviewRequest
                                        .Strategy
                                        .REPLACE_COMPILED_PLANS,
                                ScenarioRehearsalRemediationPreviewRequest
                                        .ReasonCode
                                        .SCENARIO_REVISION,
                                List.of(
                                        new ScenarioRehearsalRemediationPreviewRequest
                                                .PlanReplacement(
                                                0,
                                                "refund-happy-path",
                                                oldPlan,
                                                newPlan)),
                                successorRequest,
                                successorRequestFingerprint,
                                ticket(),
                                ScenarioRehearsalRemediationPlan
                                        .ApprovalPolicy
                                        .twoPerson(
                                                1,
                                                fingerprint('9')),
                                GENERATED_AT,
                                EXPIRES_AT));
        ScenarioRehearsalRemediationApproval owner =
                ScenarioRehearsalRemediationApproval.seal(
                        mapper,
                        approval(
                                plan,
                                1,
                                "",
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role.OWNER,
                                "owner-a",
                                GENERATED_AT.plusSeconds(60)));
        ScenarioRehearsalRemediationApproval reviewer =
                ScenarioRehearsalRemediationApproval.seal(
                        mapper,
                        approval(
                                plan,
                                2,
                                owner.approvalFingerprint(),
                                ScenarioRehearsalRemediationApprovalCommand
                                        .Role
                                        .INDEPENDENT_REVIEWER,
                                "reviewer-b",
                                GENERATED_AT.plusSeconds(120)));
        ScenarioRehearsalRemediationReceipt receipt =
                ScenarioRehearsalRemediationReceipt.seal(
                        mapper,
                        new ScenarioRehearsalRemediationReceipt(
                                "",
                                "",
                                fingerprint('a'),
                                SCOPE,
                                REMEDIATION_ID,
                                plan.planFingerprint(),
                                PREDECESSOR_ID,
                                SUCCESSOR_ID,
                                successorRequestFingerprint,
                                2,
                                reviewer.approvalFingerprint(),
                                "owner-a",
                                "",
                                GENERATED_AT.plusSeconds(180)));
        ScenarioRehearsalRemediationLineage lineage =
                ScenarioRehearsalRemediationLineage.from(
                        mapper,
                        new ScenarioRehearsalRemediationRepository
                                .Snapshot(
                                plan,
                                ScenarioRehearsalRemediationRepository
                                        .State.SUBMITTED,
                                List.of(owner, reviewer),
                                receipt));

        ScenarioRehearsalBatchWorkbookSeed.EntryResult
                predecessorEntry = entry(
                oldPlan,
                "scenario-" + "b".repeat(64),
                ScenarioRehearsalBatchItemPage.Status
                        .FAILED,
                ScenarioCaseRehearsalResult.Outcome.FAIL,
                "REHEARSAL_FAILED",
                List.of("BLOCKER_ASSERTION_FAILED"),
                new ScenarioRehearsalResult.Summary(
                        1, 0, 1, 0, 2,
                        1, 0, 0, 0));
        ScenarioRehearsalBatchWorkbookSeed.EntryResult
                successorEntry = entry(
                newPlan,
                "scenario-" + "c".repeat(64),
                ScenarioRehearsalBatchItemPage.Status
                        .PASSED,
                ScenarioCaseRehearsalResult.Outcome.PASS,
                "",
                List.of(),
                new ScenarioRehearsalResult.Summary(
                        1, 1, 0, 0, 2,
                        0, 0, 0, 0));
        ScenarioRehearsalBatchWorkbookSeed predecessor =
                workbook(
                        mapper,
                        PREDECESSOR_ID,
                        "batch-original",
                        PREDECESSOR_SEED,
                        fingerprint('d'),
                        fingerprint('e'),
                        fingerprint('8'),
                        fingerprint('f'),
                        ScenarioRehearsalBatchJob.Status
                                .FAILED,
                        new ScenarioRehearsalBatchJob.Summary(
                                1, 1, 0, 1, 0, 0),
                        List.of(predecessorEntry),
                        false,
                        predecessorBlockers);
        ScenarioRehearsalBatchWorkbookSeed successor =
                workbook(
                        mapper,
                        SUCCESSOR_ID,
                        REMEDIATION_ID,
                        SUCCESSOR_SEED,
                        successorRequestFingerprint,
                        fingerprint('0'),
                        fingerprint('1'),
                        fingerprint('2'),
                        ScenarioRehearsalBatchJob.Status
                                .SUCCEEDED,
                        new ScenarioRehearsalBatchJob.Summary(
                                1, 1, 1, 0, 0, 0),
                        List.of(successorEntry),
                        true,
                        List.of());
        return new Fixture(
                lineage,
                predecessor,
                successor);
    }

    private static ScenarioRehearsalBatchWorkbookSeed
    workbook(
            ObjectMapper mapper,
            String jobId,
            String requestId,
            String seedFingerprint,
            String requestFingerprint,
            String manifestFingerprint,
            String evidenceBundleFingerprint,
            String evidenceIndexFingerprint,
            ScenarioRehearsalBatchJob.Status status,
            ScenarioRehearsalBatchJob.Summary summary,
            List<ScenarioRehearsalBatchWorkbookSeed.EntryResult>
                    entries,
            boolean gateReady,
            List<String> blockers) {
        String attestation =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        new WorkbookAttestationMaterial(
                                "RESOURCE_GATEWAY_SCENARIO_REHEARSAL_BATCH_WORKBOOK_V1",
                                ScenarioRehearsalBatchWorkbookSeed
                                        .SCHEMA_VERSION,
                                jobId,
                                seedFingerprint,
                                evidenceBundleFingerprint,
                                evidenceIndexFingerprint),
                        ScenarioRehearsalBatchWorkbookSeed
                                .MAXIMUM_ATTESTATION_BYTES);
        VisualRunEvidenceSeal seal =
                new VisualRunEvidenceSeal(
                        "",
                        attestation,
                        "Ed25519",
                        "workbook-key",
                        GENERATED_AT.plusSeconds(240),
                        "c2lnbmF0dXJl");
        ScenarioRehearsalBatchWorkbookSeed workbook =
                mock(
                        ScenarioRehearsalBatchWorkbookSeed
                                .class);
        when(workbook.schemaVersion()).thenReturn(
                ScenarioRehearsalBatchWorkbookSeed
                        .SCHEMA_VERSION);
        when(workbook.scope()).thenReturn(SCOPE);
        when(workbook.jobId()).thenReturn(jobId);
        when(workbook.requestId()).thenReturn(requestId);
        when(workbook.seedFingerprint()).thenReturn(
                seedFingerprint);
        when(workbook.requestFingerprint()).thenReturn(
                requestFingerprint);
        when(workbook.manifestFingerprint()).thenReturn(
                manifestFingerprint);
        when(workbook.evidenceBundleFingerprint())
                .thenReturn(evidenceBundleFingerprint);
        when(workbook.evidenceIndexFingerprint())
                .thenReturn(evidenceIndexFingerprint);
        when(workbook.workbookSeal()).thenReturn(seal);
        when(workbook.status()).thenReturn(status);
        when(workbook.summary()).thenReturn(summary);
        when(workbook.entries()).thenReturn(entries);
        when(workbook.gateReady()).thenReturn(gateReady);
        when(workbook.blockers()).thenReturn(blockers);
        when(workbook.attestationMaterialFingerprint(
                any())).thenReturn(attestation);
        return workbook;
    }

    private static ScenarioRehearsalBatchWorkbookSeed.EntryResult
    entry(
            MirrorArtifactRef plan,
            String runId,
            ScenarioRehearsalBatchItemPage.Status status,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String failureCode,
            List<String> blockers,
            ScenarioRehearsalResult.Summary summary) {
        String requestId = "child-request";
        ScenarioRehearsalBatchWorkbookSeed.ChildWorkbook child =
                new ScenarioRehearsalBatchWorkbookSeed
                        .ChildWorkbook(
                        ScenarioRehearsalWorkbookSeed
                                .SCHEMA_VERSION,
                        fingerprint(
                                status
                                        == ScenarioRehearsalBatchItemPage
                                        .Status.PASSED
                                        ? '3' : '4'),
                        runId,
                        requestId,
                        plan,
                        ref(
                                "SCENARIO_PACK",
                                "refund-pack",
                                '5'),
                        ref(
                                "CAPABILITY",
                                "refund-capability",
                                '6'),
                        fingerprint('7'),
                        fingerprint('8'),
                        "child-evidence-key",
                        fingerprint('9'),
                        outcome,
                        summary,
                        blockers.isEmpty(),
                        blockers);
        return new ScenarioRehearsalBatchWorkbookSeed
                .EntryResult(
                0,
                "refund-happy-path",
                plan,
                requestId,
                runId,
                status,
                1,
                runId,
                child.evidenceBundleFingerprint(),
                child.seedFingerprint(),
                failureCode,
                child);
    }

    private static ScenarioRehearsalRemediationApproval approval(
            ScenarioRehearsalRemediationPlan plan,
            long generation,
            String previous,
            ScenarioRehearsalRemediationApprovalCommand.Role role,
            String actor,
            Instant decidedAt) {
        return new ScenarioRehearsalRemediationApproval(
                "",
                "",
                fingerprint('b'),
                SCOPE,
                REMEDIATION_ID,
                plan.planFingerprint(),
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
                decidedAt);
    }

    private static MirrorArtifactRef ticket() {
        return ref(
                "GOVERNANCE_REVIEW_TICKET",
                "ticket-a",
                'c');
    }

    private static MirrorArtifactRef ref(
            String kind,
            String id,
            char fingerprint) {
        return new MirrorArtifactRef(
                kind,
                id,
                1,
                fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:"
                + String.valueOf(value).repeat(64);
    }

    private record WorkbookAttestationMaterial(
            String domain,
            String schemaVersion,
            String jobId,
            String seedFingerprint,
            String evidenceBundleFingerprint,
            String evidenceIndexFingerprint
    ) {
    }

    record Fixture(
            ScenarioRehearsalRemediationLineage lineage,
            ScenarioRehearsalBatchWorkbookSeed predecessor,
            ScenarioRehearsalBatchWorkbookSeed successor
    ) {
    }
}
