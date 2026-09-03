package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.InMemoryGraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GovernanceGateIntegrationServiceTest {

    @Test
    void storesGateResultIdempotentlyAndMarksItStaleAfterDraftChanges() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft revisionOne = drafts.save(draft());
        InMemoryGovernanceGateResultRepository gates = new InMemoryGovernanceGateResultRepository();
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(drafts, null, null, null, gates);
        GovernanceGateResult result = gateResult("gate-1", revisionOne, "BLOCKED", null);

        GovernanceGateResult first = service.submitGateResult(result, gateContext("corr-1")).payload();
        GovernanceGateResult duplicate = service.submitGateResult(result, gateContext("corr-2")).payload();
        GovernanceGateView current = service.governanceGate(revisionOne.draftId(), readContext("corr-3")).payload();
        GraphDraft revisionTwo = drafts.save(revisionOne);
        GovernanceGateView stale = service.governanceGate(revisionTwo.draftId(), readContext("corr-4")).payload();

        assertThat(first).isEqualTo(result);
        assertThat(duplicate).isEqualTo(first);
        assertThat(gates.forDraft(revisionOne.draftId())).containsExactly(result);
        assertThat(current.freshness()).isEqualTo("CURRENT");
        assertThat(stale.freshness()).isEqualTo("STALE");
        assertThat(stale.currentRevision()).isEqualTo(revisionTwo.revision());
        assertThat(stale.result().target().revision()).isEqualTo(revisionOne.revision());
    }

    @Test
    void rejectsConflictingIdWrongPurposeAndWrongSnapshot() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft stored = drafts.save(draft());
        InMemoryGovernanceGateResultRepository gates = new InMemoryGovernanceGateResultRepository();
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(drafts, null, null, null, gates);
        GovernanceGateResult original = gateResult("gate-1", stored, "BLOCKED", null);
        service.submitGateResult(original, gateContext("corr-1"));

        GovernanceGateResult conflicting = gateResult("gate-1", stored, "PASSED", null);
        GovernanceGateResult wrongSnapshot = new GovernanceGateResult("", "gate-2",
                new GovernanceGateResult.Target("GRAPH_DRAFT", stored.draftId(), stored.revision(), "sha256:wrong",
                        stored.tenantId(), stored.namespace(), stored.environment()),
                "BLOCKED", List.of(), Instant.now(), null, "");

        assertThatThrownBy(() -> service.submitGateResult(conflicting, gateContext("corr-2")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, exception ->
                        assertThat(exception.problem().code()).isEqualTo("RG.INTEGRATION.GATE_RESULT_ID_CONFLICT"));
        assertThatThrownBy(() -> service.submitGateResult(wrongSnapshot, gateContext("corr-3")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, exception ->
                        assertThat(exception.problem().code()).isEqualTo("RG.INTEGRATION.GATE_TARGET_STALE"));
        assertThatThrownBy(() -> service.submitGateResult(original, readContext("corr-4")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, exception ->
                        assertThat(exception.problem().code()).isEqualTo("RG.INTEGRATION.PURPOSE_NOT_ALLOWED"));
    }

    @Test
    void marksExpiredGateResultWithoutTreatingItAsCurrent() {
        InMemoryGraphDraftRepository drafts = new InMemoryGraphDraftRepository();
        GraphDraft stored = drafts.save(draft());
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(drafts, null, null, null,
                new InMemoryGovernanceGateResultRepository());
        GovernanceGateResult expired = gateResult("gate-expired", stored, "BLOCKED",
                Instant.parse("2026-07-11T00:00:00Z"));

        service.submitGateResult(expired, gateContext("corr-expired"));

        assertThat(service.authoringGovernanceGate(stored.draftId()).freshness()).isEqualTo("EXPIRED");
    }

    private static GovernanceGateResult gateResult(String id, GraphDraft draft, String status, Instant expiresAt) {
        return new GovernanceGateResult("", id,
                new GovernanceGateResult.Target("GRAPH_DRAFT", draft.draftId(), draft.revision(),
                        ToolStudioIntegrationService.draftFingerprint(draft), draft.tenantId(), draft.namespace(),
                        draft.environment()),
                status,
                List.of(new GovernanceGateResult.Issue("issue-1", "BLOCKING", "CONTRACT_MISSING",
                        "Graph contract is missing.", "/nodes/eligibility", "Add a contract suite.", "")),
                Instant.parse("2026-07-10T00:00:00Z"), expiresAt, "");
    }

    private static IntegrationRequestContext gateContext(String correlationId) {
        return context("GOVERNANCE_GATE_FEEDBACK", correlationId);
    }

    private static IntegrationRequestContext readContext(String correlationId) {
        return context("GOVERNANCE_EVIDENCE_INGESTION", correlationId);
    }

    private static IntegrationRequestContext context(String purpose, String correlationId) {
        return new IntegrationRequestContext("tenant-a", "knowledge-governance", "knowledge", "prod",
                "ap-southeast-1", "WORKLOAD", "aneke-sync", "", purpose, correlationId);
    }

    private static GraphDraft draft() {
        return new GraphDraft("", "draft-1", 0, "knowledgeTool", "tenant-a", "knowledge", "prod", "DRAFT",
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(), List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection("", ""), Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }
}
