package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.TargetKind;
import com.leanowtech.bloge.gateway.testing.correctness.publication.CorrectnessPublicationRepository;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.publication.StoredCorrectnessPublicationAttempt;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrectnessRunServiceTest {

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private CorrectnessPreflightFacade preflight;
    private CorrectnessPublicationRepository publications;
    private TestSuiteExecutionService suites;
    private CorrectnessEvidenceCompanionFactory companions;
    private CorrectnessEvidenceRepository evidence;
    private CorrectnessRunService service;

    @BeforeEach
    void setUp() {
        preflight = mock(CorrectnessPreflightFacade.class);
        publications = mock(CorrectnessPublicationRepository.class);
        suites = mock(TestSuiteExecutionService.class);
        companions = mock(CorrectnessEvidenceCompanionFactory.class);
        evidence = mock(CorrectnessEvidenceRepository.class);
        service = new CorrectnessRunService(
                preflight, publications, suites, companions, evidence, mapper);
    }

    @Test
    void staleReviewedPreflightRejectsBeforePublicationOrSuiteExecution() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('a'));
        when(preflight.preflight(any(), eq(identity())))
                .thenReturn(report(request.selection(), fp('b'), List.of()));

        assertThatThrownBy(() -> service.execute(request, identity()))
                .isInstanceOf(CorrectnessRunException.class)
                .extracting(value -> ((CorrectnessRunException) value).code())
                .isEqualTo("RG.CORRECTNESS.PREFLIGHT_STALE");
        verify(publications, never()).findPublication(any(), any());
        verify(suites, never()).execute(any(), any(), any());
    }

    @Test
    void safetyBlockerRejectsBeforePublicationOrSuiteExecution() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('a'));
        when(preflight.preflight(any(), eq(identity()))).thenReturn(report(
                request.selection(), fp('a'),
                List.of(new CorrectnessPreflightReport.Blocker(
                        "RG.TEST.REAL_CALL_BLOCKED", "preflight.blocked", "case-1"))));

        assertThatThrownBy(() -> service.execute(request, identity()))
                .isInstanceOf(CorrectnessRunException.class)
                .extracting(value -> ((CorrectnessRunException) value).code())
                .isEqualTo("RG.CORRECTNESS.PREFLIGHT_BLOCKED");
        verify(publications, never()).findPublication(any(), any());
        verify(suites, never()).execute(any(), any(), any());
    }

    @Test
    void allSelectionDelegatesToExistingSuiteRunnerWithoutPersistingRawClientKey() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('a'));
        CorrectnessPreflightReport reviewed = report(request.selection(), fp('a'), List.of());
        when(preflight.preflight(any(), eq(identity()))).thenReturn(reviewed);
        exactPublicationClosure(request);
        TestSuiteExecutionResponse running = suiteResponse(
                "suite-run-1", TestSuiteRunEvidence.Status.RUNNING);
        when(suites.execute(eq("suite-1"), any(), eq(identity()))).thenReturn(running);

        CorrectnessRunResponse response = service.execute(request, identity());

        assertThat(response.status()).isEqualTo(CorrectnessRunResponse.Status.RUNNING);
        ArgumentCaptor<TestSuiteExecutionRequest> captured =
                ArgumentCaptor.forClass(TestSuiteExecutionRequest.class);
        verify(suites).execute(eq("suite-1"), captured.capture(), eq(identity()));
        assertThat(captured.getValue().clientRequestId())
                .startsWith("correctness-run-")
                .doesNotContain(request.clientRequestId());
        assertThat(mapper.valueToTree(captured.getValue()).toString())
                .doesNotContain(request.clientRequestId());
        verify(companions, never()).create(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void selectedCasesDelegateExactlyToTheSameSuiteRunner() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.SELECTED,
                List.of("case-b", "case-a"), fp('a'));
        when(preflight.preflight(any(), eq(identity())))
                .thenReturn(report(request.selection(), fp('a'), List.of()));
        exactPublicationClosure(request);
        TestSuiteExecutionResponse running = suiteResponse(
                "suite-run-2", TestSuiteRunEvidence.Status.RUNNING);
        when(suites.executeSelected(eq("suite-1"), any(), any(), eq(identity())))
                .thenReturn(running);

        service.execute(request, identity());

        verify(suites).executeSelected(
                eq("suite-1"), any(), eq(List.of("case-a", "case-b")), eq(identity()));
        verify(suites, never()).execute(any(), any(), any());
    }

    @Test
    void legacyPublicationWithoutSourceMapBindingCannotStartBusinessExecution() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('a'));
        when(preflight.preflight(any(), eq(identity())))
                .thenReturn(report(request.selection(), fp('a'), List.of()));
        when(publications.findPublication(any(), eq("publication-1")))
                .thenReturn(Optional.of(mock(StoredCorrectnessPublication.class)));
        when(publications.findCommittedAttemptForPublication(any(), eq("publication-1")))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.execute(request, identity()))
                .isInstanceOf(CorrectnessRunException.class)
                .extracting(value -> ((CorrectnessRunException) value).code())
                .isEqualTo("RG.CORRECTNESS.PUBLICATION_TRACEABILITY_UNAVAILABLE");
        verify(suites, never()).execute(any(), any(), any());
    }

    @Test
    void terminalSuiteResultCreatesAndPersistsOneEvidenceCompanion() {
        CorrectnessRunRequest request = request(
                CorrectnessRunRequest.Selection.Mode.ALL, List.of(), fp('a'));
        CorrectnessPreflightReport reviewed = report(request.selection(), fp('a'), List.of());
        when(preflight.preflight(any(), eq(identity()))).thenReturn(reviewed);
        PublicationClosure closure = exactPublicationClosure(request);
        TestSuiteExecutionResponse terminal = suiteResponse(
                "suite-run-3", TestSuiteRunEvidence.Status.PASSED);
        when(suites.execute(eq("suite-1"), any(), eq(identity()))).thenReturn(terminal);
        StoredCorrectnessEvidenceCompanion stored = stored("suite-run-3");
        when(evidence.find(any(), eq("suite-run-3"))).thenReturn(Optional.empty());
        when(companions.create(eq(request), eq(reviewed), eq(closure.publication()),
                eq(closure.attempt()), eq(terminal), any(), eq(identity())))
                .thenReturn(stored);
        when(evidence.saveIfAbsent(any(), eq(stored))).thenReturn(stored);

        CorrectnessRunResponse response = service.execute(request, identity());

        assertThat(response.status())
                .isEqualTo(CorrectnessRunResponse.Status.EVIDENCE_AVAILABLE);
        assertThat(response.evidenceCompanion()).isEqualTo(stored);
        verify(evidence).saveIfAbsent(any(), eq(stored));
    }

    @Test
    void evidenceReadIsScopeExact() {
        StoredCorrectnessEvidenceCompanion stored = stored("suite-run-4");
        when(evidence.find(any(), eq("suite-run-4"))).thenReturn(Optional.of(stored));

        assertThat(service.findEvidence("suite-run-4", identity())).isEqualTo(stored);
        verify(evidence).find(eq(new com.leanowtech.bloge.gateway.testing.correctness.domain
                .CorrectnessProtocol.EnterpriseScope(
                "tenant-a", "org-a", "project-a", "test", "sg")), eq("suite-run-4"));
    }

    private PublicationClosure exactPublicationClosure(CorrectnessRunRequest request) {
        StoredCorrectnessPublication publication = mock(StoredCorrectnessPublication.class);
        StoredCorrectnessPublicationAttempt attempt =
                mock(StoredCorrectnessPublicationAttempt.class);
        when(publications.findPublication(any(), eq(request.publicationRef().publicationId())))
                .thenReturn(Optional.of(publication));
        when(publications.findCommittedAttemptForPublication(
                any(), eq(request.publicationRef().publicationId())))
                .thenReturn(Optional.of(attempt));
        return new PublicationClosure(publication, attempt);
    }

    private CorrectnessRunRequest request(
            CorrectnessRunRequest.Selection.Mode mode,
            List<String> caseIds,
            String preflightFingerprint
    ) {
        return new CorrectnessRunRequest(
                "", new CorrectnessRunRequest.PublicationRef(
                "publication-1", 1, fp('1')),
                new CorrectnessRunRequest.Selection(mode, caseIds, fp('2')),
                preflightFingerprint, "raw-client-request-id",
                CorrectnessRunRequest.Strategy.COLLECT_ALL);
    }

    private CorrectnessPreflightReport report(
            CorrectnessRunRequest.Selection selection,
            String preflightFingerprint,
            List<CorrectnessPreflightReport.Blocker> blockers
    ) {
        return new CorrectnessPreflightReport(
                "", new CorrectnessRunRequest.PublicationRef(
                "publication-1", 1, fp('1')),
                new ExactTargetRef(TargetKind.GRAPH, "graph-1", 1, fp('3')),
                new ExactAssetRef("TEST_SUITE", "suite-1", 1, fp('4')),
                selection, CorrectnessPreflightReport.ProofLevel.SIMULATED_BUSINESS,
                List.of(), new CorrectnessPreflightReport.RiskSummary(
                0, 1, 0, 0, 0, 0, 0, 0, 0, true, List.of("READ")),
                blockers, preflightFingerprint);
    }

    private TestSuiteExecutionResponse suiteResponse(
            String suiteRunId,
            TestSuiteRunEvidence.Status status
    ) {
        TestSuiteRunEvidence aggregate = mock(TestSuiteRunEvidence.class);
        TestSuiteExecutionResponse response = mock(TestSuiteExecutionResponse.class);
        when(aggregate.status()).thenReturn(status);
        when(response.suiteRunId()).thenReturn(suiteRunId);
        when(response.evidence()).thenReturn(aggregate);
        when(response.evidenceFingerprint()).thenReturn(fp('5'));
        return response;
    }

    private StoredCorrectnessEvidenceCompanion stored(String suiteRunId) {
        StoredCorrectnessEvidenceCompanion stored =
                mock(StoredCorrectnessEvidenceCompanion.class);
        CorrectnessEvidenceCompanion companion = mock(CorrectnessEvidenceCompanion.class);
        when(stored.companion()).thenReturn(companion);
        when(companion.suiteRunId()).thenReturn(suiteRunId);
        return stored;
    }

    private IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "USER", "author-1", "", "TEST_EXECUTION", "corr-1",
                Set.of(), "CONFIDENTIAL", "");
    }

    private String fp(char digit) {
        return "sha256:" + String.valueOf(digit).repeat(64);
    }

    private record PublicationClosure(
            StoredCorrectnessPublication publication,
            StoredCorrectnessPublicationAttempt attempt
    ) { }
}
