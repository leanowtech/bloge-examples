package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationPlan;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedCompilationProvenance;
import com.leanowtech.bloge.gateway.authoring.scenario.ScenarioGovernedProvenanceMetadataCodec;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionRequest;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionResponse;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteExecutionService;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistrationRequest;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.visual.diagnostic.VisualDiagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CapabilityStudioGovernedCandidateServiceTest {

    private static final String SOURCE_MAP_FINGERPRINT = fingerprint('a');
    private static final String PROVENANCE_FINGERPRINT = fingerprint('b');
    private static final String COMPILATION_FINGERPRINT = fingerprint('c');
    private static final String PUBLICATION_FINGERPRINT = fingerprint('d');
    private static final String SUITE_FINGERPRINT = fingerprint('e');
    private static final String FIXTURE_FINGERPRINT = fingerprint('f');
    private static final String EVIDENCE_FINGERPRINT = fingerprint('1');

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final CapabilityStudioGovernedCompilationService compiler =
            mock(CapabilityStudioGovernedCompilationService.class);
    private final CapabilityStudioGovernedAssetPublisher publisher =
            mock(CapabilityStudioGovernedAssetPublisher.class);
    private final TestSuiteExecutionService executions = mock(TestSuiteExecutionService.class);
    private final IntegrationRequestContext publicationIdentity = identity("TEST_SCENARIO_PUBLISH");
    private final IntegrationRequestContext executionIdentity = identity("TEST_EXECUTION");

    private CapabilityStudioGovernedCandidateService service;

    @BeforeEach
    void setUp() {
        service = new CapabilityStudioGovernedCandidateService(
                mapper, compiler, publisher, executions);
    }

    @Test
    void bindsCompilationPublicationAndExistingSuiteRuntimeIntoOneReceipt() {
        CapabilityStudioGovernedCompilation compilation = compilation(true);
        CapabilityStudioGovernedAssetPublisher.Receipt publication = publication();
        when(compiler.compile(null, null, null, null, null)).thenReturn(compilation);
        when(publisher.publish(compilation, publicationIdentity)).thenReturn(publication);
        when(executions.execute(eq("suite-golden"), any(), eq(executionIdentity)))
                .thenReturn(response(evidence("candidate-001", Map.of(
                        "governedProvenanceFingerprint", PROVENANCE_FINGERPRINT,
                        "governedSourceMapFingerprint", SOURCE_MAP_FINGERPRINT,
                        "governedExactRefs", exactRefs()))));

        CapabilityStudioGovernedCandidateService.CandidateReceipt first = run("candidate-001");
        CapabilityStudioGovernedCandidateService.CandidateReceipt second = run("candidate-001");

        assertThat(first).isEqualTo(second);
        assertThat(first.publication()).isEqualTo(publication);
        assertThat(first.evidence().suiteRunId()).isEqualTo("suite-run-001");
        assertThat(first.evidence().status()).isEqualTo("PASSED");
        assertThat(first.evidence().provenanceFingerprint()).isEqualTo(PROVENANCE_FINGERPRINT);
        assertThat(first.evidence().sourceMapFingerprint()).isEqualTo(SOURCE_MAP_FINGERPRINT);
        assertThat(first.evidence().childRuns()).containsExactly(
                new CapabilityStudioGovernedCandidateService.ChildRunRef(
                        "case-golden", "child-run-001", "PASSED", "fixture-golden", 1,
                        FIXTURE_FINGERPRINT));
        assertThat(first.receiptFingerprint()).matches("sha256:[a-f0-9]{64}");

        ArgumentCaptor<TestSuiteExecutionRequest> request =
                ArgumentCaptor.forClass(TestSuiteExecutionRequest.class);
        verify(executions, org.mockito.Mockito.times(2)).execute(
                eq("suite-golden"), request.capture(), eq(executionIdentity));
        assertThat(request.getValue().suiteRef()).isEqualTo(
                new TestSuiteExecutionRequest.SuiteRef(
                        "suite-golden", 1, SUITE_FINGERPRINT));
        assertThat(request.getValue().clientRequestId()).isEqualTo("candidate-001");
        assertThat(request.getValue().metadata()).containsEntry(
                "publicationReceiptFingerprint", PUBLICATION_FINGERPRINT);
    }

    @Test
    void rejectsBlockedCompilationBeforePublishingOrExecuting() {
        when(compiler.compile(null, null, null, null, null)).thenReturn(compilation(false));

        assertThatThrownBy(() -> run("candidate-blocked"))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.COMPILATION_BLOCKED");
        verify(publisher, never()).publish(any(), any());
        verify(executions, never()).execute(any(), any(), any());
    }

    @Test
    void rejectsAResponseThatExecutedAnotherSuiteRevision() {
        CapabilityStudioGovernedCompilation compilation = compilation(true);
        when(compiler.compile(null, null, null, null, null)).thenReturn(compilation);
        when(publisher.publish(compilation, publicationIdentity)).thenReturn(publication());
        TestSuiteRunEvidence drifted = evidence(
                "candidate-suite-drift",
                Map.of("governedProvenanceFingerprint", PROVENANCE_FINGERPRINT,
                        "governedSourceMapFingerprint", SOURCE_MAP_FINGERPRINT,
                        "governedExactRefs", exactRefs()),
                new TestSuiteExecutionRequest.SuiteRef("suite-other", 2, fingerprint('9')),
                TestSuiteRunEvidence.Status.PASSED);
        when(executions.execute(eq("suite-golden"), any(), eq(executionIdentity)))
                .thenReturn(response(drifted));

        assertThatThrownBy(() -> run("candidate-suite-drift"))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.SUITE_REF_DRIFT");
    }

    @Test
    void rejectsMissingOrDriftedGovernedProvenanceInTerminalEvidence() {
        CapabilityStudioGovernedCompilation compilation = compilation(true);
        when(compiler.compile(null, null, null, null, null)).thenReturn(compilation);
        when(publisher.publish(compilation, publicationIdentity)).thenReturn(publication());
        when(executions.execute(eq("suite-golden"), any(), eq(executionIdentity)))
                .thenReturn(response(evidence("candidate-no-provenance", Map.of())));

        assertThatThrownBy(() -> run("candidate-no-provenance"))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.PROVENANCE_FINGERPRINT_DRIFT");

        when(executions.execute(eq("suite-golden"), any(), eq(executionIdentity)))
                .thenReturn(response(evidence("candidate-source-map-drift", Map.of(
                        "governedProvenanceFingerprint", PROVENANCE_FINGERPRINT,
                        "governedSourceMapFingerprint", fingerprint('8'),
                        "governedExactRefs", exactRefs()))));
        assertThatThrownBy(() -> run("candidate-source-map-drift"))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo(
                        "RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.SOURCE_MAP_FINGERPRINT_DRIFT");
    }

    @Test
    void rejectsRunningOrUnfingerprintedEvidence() {
        CapabilityStudioGovernedCompilation compilation = compilation(true);
        when(compiler.compile(null, null, null, null, null)).thenReturn(compilation);
        when(publisher.publish(compilation, publicationIdentity)).thenReturn(publication());
        TestSuiteRunEvidence running = evidence(
                "candidate-running",
                Map.of("governedProvenanceFingerprint", PROVENANCE_FINGERPRINT,
                        "governedSourceMapFingerprint", SOURCE_MAP_FINGERPRINT,
                        "governedExactRefs", exactRefs()),
                suiteRef(), TestSuiteRunEvidence.Status.RUNNING);
        when(executions.execute(eq("suite-golden"), any(), eq(executionIdentity)))
                .thenReturn(response(running));

        assertThatThrownBy(() -> run("candidate-running"))
                .isInstanceOf(CapabilityStudioGovernedCompilationException.class)
                .extracting("code")
                .isEqualTo("RG.CAPABILITY_STUDIO.GOVERNED_CANDIDATE.EVIDENCE_NOT_TERMINAL");
    }

    private CapabilityStudioGovernedCandidateService.CandidateReceipt run(String requestId) {
        return service.run(null, null, null, null, null, requestId,
                publicationIdentity, executionIdentity);
    }

    private CapabilityStudioGovernedCompilation compilation(boolean compiled) {
        TestSuite suite = suite();
        ScenarioGovernedCompilationPlan plan = new ScenarioGovernedCompilationPlan(
                ScenarioGovernedCompilationPlan.SCHEMA_VERSION,
                compiled,
                "dataset-golden",
                1,
                fingerprint('2'),
                fingerprint('3'),
                null,
                List.of(),
                compiled ? new TestSuiteRegistrationRequest("", suite) : null,
                compiled ? List.of() : List.of(
                        VisualDiagnostic.error("BLOCKED", "blocked", "/dataset")));
        return new CapabilityStudioGovernedCompilation(
                plan,
                new CapabilityStudioScenarioDatasetSourceMap(
                        null, null, SOURCE_MAP_FINGERPRINT, List.of()),
                COMPILATION_FINGERPRINT);
    }

    private TestSuite suite() {
        return new TestSuite(
                "",
                "suite-golden",
                1,
                new TestSuite.Target("OPERATOR", "tool-golden", fingerprint('4')),
                "INTERNAL",
                List.of(),
                new TestSuite.CoveragePolicy(0, List.of(), List.of(), List.of(), 0, false),
                new TestSuite.PromotionPolicy(false, 0, false),
                Map.of(
                        "governedProvenanceSchemaVersion",
                        "bloge.scenarioGovernedCompilationProvenance.v1",
                        "governedProvenanceFingerprint", PROVENANCE_FINGERPRINT,
                        "governedSourceMapFingerprint", SOURCE_MAP_FINGERPRINT,
                        "governedExactRefs", exactRefs()));
    }

    private static Map<String, Object> exactRefs() {
        ScenarioGovernedCompilationProvenance provenance =
                new ScenarioGovernedCompilationProvenance(
                        ScenarioGovernedCompilationProvenance.SCHEMA_VERSION,
                        SOURCE_MAP_FINGERPRINT,
                        List.of(new ScenarioGovernedCompilationProvenance.ExactRef(
                                "DATASET", "dataset-golden", 1, fingerprint('5'),
                                new ScenarioGovernedCompilationProvenance.Scope(
                                        "tenant-a", "org-a", "project-a", "test", "sg"),
                                "CAPABILITY_STUDIO_GOLDEN_PACK")));
        return ScenarioGovernedProvenanceMetadataCodec.encodeExactRefs(provenance);
    }

    private CapabilityStudioGovernedAssetPublisher.Receipt publication() {
        return new CapabilityStudioGovernedAssetPublisher.Receipt(
                COMPILATION_FINGERPRINT,
                SOURCE_MAP_FINGERPRINT,
                List.of(new CapabilityStudioGovernedAssetPublisher.ExactRef(
                        "FIXTURE_BUNDLE", "fixture-golden", 1, FIXTURE_FINGERPRINT)),
                new CapabilityStudioGovernedAssetPublisher.ExactRef(
                        "TEST_SUITE", "suite-golden", 1, SUITE_FINGERPRINT),
                PUBLICATION_FINGERPRINT);
    }

    private TestSuiteRunEvidence evidence(String clientRequestId, Map<String, Object> metadata) {
        return evidence(clientRequestId, metadata, suiteRef(), TestSuiteRunEvidence.Status.PASSED);
    }

    private TestSuiteRunEvidence evidence(
            String clientRequestId,
            Map<String, Object> metadata,
            TestSuiteExecutionRequest.SuiteRef suiteRef,
            TestSuiteRunEvidence.Status status) {
        return new TestSuiteRunEvidence(
                TestSuiteRunEvidence.SCHEMA_VERSION,
                "suite-run-001",
                clientRequestId,
                status,
                TestSuiteExecutionService.AUTHORIZED_PURPOSE,
                suiteRef,
                new TestSuite.Target("OPERATOR", "tool-golden", fingerprint('4')),
                Instant.parse("2026-08-18T00:00:00Z"),
                status == TestSuiteRunEvidence.Status.RUNNING
                        ? null : Instant.parse("2026-08-18T00:00:01Z"),
                List.of(new TestSuiteRunEvidence.CaseResult(
                        "case-golden", TestSuite.CaseType.GOLDEN,
                        new TestSuite.FixtureBundleRef(
                                "fixture-golden", 1, FIXTURE_FINGERPRINT),
                        TestSuiteRunEvidence.CaseStatus.PASSED,
                        "child-run-001",
                        TestRunEvidence.Status.PASSED,
                        TestRunEvidence.EvidenceClass.CERTIFIABLE,
                        1,
                        1,
                        "",
                        "")),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                TestSuiteRunEvidence.PromotionVerdict.notEvaluated(),
                List.of(),
                metadata);
    }

    private TestSuiteExecutionResponse response(TestSuiteRunEvidence evidence) {
        return new TestSuiteExecutionResponse(
                TestSuiteExecutionResponse.SCHEMA_VERSION_V1,
                evidence.suiteRunId(),
                EVIDENCE_FINGERPRINT,
                evidence);
    }

    private static TestSuiteExecutionRequest.SuiteRef suiteRef() {
        return new TestSuiteExecutionRequest.SuiteRef(
                "suite-golden", 1, SUITE_FINGERPRINT);
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg",
                "WORKLOAD", "capability-studio", "", purpose, "correlation-001",
                java.util.Set.of(), "RESTRICTED", "");
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }
}
