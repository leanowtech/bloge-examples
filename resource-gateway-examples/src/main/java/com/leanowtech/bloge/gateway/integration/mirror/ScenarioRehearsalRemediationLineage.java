package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Content-addressed public read model for one reviewed Scenario remediation lineage.
 *
 * <p>The lineage is reconstructed from immutable plan, approval, and submission facts. Its state,
 * approval generation, and approval head are derived projections that are checked against those
 * facts before transport. It contains no fixture values, runtime payloads, comments, credentials,
 * or caller-controlled actor identity.</p>
 *
 * @param schemaVersion exact public lineage wire version
 * @param lineageFingerprint canonical address of this complete read model with this field blanked
 * @param state lifecycle state derived from the retained facts
 * @param plan immutable reviewed successor plan
 * @param approvals ordered append-only decision chain
 * @param approvalGeneration current decision generation
 * @param approvalHeadFingerprint current decision-chain head, blank before the first decision
 * @param receipt immutable successor-admission receipt, or {@code null} before submission
 */
public record ScenarioRehearsalRemediationLineage(
        String schemaVersion,
        String lineageFingerprint,
        ScenarioRehearsalRemediationRepository.State state,
        ScenarioRehearsalRemediationPlan plan,
        List<ScenarioRehearsalRemediationApproval> approvals,
        long approvalGeneration,
        String approvalHeadFingerprint,
        ScenarioRehearsalRemediationReceipt receipt
) {
    /** Current public reviewed-remediation lineage version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.scenarioRehearsalRemediationLineage.v1";
    /** Maximum canonical lineage size admitted for content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES =
            8 * 1024 * 1024;

    /** Enforces correspondence between every public projection and its immutable source facts. */
    public ScenarioRehearsalRemediationLineage {
        schemaVersion = version(schemaVersion);
        lineageFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        lineageFingerprint,
                        "lineageFingerprint");
        state = Objects.requireNonNull(state, "state");
        plan = Objects.requireNonNull(plan, "plan");
        approvals = approvals == null
                ? List.of() : List.copyOf(approvals);
        ScenarioRehearsalRemediationRepository.Snapshot snapshot =
                new ScenarioRehearsalRemediationRepository.Snapshot(
                        plan, state, approvals, receipt);
        if (approvalGeneration != snapshot.approvalGeneration()
                || !Objects.equals(
                approvalHeadFingerprint,
                snapshot.approvalHeadFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation lineage approval projection differs from source facts");
        }
        approvalHeadFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        approvalHeadFingerprint,
                        "approvalHeadFingerprint");
        requireFactClosure(plan, approvals, receipt);
    }

    /**
     * Reconstructs and seals a public lineage after independently verifying every source fact.
     *
     * @param mapper canonical protocol mapper
     * @param snapshot integrity-checked repository snapshot
     * @return content-addressed public lineage
     */
    public static ScenarioRehearsalRemediationLineage from(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationRepository.Snapshot snapshot) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        ScenarioRehearsalRemediationRepository.Snapshot exact =
                Objects.requireNonNull(snapshot, "snapshot");
        exact.plan().verify(exactMapper);
        requireSuccessorFingerprint(
                exactMapper, exact.plan());
        exact.approvals().forEach(
                approval -> approval.verify(exactMapper));
        if (exact.receipt() != null) {
            exact.receipt().verify(exactMapper);
        }
        return seal(
                exactMapper,
                new ScenarioRehearsalRemediationLineage(
                        "",
                        "",
                        exact.state(),
                        exact.plan(),
                        exact.approvals(),
                        exact.approvalGeneration(),
                        exact.approvalHeadFingerprint(),
                        exact.receipt()));
    }

    /** Recomputes and verifies this complete public lineage. */
    public void verify(ObjectMapper mapper) {
        ObjectMapper exactMapper =
                Objects.requireNonNull(mapper, "mapper");
        plan.verify(exactMapper);
        requireSuccessorFingerprint(
                exactMapper, plan);
        approvals.forEach(approval -> approval.verify(exactMapper));
        if (receipt != null) {
            receipt.verify(exactMapper);
        }
        if (lineageFingerprint.isBlank()
                || !lineageFingerprint.equals(
                seal(exactMapper, this).lineageFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation lineage fingerprint mismatch");
        }
    }

    private static ScenarioRehearsalRemediationLineage seal(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationLineage value) {
        ScenarioRehearsalRemediationLineage material =
                value.withFingerprint("");
        return material.withFingerprint(
                ProtocolFingerprint.ofBounded(
                        mapper,
                        material,
                        MAXIMUM_CANONICAL_BYTES));
    }

    private ScenarioRehearsalRemediationLineage withFingerprint(
            String value) {
        return new ScenarioRehearsalRemediationLineage(
                schemaVersion,
                value,
                state,
                plan,
                approvals,
                approvalGeneration,
                approvalHeadFingerprint,
                receipt);
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException(
                    "unsupported Scenario remediation lineage schemaVersion");
        }
        return exact;
    }

    private static void requireSuccessorFingerprint(
            ObjectMapper mapper,
            ScenarioRehearsalRemediationPlan plan) {
        String expected =
                ProtocolFingerprint.ofBounded(
                        mapper,
                        plan.successorRequest(),
                        ScenarioRehearsalRemediationPlan
                                .MAXIMUM_CANONICAL_BYTES);
        if (!expected.equals(
                plan.successorRequestFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario remediation successor request fingerprint mismatch");
        }
    }

    private static void requireFactClosure(
            ScenarioRehearsalRemediationPlan plan,
            List<ScenarioRehearsalRemediationApproval> approvals,
            ScenarioRehearsalRemediationReceipt receipt) {
        List<ScenarioRehearsalRemediationApprovalCommand.Role>
                roles = plan.approvalPolicy().requiredRoles();
        if (approvals.size() > roles.size()) {
            throw new IllegalArgumentException(
                    "Scenario remediation approval chain exceeds the frozen policy");
        }
        Set<String> controlPrincipals = new HashSet<>();
        Instant previousDecision = plan.generatedAt();
        for (int index = 0; index < approvals.size(); index++) {
            ScenarioRehearsalRemediationApproval approval =
                    approvals.get(index);
            if (!approval.scope().equals(plan.scope())
                    || !approval.governanceTicketRef().equals(
                    plan.governanceTicketRef())
                    || approval.role() != roles.get(index)
                    || approval.decidedAt().isBefore(
                    previousDecision)
                    || approval.decidedAt().isAfter(
                    plan.expiresAt())
                    || !controlPrincipals.add(
                    approval.actorId())
                    || !approval.delegatedBy().isBlank()
                    && !controlPrincipals.add(
                    approval.delegatedBy())) {
                throw new IllegalArgumentException(
                        "Scenario remediation approval facts violate the frozen policy");
            }
            previousDecision = approval.decidedAt();
        }
        if (receipt != null
                && (!receipt.scope().equals(plan.scope())
                || !receipt.remediationId().equals(
                plan.remediationId())
                || !receipt.remediationPlanFingerprint()
                .equals(plan.planFingerprint())
                || !receipt.predecessorJobId().equals(
                plan.predecessorJobId())
                || !receipt.successorRequestFingerprint()
                .equals(plan.successorRequestFingerprint())
                || receipt.approvalGeneration()
                != approvals.size()
                || receipt.acceptedAt().isAfter(
                plan.expiresAt())
                || receipt.acceptedAt().isBefore(
                previousDecision))) {
            throw new IllegalArgumentException(
                    "Scenario remediation receipt violates the frozen plan");
        }
    }
}
