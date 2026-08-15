package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseCorrectnessGovernanceRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private JdbcTemplate jdbc;
    private DatabaseCorrectnessGovernanceRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:correctness-governance-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE rg_outcome_calibration_proposals (
                    tenant_id VARCHAR(255) NOT NULL, organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL, environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL, proposal_id VARCHAR(512) NOT NULL,
                    proposal_fingerprint VARCHAR(80) NOT NULL, status VARCHAR(32) NOT NULL,
                    target_kind VARCHAR(32) NOT NULL, target_id VARCHAR(512) NOT NULL,
                    publication_id VARCHAR(512) NOT NULL,
                    publication_fingerprint VARCHAR(80) NOT NULL,
                    suite_run_id VARCHAR(512) NOT NULL,
                    evidence_companion_id VARCHAR(512) NOT NULL,
                    evidence_companion_fingerprint VARCHAR(80) NOT NULL,
                    mismatch_kind VARCHAR(64) NOT NULL, reason_code VARCHAR(128) NOT NULL,
                    owner_id VARCHAR(512) NOT NULL, canonical_json CLOB NOT NULL,
                    created_at TIMESTAMP WITH TIME ZONE NOT NULL, created_by VARCHAR(512) NOT NULL,
                    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                        region_id, proposal_id))
                """);
        jdbc.execute("""
                CREATE TABLE rg_correctness_governance_feedback (
                    tenant_id VARCHAR(255) NOT NULL, organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL, environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL, feedback_id VARCHAR(512) NOT NULL,
                    feedback_fingerprint VARCHAR(80) NOT NULL,
                    publication_id VARCHAR(512) NOT NULL,
                    publication_fingerprint VARCHAR(80) NOT NULL,
                    source_system VARCHAR(128) NOT NULL, source_decision_id VARCHAR(512) NOT NULL,
                    source_decision_revision BIGINT NOT NULL,
                    source_decision_fingerprint VARCHAR(80) NOT NULL, decision VARCHAR(32) NOT NULL,
                    canonical_json CLOB NOT NULL, produced_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE, received_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    received_by VARCHAR(512) NOT NULL,
                    PRIMARY KEY (tenant_id, organization_id, project_id, environment_id,
                        region_id, feedback_id),
                    UNIQUE (tenant_id, organization_id, project_id, environment_id, region_id,
                        source_system, source_decision_id, source_decision_revision))
                """);
        jdbc.execute("""
                CREATE TABLE rg_correctness_outbox (
                    tenant_id VARCHAR(255) NOT NULL, organization_id VARCHAR(255) NOT NULL,
                    project_id VARCHAR(255) NOT NULL, environment_id VARCHAR(255) NOT NULL,
                    region_id VARCHAR(128) NOT NULL, event_id VARCHAR(512) PRIMARY KEY,
                    aggregate_kind VARCHAR(128) NOT NULL, aggregate_id VARCHAR(512) NOT NULL,
                    aggregate_revision BIGINT NOT NULL, event_type VARCHAR(255) NOT NULL,
                    event_json CLOB NOT NULL, occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    published_at TIMESTAMP WITH TIME ZONE)
                """);
        repository = new DatabaseCorrectnessGovernanceRepository(jdbc, mapper);
    }

    @Test
    void persistsProposedOnlyCalibrationAndPayloadFreeEvent() {
        StoredOutcomeCalibrationProposal stored = proposal("customer-secret-marker");
        OutcomeCalibrationProposed event = proposalEvent(stored);

        assertThat(repository.saveProposalIfAbsent(scope(), stored, event)).isEqualTo(stored);
        assertThat(repository.saveProposalIfAbsent(scope(), stored, event)).isEqualTo(stored);
        assertThat(repository.findProposal(scope(), "proposal-1")).contains(stored);

        String eventJson = jdbc.queryForObject(
                "SELECT event_json FROM rg_correctness_outbox", String.class);
        assertThat(eventJson)
                .contains("OutcomeCalibrationProposed.v1", "OUTCOME_MISMATCH")
                .doesNotContain("customer-secret-marker");
    }

    @Test
    void storesLatestExactFeedbackAndRejectsIndexedColumnTampering() {
        StoredCorrectnessGovernanceFeedback first = feedback(
                "feedback-1", 1, NOW.minusSeconds(60), CorrectnessGovernanceFeedback.GateDecision.BLOCKED);
        StoredCorrectnessGovernanceFeedback second = feedback(
                "feedback-2", 2, NOW, CorrectnessGovernanceFeedback.GateDecision.BLOCKED);

        repository.saveFeedbackIfAbsent(scope(), first, feedbackEvent(first));
        repository.saveFeedbackIfAbsent(scope(), second, feedbackEvent(second));

        assertThat(repository.findLatestFeedback(scope(), "publication-1", fp('1')))
                .contains(second);
        assertThat(repository.findLatestFeedback(otherScope(), "publication-1", fp('1')))
                .isEmpty();
        String events = String.join("\n", jdbc.queryForList(
                "SELECT event_json FROM rg_correctness_outbox ORDER BY occurred_at", String.class));
        assertThat(events).doesNotContain("ANEKE internal workbook explanation");

        jdbc.update("""
                UPDATE rg_correctness_governance_feedback
                SET publication_fingerprint = ? WHERE feedback_id = 'feedback-2'
                """, fp('9'));
        assertThatThrownBy(() -> repository.findFeedback(scope(), "feedback-2"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("integrity");
    }

    @Test
    void immutableIdentitiesRejectDifferentContent() {
        StoredOutcomeCalibrationProposal original = proposal("first rationale");
        repository.saveProposalIfAbsent(scope(), original, proposalEvent(original));
        StoredOutcomeCalibrationProposal changed = proposal("changed rationale");

        assertThatThrownBy(() -> repository.saveProposalIfAbsent(
                scope(), changed, proposalEvent(changed)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("immutable identity");
    }

    @Test
    void domainRejectsBlockedFeedbackWithoutBlockingFinding() {
        assertThatThrownBy(() -> new CorrectnessGovernanceFeedback(
                "", "feedback-invalid", scope(), publicationRef(), "ANEKE_TOOL_STUDIO",
                "toolStudio.resourceGatewayProtocol.v1", "decision-invalid", 1, fp('7'),
                CorrectnessGovernanceFeedback.GateDecision.BLOCKED,
                CorrectnessGovernanceFeedback.WorkbookStatus.CURRENT,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.APPROVED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NONE,
                List.of(), NOW, NOW.plusSeconds(60), NOW, "aneke", "correlation-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("blocking finding");
    }

    private StoredOutcomeCalibrationProposal proposal(String rationale) {
        PrincipalRef actor = actor();
        ExactAssetRef scenario = ref("SCENARIO_DRAFT_SET", "scenario-1", '2');
        OutcomeCalibrationProposal value = new OutcomeCalibrationProposal(
                "", "proposal-1", scope(), publicationRef(), "suite-run-1",
                ref("CORRECTNESS_EVIDENCE_COMPANION", "evidence-1", '3'), target(),
                List.of(new ExactCaseRef(scenario, "case-1", fp('4'))),
                List.of(ref("BUSINESS_ORACLE", "oracle-1", '5')),
                OutcomeCalibrationProposal.MismatchKind.EXPECTED_OUTCOME_DIFFERED,
                "OUTCOME_MISMATCH", rationale, "Regression: observed mismatch",
                OutcomeCalibrationProposal.ProposalStatus.PROPOSED, actor, "correlation-1",
                new AuditMetadata(NOW, NOW, actor, actor));
        return StoredOutcomeCalibrationProposal.verified(mapper, value);
    }

    private OutcomeCalibrationProposed proposalEvent(StoredOutcomeCalibrationProposal stored) {
        var value = stored.proposal();
        return new OutcomeCalibrationProposed(
                "", "proposal-event-" + stored.proposalFingerprint().substring(7, 23), scope(),
                ref("OUTCOME_CALIBRATION_PROPOSAL", value.proposalId(),
                        stored.proposalFingerprint()),
                value.publicationRef(), value.evidenceCompanionRef(), value.target(),
                value.suiteRunId(), value.mismatchKind(), value.reasonCode(), "tester",
                "correlation-1", NOW);
    }

    private StoredCorrectnessGovernanceFeedback feedback(
            String id, long revision, Instant producedAt,
            CorrectnessGovernanceFeedback.GateDecision decision) {
        CorrectnessGovernanceFeedback value = new CorrectnessGovernanceFeedback(
                "", id, scope(), publicationRef(), "ANEKE_TOOL_STUDIO",
                "toolStudio.resourceGatewayProtocol.v1", "decision-" + revision, revision,
                fp((char) ('6' + revision)), decision,
                CorrectnessGovernanceFeedback.WorkbookStatus.CURRENT,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.REQUIRED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NONE,
                List.of(new CorrectnessGovernanceFeedback.Finding(
                        "finding-" + revision, CorrectnessGovernanceFeedback.Severity.BLOCKING,
                        CorrectnessGovernanceFeedback.Category.OWNER, "OWNER_APPROVAL_REQUIRED",
                        "Owner approval is required.", "Request approval.",
                        "/governance/decisions/" + revision)),
                producedAt, producedAt.plusSeconds(3600), producedAt, "aneke", "correlation-1");
        return StoredCorrectnessGovernanceFeedback.verified(mapper, value);
    }

    private CorrectnessGovernanceFeedbackReceived feedbackEvent(
            StoredCorrectnessGovernanceFeedback stored) {
        var value = stored.feedback();
        return new CorrectnessGovernanceFeedbackReceived(
                "", "feedback-event-" + value.feedbackId(), scope(),
                ref("CORRECTNESS_GOVERNANCE_FEEDBACK", value.feedbackId(),
                        stored.feedbackFingerprint()),
                value.publicationRef(), value.decision(), value.sourceSystem(),
                value.sourceDecisionId(), value.sourceDecisionRevision(), "aneke",
                "correlation-1", value.receivedAt());
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg");
    }

    private EnterpriseScope otherScope() {
        return new EnterpriseScope("tenant-b", "org-a", "project-a", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('0'));
    }

    private PublicationRef publicationRef() {
        return new PublicationRef("publication-1", 1, fp('1'));
    }

    private PrincipalRef actor() {
        return new PrincipalRef("tester", PrincipalKind.USER, "Tester");
    }

    private ExactAssetRef ref(String kind, String id, char value) {
        return ref(kind, id, fp(value));
    }

    private ExactAssetRef ref(String kind, String id, String fingerprint) {
        return new ExactAssetRef(kind, id, 1, fingerprint);
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
