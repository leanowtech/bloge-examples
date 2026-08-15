package com.leanowtech.bloge.gateway.testing.correctness.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactCaseRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceCompanion;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessEvidenceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;
import com.leanowtech.bloge.gateway.testing.correctness.run.StoredCorrectnessEvidenceCompanion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CorrectnessGovernanceServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CorrectnessGovernanceRepository governance =
            mock(CorrectnessGovernanceRepository.class);
    private final CorrectnessPublicationRepository publications =
            mock(CorrectnessPublicationRepository.class);
    private final CorrectnessEvidenceRepository evidence = mock(CorrectnessEvidenceRepository.class);
    private CorrectnessGovernanceService service;

    @BeforeEach
    void setUp() {
        service = new CorrectnessGovernanceService(
                governance, publications, evidence, mapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(governance.saveProposalIfAbsent(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(governance.saveFeedbackIfAbsent(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void derivesExactProposalClosureFromEvidenceAndKeepsItProposed() throws Exception {
        evidence("case-1", "oracle-1");
        OutcomeCalibrationRequest request = new OutcomeCalibrationRequest(
                "proposal-1", "suite-run-1", fp('e'), List.of("case-1"),
                List.of("oracle-1"),
                OutcomeCalibrationProposal.MismatchKind.EXPECTED_OUTCOME_DIFFERED,
                "OUTCOME_MISMATCH", "Observed business result differs from reviewed truth.",
                "Regression for outcome mismatch");

        StoredOutcomeCalibrationProposal result = service.propose(request, identity());

        assertThat(result.proposal().status())
                .isEqualTo(OutcomeCalibrationProposal.ProposalStatus.PROPOSED);
        assertThat(result.proposal().caseRefs()).extracting(ExactCaseRef::caseId)
                .containsExactly("case-1");
        assertThat(result.proposal().oracleRefs()).extracting(ExactAssetRef::id)
                .containsExactly("oracle-1");
        assertThat(result.proposal().owner().id()).isEqualTo("author-1");

        ArgumentCaptor<OutcomeCalibrationProposed> event =
                ArgumentCaptor.forClass(OutcomeCalibrationProposed.class);
        org.mockito.Mockito.verify(governance).saveProposalIfAbsent(
                any(), any(), event.capture());
        assertThat(mapper.writeValueAsString(event.getValue()))
                .doesNotContain("Observed business result");
    }

    @Test
    void refusesCallerSelectedCaseOutsideEvidenceClosure() {
        evidence("case-1", "oracle-1");
        OutcomeCalibrationRequest request = new OutcomeCalibrationRequest(
                "proposal-1", "suite-run-1", fp('e'), List.of("case-substituted"),
                List.of("oracle-1"),
                OutcomeCalibrationProposal.MismatchKind.MISSING_BUSINESS_BRANCH,
                "BRANCH_MISSING", "Missing branch.", "Regression");

        assertThatThrownBy(() -> service.propose(request, identity()))
                .isInstanceOf(CorrectnessGovernanceException.class)
                .extracting("code")
                .isEqualTo("RG.CORRECTNESS.CALIBRATION_REQUEST_INVALID");
    }

    @Test
    void receivesAnekeFeedbackOnlyForExactPublication() {
        publication();
        CorrectnessGovernanceFeedbackRequest request = new CorrectnessGovernanceFeedbackRequest(
                "feedback-1", fp('1'), "ANEKE_TOOL_STUDIO",
                "1.1.0", "decision-1", 1, fp('9'),
                CorrectnessGovernanceFeedback.GateDecision.BLOCKED,
                CorrectnessGovernanceFeedback.WorkbookStatus.MISSING,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.REQUIRED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NONE,
                List.of(new CorrectnessGovernanceFeedback.Finding(
                        "finding-1", CorrectnessGovernanceFeedback.Severity.BLOCKING,
                        CorrectnessGovernanceFeedback.Category.WORKBOOK,
                        "WORKBOOK_REQUIRED", "Workbook is missing.", "Create workbook.",
                        "https://aneke.example/workbooks/new")),
                NOW.minusSeconds(30), NOW.plusSeconds(3600));

        StoredCorrectnessGovernanceFeedback result = service.receiveFeedback(
                "publication-1", request, governanceIdentity());

        assertThat(result.feedback().scope()).isEqualTo(scope());
        assertThat(result.feedback().publicationRef())
                .isEqualTo(new PublicationRef("publication-1", 1, fp('1')));
        assertThat(result.feedback().receivedAt()).isEqualTo(NOW);
        assertThat(result.feedback().receivedBy()).isEqualTo("aneke-sidecar");
    }

    @Test
    void toleratesBoundedCrossSystemClockSkewWithoutWeakeningExpirySemantics() {
        publication();
        CorrectnessGovernanceFeedbackRequest request = new CorrectnessGovernanceFeedbackRequest(
                "feedback-clock-skew", fp('1'), "ANEKE_TOOL_STUDIO",
                "1.1.0", "decision-clock-skew", 1, fp('8'),
                CorrectnessGovernanceFeedback.GateDecision.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.WorkbookStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NOT_EVALUATED,
                List.of(), NOW.plusSeconds(30), NOW.plusSeconds(3600));

        StoredCorrectnessGovernanceFeedback result = service.receiveFeedback(
                "publication-1", request, governanceIdentity());

        assertThat(result.feedback().producedAt()).isAfter(result.feedback().receivedAt());
        assertThat(result.feedback().expiresAt()).isAfter(result.feedback().producedAt());
    }

    @Test
    void rejectsFeedbackOutsideTheAdvertisedProtocolCompatibilityWindow() {
        publication();
        CorrectnessGovernanceFeedbackRequest request = new CorrectnessGovernanceFeedbackRequest(
                "feedback-unsupported-protocol", fp('1'), "ANEKE_TOOL_STUDIO",
                "0.8.0", "decision-unsupported-protocol", 1, fp('7'),
                CorrectnessGovernanceFeedback.GateDecision.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.WorkbookStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NOT_EVALUATED,
                List.of(), NOW.minusSeconds(30), NOW.plusSeconds(3600));

        assertThatThrownBy(() -> service.receiveFeedback(
                "publication-1", request, governanceIdentity()))
                .isInstanceOf(CorrectnessGovernanceException.class)
                .extracting("code")
                .isEqualTo("RG.CORRECTNESS.GOVERNANCE_FEEDBACK_INVALID");
    }

    @Test
    void rejectsUnknownFeedbackSourceAndPublicationDrift() {
        publication();
        CorrectnessGovernanceFeedbackRequest wrongSource = feedbackRequest(
                "OTHER_GOVERNOR", fp('1'));
        CorrectnessGovernanceFeedbackRequest drifted = feedbackRequest(
                "ANEKE_TOOL_STUDIO", fp('2'));

        assertThatThrownBy(() -> service.receiveFeedback(
                "publication-1", wrongSource, governanceIdentity()))
                .isInstanceOf(CorrectnessGovernanceException.class)
                .extracting("code")
                .isEqualTo("RG.CORRECTNESS.GOVERNANCE_FEEDBACK_INVALID");
        assertThatThrownBy(() -> service.receiveFeedback(
                "publication-1", drifted, governanceIdentity()))
                .isInstanceOf(CorrectnessGovernanceException.class)
                .extracting("code")
                .isEqualTo("RG.CORRECTNESS.PUBLICATION_DRIFT");
    }

    private void evidence(String caseId, String oracleId) {
        ExactAssetRef scenarioRef = ref("SCENARIO_DRAFT_SET", "scenario-1", '2');
        CorrectnessEvidenceCompanion companion = mock(CorrectnessEvidenceCompanion.class);
        when(companion.evidenceCompanionId()).thenReturn("evidence-1");
        when(companion.suiteRunId()).thenReturn("suite-run-1");
        when(companion.publicationRef()).thenReturn(new PublicationRef("publication-1", 1, fp('1')));
        when(companion.target()).thenReturn(target());
        when(companion.caseRefs()).thenReturn(List.of(
                new ExactCaseRef(scenarioRef, caseId, fp('3'))));
        when(companion.oracleRefs()).thenReturn(List.of(
                ref("BUSINESS_ORACLE", oracleId, '4')));
        StoredCorrectnessEvidenceCompanion stored = mock(StoredCorrectnessEvidenceCompanion.class);
        when(stored.companionFingerprint()).thenReturn(fp('e'));
        when(stored.companion()).thenReturn(companion);
        when(evidence.find(scope(), "suite-run-1")).thenReturn(Optional.of(stored));
    }

    private void publication() {
        CorrectnessPublication publication = mock(CorrectnessPublication.class);
        when(publication.publicationId()).thenReturn("publication-1");
        StoredCorrectnessPublication stored = mock(StoredCorrectnessPublication.class);
        when(stored.publication()).thenReturn(publication);
        when(stored.publicationFingerprint()).thenReturn(fp('1'));
        when(publications.findPublication(scope(), "publication-1"))
                .thenReturn(Optional.of(stored));
    }

    private CorrectnessGovernanceFeedbackRequest feedbackRequest(
            String source, String publicationFingerprint) {
        return new CorrectnessGovernanceFeedbackRequest(
                "feedback-1", publicationFingerprint, source,
                "1.1.0", "decision-1", 1, fp('9'),
                CorrectnessGovernanceFeedback.GateDecision.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.WorkbookStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.NOT_EVALUATED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NOT_EVALUATED,
                List.of(), NOW.minusSeconds(30), NOW.plusSeconds(3600));
    }

    private IntegrationRequestContext identity() {
        return identity("USER", "author-1", "CORRECTNESS_WRITE");
    }

    private IntegrationRequestContext governanceIdentity() {
        return identity("SERVICE", "aneke-sidecar", "GOVERNANCE_GATE_FEEDBACK");
    }

    private IntegrationRequestContext identity(String actorType, String actorId, String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", actorType, actorId,
                "", purpose, "correlation-1");
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg");
    }

    private ExactTargetRef target() {
        return new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('0'));
    }

    private ExactAssetRef ref(String kind, String id, char value) {
        return new ExactAssetRef(kind, id, 1, fp(value));
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
