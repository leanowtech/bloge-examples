package com.leanowtech.bloge.gateway.testing.correctness.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessVerdict;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceFeedback;
import com.leanowtech.bloge.gateway.testing.correctness.governance.CorrectnessGovernanceRepository;
import com.leanowtech.bloge.gateway.testing.correctness.governance.StoredCorrectnessGovernanceFeedback;
import com.leanowtech.bloge.gateway.testing.correctness.run.CorrectnessRunRequest.PublicationRef;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Components;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.Coordinate;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceComponentSource.PageRequest;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.Availability;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CasePage;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CommandPolicy;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.CoverageSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.FixtureCatalogSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.OracleAssertionSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.PublicationSummary;
import com.leanowtech.bloge.gateway.testing.correctness.workspace.CorrectnessWorkspaceProjection.ReviewSummary;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GovernanceFeedbackCorrectnessWorkspaceComponentSourceTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void mapsBlockingFeedbackToGateAndRemediation() {
        CorrectnessGovernanceRepository repository = mock(CorrectnessGovernanceRepository.class);
        when(repository.findLatestFeedback(any(), any(), any()))
                .thenReturn(Optional.of(feedback(
                        CorrectnessGovernanceFeedback.GateDecision.BLOCKED,
                        NOW.plusSeconds(3600))));
        var source = source(repository);

        Components result = source.load(coordinate(), page());

        assertThat(result.verdict().gate()).isEqualTo(CorrectnessVerdict.GateVerdict.BLOCKED);
        assertThat(result.verdict().reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("ANEKE_WORKBOOK_REQUIRED");
        assertThat(result.verdict().nextActions())
                .extracting(CorrectnessVerdict.Remediation::command)
                .contains("OPEN_GOVERNANCE_FEEDBACK");
        assertThat(result.capabilities()).contains("CORRECTNESS_GOVERNANCE_FEEDBACK_V1");
    }

    @Test
    void externalAcceptanceCannotForgeMissingLocalProof() {
        CorrectnessGovernanceRepository repository = mock(CorrectnessGovernanceRepository.class);
        when(repository.findLatestFeedback(any(), any(), any()))
                .thenReturn(Optional.of(feedback(
                        CorrectnessGovernanceFeedback.GateDecision.ACCEPTED,
                        NOW.plusSeconds(3600))));

        Components result = source(repository).load(coordinate(), page());

        assertThat(result.verdict().gate())
                .isEqualTo(CorrectnessVerdict.GateVerdict.NOT_EVALUATED);
        assertThat(result.verdict().execution())
                .isEqualTo(CorrectnessVerdict.ExecutionVerdict.NOT_RUN);
        assertThat(result.verdict().assertions())
                .isEqualTo(CorrectnessVerdict.AssertionVerdict.NONE);
    }

    @Test
    void expiredFeedbackRequiresRefreshInsteadOfRemainingAuthoritative() {
        CorrectnessGovernanceRepository repository = mock(CorrectnessGovernanceRepository.class);
        when(repository.findLatestFeedback(any(), any(), any()))
                .thenReturn(Optional.of(feedback(
                        CorrectnessGovernanceFeedback.GateDecision.ACCEPTED,
                        NOW.minusSeconds(1))));

        Components result = source(repository).load(coordinate(), page());

        assertThat(result.verdict().gate()).isEqualTo(CorrectnessVerdict.GateVerdict.REVIEW);
        assertThat(result.verdict().reasons()).extracting(CorrectnessVerdict.Reason::code)
                .contains("ANEKE_FEEDBACK_EXPIRED");
    }

    private GovernanceFeedbackCorrectnessWorkspaceComponentSource source(
            CorrectnessGovernanceRepository repository) {
        CorrectnessWorkspaceComponentSource delegate = (coordinate, page) -> components(page);
        return new GovernanceFeedbackCorrectnessWorkspaceComponentSource(
                delegate, repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Components components(PageRequest page) {
        CorrectnessVerdict verdict = new CorrectnessVerdict(
                CorrectnessVerdict.ExecutionVerdict.NOT_RUN,
                CorrectnessVerdict.AssertionVerdict.NONE,
                CorrectnessVerdict.CoverageVerdict.NOT_EVALUATED,
                CorrectnessVerdict.EvidenceVerdict.NONE,
                CorrectnessVerdict.GateVerdict.NOT_EVALUATED,
                CorrectnessVerdict.ProofLevel.STRUCTURAL, List.of(), List.of());
        return new Components(
                CoverageSummary.unavailable(), OracleAssertionSummary.unavailable(),
                new CasePage(Availability.UNAVAILABLE, null, 0, List.of(), "",
                        page.queryFingerprint()),
                FixtureCatalogSummary.unavailable(), ReviewSummary.empty(),
                new PublicationSummary(
                        new ExactAssetRef(
                                "CORRECTNESS_PUBLICATION", "publication-1", 1, fp('1')),
                        "COMMITTED", NOW.minusSeconds(300)),
                null, verdict, List.of(), List.of(), CommandPolicy.readOnly());
    }

    private StoredCorrectnessGovernanceFeedback feedback(
            CorrectnessGovernanceFeedback.GateDecision decision, Instant expiresAt) {
        List<CorrectnessGovernanceFeedback.Finding> findings =
                decision == CorrectnessGovernanceFeedback.GateDecision.BLOCKED
                        ? List.of(new CorrectnessGovernanceFeedback.Finding(
                        "finding-1", CorrectnessGovernanceFeedback.Severity.BLOCKING,
                        CorrectnessGovernanceFeedback.Category.WORKBOOK,
                        "WORKBOOK_REQUIRED", "Workbook required.", "Create workbook.", ""))
                        : List.of();
        CorrectnessGovernanceFeedback feedback = new CorrectnessGovernanceFeedback(
                "", "feedback-1", scope(),
                new PublicationRef("publication-1", 1, fp('1')),
                "ANEKE_TOOL_STUDIO", "toolStudio.resourceGatewayProtocol.v1",
                "decision-1", 1, fp('2'), decision,
                CorrectnessGovernanceFeedback.WorkbookStatus.CURRENT,
                CorrectnessGovernanceFeedback.OwnerApprovalStatus.APPROVED,
                CorrectnessGovernanceFeedback.BreakingMigrationStatus.NONE,
                findings, NOW.minusSeconds(3600), expiresAt, NOW.minusSeconds(3500),
                "aneke", "correlation-1");
        return StoredCorrectnessGovernanceFeedback.verified(mapper, feedback);
    }

    private Coordinate coordinate() {
        return new Coordinate(
                scope(), new ExactAssetRef("DEFINITION", "definition-1", 1, fp('3')),
                new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('4')), null);
    }

    private PageRequest page() {
        return new PageRequest("", 100, fp('5'));
    }

    private EnterpriseScope scope() {
        return new EnterpriseScope("tenant-a", "org-a", "project-a", "test", "sg");
    }

    private static String fp(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
