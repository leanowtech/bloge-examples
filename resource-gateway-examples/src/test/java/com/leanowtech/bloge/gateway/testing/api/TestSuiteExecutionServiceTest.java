package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionSubjects;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.Kind;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoveragePolicy;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV4;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.planning.TestBoundaryCasePlanner;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestSuiteExecutionServiceTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String SUITE = "sha256:" + "b".repeat(64);
    private static final String FIXTURE_1 = "sha256:" + "c".repeat(64);
    private static final String FIXTURE_2 = "sha256:" + "d".repeat(64);

    private TestSuiteRegistryService registry;
    private TestExecutionApiService executions;
    private InMemorySuiteRunRepository runRepository;
    private TestSecurityEventRepository securityEvents;
    private TestRuntimeAdmissionGate admissions;
    private AdmissionGuard admissionGuard;
    private TestSuiteExecutionService service;
    private IntegrationRequestContext identity;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        registry = mock(TestSuiteRegistryService.class);
        executions = mock(TestExecutionApiService.class);
        runRepository = new InMemorySuiteRunRepository();
        securityEvents = mock(TestSecurityEventRepository.class);
        admissions = mock(TestRuntimeAdmissionGate.class);
        admissionGuard = mock(AdmissionGuard.class);
        when(admissions.admit(any(), any())).thenReturn(admissionGuard);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        service = new TestSuiteExecutionService(registry, executions, runRepository,
                objectMapper, securityEvents, Duration.ofDays(30),
                TestSuiteRunLeaseCoordinator.passive(Duration.ofMinutes(5)),
                new TestSuiteRunAttestationService(objectMapper,
                        new InMemoryVisualEvidenceSigner()), admissions);
        when(executions.verifyEvidence(any())).thenReturn(true);
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
        when(executions.admissionSubjects(any(), any())).thenReturn(
                new AdmissionSubjects(Set.of("graph-operator"), Set.of("resource-a")));
    }

    @Test
    void collectAllExecutesExactCasesAndComputesCoverageAndPromotionVerdict() {
        StoredTestSuite stored = storedSuite();
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY",
                                "", "", TestRunEvidence.Status.PASSED,
                                TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("suite-a", request("request-a",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        assertThat(result.evidence().caseResults()).extracting(TestSuiteRunEvidence.CaseResult::runId)
                .containsExactly("run-golden", "run-negative");
        assertThat(result.evidence().coverage().status())
                .isEqualTo(TestSuiteRunEvidence.CoverageStatus.SATISFIED);
        assertThat(result.evidence().coverage().missingInvocationSiteIds()).isEmpty();
        assertThat(result.evidence().coverage().missingEdgeTransfers()).isEmpty();
        assertThat(result.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
        assertThat(result.evidenceFingerprint()).startsWith("sha256:");
        assertThat(result.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION);
        assertThat(result.attestation().terminallyVerifiable()).isTrue();
        assertThat(result.attestation().childEvidenceRefs())
                .extracting(TestSuiteRunAttestation.ChildEvidenceRef::runId)
                .containsExactly("run-golden", "run-negative");
        assertThat(result.evidence().metadata())
                .containsKey("requestMetadataFingerprint")
                .doesNotContainKey("requestMetadata");

        TestSuiteExecutionResponse retry = service.execute("suite-a", request("request-a",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        assertThat(retry).isEqualTo(result);
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(identity));
        ArgumentCaptor<AdmissionIntent> admission = ArgumentCaptor.forClass(AdmissionIntent.class);
        verify(admissions).admit(eq(identity), admission.capture());
        assertThat(admission.getValue()).satisfies(intent -> {
            assertThat(intent.kind()).isEqualTo(Kind.SUITE);
            assertThat(intent.stableRequestKey()).isEqualTo("request-a");
            assertThat(intent.suiteRef()).isEqualTo("suite-a");
            assertThat(intent.operatorRefs()).containsExactly("graph-operator");
            assertThat(intent.dependencyRefs()).containsExactly("resource-a");
        });
        verify(admissionGuard).checkpoint();
        verify(admissionGuard).close();
        assertThat(runRepository.records).hasSize(1);
    }

    @Test
    void ordinaryRunnerFailsClosedForMaterializedMutationSuites() {
        StoredTestSuite stored = storedMutationSuite();
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);

        assertThatThrownBy(() -> service.execute("suite-a", request("mutation-request",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    var problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code())
                            .isEqualTo("RG.TEST.MUTATION_SUITE_EXECUTION_UNAVAILABLE");
                });

        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
        verify(admissions, never()).admit(any(), any());
        assertThat(runRepository.records).isEmpty();
    }

    @Test
    void propertyV4ExecutesFrozenShrinkClosureAndExportsPathLocalCounterexampleEvidence() {
        StoredTestSuite stored = storedPropertySuite();
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(propertyResponse("property-run-root", "property-001",
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(propertyResponse("property-run-shrink",
                        "property-001-shrink-001", TestRunEvidence.Status.ASSERTION_FAILED));

        TestSuiteExecutionResponse response = service.execute("suite-a", request("property-request",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteExecutionResponse retry = service.execute("suite-a", request("property-request",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteEvidenceBundle bundle = service.evidenceBundle(response.suiteRunId(), identity);

        assertThat(retry).isEqualTo(response);
        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V5);
        assertThat(response.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V4);
        assertThat(response.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV4.class,
                evidence -> {
                    assertThat(evidence.status())
                            .isEqualTo(TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES);
                    assertThat(evidence.propertyCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV4.PropertyCoverageStatus.COUNTEREXAMPLE);
                    assertThat(evidence.propertyCoverage().minimalObservedCounterexamples())
                            .singleElement().satisfies(counterexample -> {
                                assertThat(counterexample.caseId())
                                        .isEqualTo("property-001-shrink-001");
                                assertThat(counterexample.minimalityScope())
                                        .isEqualTo(TestSuiteRunEvidenceV4.MINIMALITY_SCOPE);
                                assertThat(counterexample.globallyMinimal()).isFalse();
                            });
                    assertThat(evidence.propertyCoverage().counterexampleCases()).isEqualTo(2);
                    assertThat(evidence.propertyCoverage().allCasesCompleted()).isTrue();
                });
        assertThat(response.attestation().childEvidenceRefs())
                .extracting(TestSuiteRunAttestation.ChildEvidenceRef::runId)
                .containsExactly("property-run-root", "property-run-shrink");
        assertThat(bundle.schemaVersion()).isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V4);
        assertThat(bundle.evidence()).isInstanceOf(TestSuiteRunEvidenceV4.class);
        assertThat(objectMapper.valueToTree(bundle).toString())
                .doesNotContain("generated-root", "generated-shrink");
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(identity));
    }

    @Test
    void propertyFailFastCompletesCurrentShrinkPathBeforeSkippingTheNextTrial() {
        StoredTestSuite stored = storedTwoTrialPropertySuite();
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(propertyResponse("property-run-root", "property-001",
                        TestRunEvidence.Status.ASSERTION_FAILED))
                .thenReturn(propertyResponse("property-run-shrink",
                        "property-001-shrink-001", TestRunEvidence.Status.ASSERTION_FAILED));

        TestSuiteExecutionResponse response = service.execute("suite-a", request("property-fast",
                TestSuiteExecutionRequest.Strategy.FAIL_FAST), identity);

        assertThat(response.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV4.class,
                evidence -> {
                    assertThat(evidence.status()).isEqualTo(TestSuiteRunEvidence.Status.PARTIAL);
                    assertThat(evidence.caseResults())
                            .extracting(TestSuiteRunEvidence.CaseResult::status)
                            .containsExactly(TestSuiteRunEvidence.CaseStatus.FAILED,
                                    TestSuiteRunEvidence.CaseStatus.FAILED,
                                    TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED,
                                    TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
                    assertThat(evidence.propertyTrialResults())
                            .extracting(TestSuiteRunEvidenceV4.PropertyTrialResult::status)
                            .containsExactly(
                                    TestSuiteRunEvidenceV4.PropertyTrialStatus.COUNTEREXAMPLE,
                                    TestSuiteRunEvidenceV4.PropertyTrialStatus.INCOMPLETE);
                    assertThat(evidence.propertyCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV4.PropertyCoverageStatus.INCOMPLETE);
                });
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(identity));
    }

    @Test
    void terminalSuiteExportsPortablePayloadFreeEvidenceBundle() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));
        TestSuiteExecutionResponse run = service.execute("suite-a", request("portable-evidence",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        TestSuiteEvidenceBundle bundle = service.evidenceBundle(run.suiteRunId(), identity);

        assertThat(bundle.schemaVersion()).isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION);
        assertThat(bundle.payloadPolicy()).isEqualTo(TestSuiteEvidenceBundle.PayloadPolicy.OMITTED);
        assertThat(bundle.bundleFingerprint()).startsWith("sha256:");
        assertThat(bundle.attestation()).isEqualTo(run.attestation());
        assertThat(objectMapper.valueToTree(bundle).toString())
                .doesNotContain("orderId", "nightly", "requestMetadata\"");
    }

    @Test
    void semanticV2SuiteProducesV3ResponseSignedEvidenceAndPortableBundle() {
        StoredTestSuite structural = storedSuite();
        TestSuite base = (TestSuite) structural.suite();
        TestSuiteV2 semantic = new TestSuiteV2("", base.suiteId(), base.revision(), base.target(),
                base.classification(), base.cases(), base.coveragePolicy(), new SemanticCoveragePolicy(
                List.of(new SemanticCoveragePolicy.BranchRequirement("fetch-output",
                        SemanticCoveragePolicy.Kind.BRANCH_TRANSFERRED,
                        "/root/fetch#PRIMARY", "/root/output#PRIMARY"))),
                base.promotionPolicy(), base.metadata());
        when(registry.find("suite-a", 3, identity)).thenReturn(new StoredTestSuite("", "tenant-a",
                "test", "suite-a", 3, SUITE, semantic, Instant.now(), "runner"));
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse response = service.execute("suite-a", request("semantic-v2",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteEvidenceBundle bundle = service.evidenceBundle(response.suiteRunId(), identity);

        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V3);
        assertThat(response.evidence()).isInstanceOf(TestSuiteRunEvidenceV2.class);
        assertThat(((TestSuiteRunEvidenceV2) response.evidence()).semanticCoverage().status())
                .isEqualTo(SemanticCoverageVerdict.Status.SATISFIED);
        assertThat(response.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V2);
        assertThat(bundle.schemaVersion()).isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V2);
        assertThat(bundle.evidence()).isInstanceOf(TestSuiteRunEvidenceV2.class);

        TestSuiteRunEvidenceV2 mislabeled = withSchema(
                (TestSuiteRunEvidenceV2) response.evidence(), TestSuiteRunEvidence.SCHEMA_VERSION);
        assertThatThrownBy(() -> new TestSuiteExecutionResponse(
                TestSuiteExecutionResponse.SCHEMA_VERSION_V3, response.suiteRunId(),
                response.evidenceFingerprint(), mislabeled, response.attestation()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("generations must match");
        assertThatThrownBy(() -> new TestSuiteEvidenceBundle(
                TestSuiteEvidenceBundle.SCHEMA_VERSION_V2, bundle.suiteRunId(),
                bundle.bundleFingerprint(), bundle.payloadPolicy(), bundle.attestation(), mislabeled))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
    }

    @Test
    void schemaAdmissionV3RevalidatesWithoutInvokingBusinessTargetAndExportsEvidence() {
        AdmissionScenario scenario = admissionScenario(3);
        when(registry.find("suite-a", 3, identity)).thenReturn(scenario.stored());
        when(executions.resolveSchemaAdmissionTarget(any(), eq(identity))).thenReturn(
                scenario.current());

        TestSuiteExecutionResponse response = service.execute("suite-a", request("admission-v3",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteExecutionResponse retry = service.execute("suite-a", request("admission-v3",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteEvidenceBundle bundle = service.evidenceBundle(response.suiteRunId(), identity);

        assertThat(retry).isEqualTo(response);
        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V4);
        assertThat(response.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV3.class,
                evidence -> {
                    assertThat(evidence.status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
                    assertThat(evidence.admissionResults())
                            .extracting(TestSuiteRunEvidenceV3.AdmissionCaseResult::status)
                            .containsOnly(TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED);
                    assertThat(evidence.admissionCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.SATISFIED);
                    assertThat(evidence.coverage())
                            .isEqualTo(TestSuiteRunEvidence.CoverageVerdict.notEvaluated());
                    assertThat(evidence.promotion().status())
                            .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
                    assertThat(evidence.promotion().reasons()).containsExactlyInAnyOrder(
                            TestSuiteRunEvidenceV3.SCHEMA_ADMISSION_ONLY,
                            TestSuiteRunEvidenceV3.BUSINESS_EXECUTION_NOT_PERFORMED);
                    assertThat(evidence.metadata()).containsEntry("businessTargetInvoked", false)
                            .containsEntry("childRunCount", 0);
                    assertThat(evidence.caseResults())
                            .allSatisfy(result -> assertThat(result.runId()).isBlank());
                });
        assertThat(response.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(response.attestation().childEvidenceRefs()).isEmpty();
        assertThat(bundle.schemaVersion()).isEqualTo(TestSuiteEvidenceBundle.SCHEMA_VERSION_V3);
        assertThat(bundle.evidence()).isInstanceOf(TestSuiteRunEvidenceV3.class);
        assertThat(objectMapper.valueToTree(bundle).toString()).doesNotContain("\"input\":");

        ArgumentCaptor<AdmissionIntent> admissionIntent =
                ArgumentCaptor.forClass(AdmissionIntent.class);
        verify(admissions).admit(eq(identity), admissionIntent.capture());
        assertThat(admissionIntent.getValue().operatorRefs()).isEmpty();
        assertThat(admissionIntent.getValue().dependencyRefs()).isEmpty();
        verify(executions).resolveSchemaAdmissionTarget(any(), eq(identity));
        verify(executions, never()).admissionSubjects(any(), any());
        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
        verify(executions, never()).executeAdmittedSuiteOperatorCase(any(), any(), any());
        verify(executions, never()).describeGraphTarget(any(), any());
        verify(executions, never()).describeOperatorTarget(any(), any());
        assertThat(runRepository.records).hasSize(1);
    }

    @Test
    void schemaAdmissionTerminalStoreFailurePreservesSignedV3IncompleteEvidence() {
        AdmissionScenario scenario = admissionScenario(2);
        when(registry.find("suite-a", 3, identity)).thenReturn(scenario.stored());
        when(executions.resolveSchemaAdmissionTarget(any(), eq(identity))).thenReturn(
                scenario.current());
        runRepository.failNextTerminalUpdate = true;

        TestSuiteExecutionResponse response = service.execute("suite-a", request(
                "admission-terminal-store-failure",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        assertThat(response.schemaVersion()).isEqualTo(TestSuiteExecutionResponse.SCHEMA_VERSION_V4);
        assertThat(response.evidence()).isInstanceOfSatisfying(TestSuiteRunEvidenceV3.class,
                evidence -> {
                    assertThat(evidence.status())
                            .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
                    assertThat(evidence.admissionCoverage().status())
                            .isEqualTo(TestSuiteRunEvidenceV3.AdmissionCoverageStatus.INCOMPLETE);
                    assertThat(evidence.admissionResults())
                            .extracting(TestSuiteRunEvidenceV3.AdmissionCaseResult::status)
                            .containsOnly(TestSuiteRunEvidenceV3.AdmissionCaseStatus.MATCHED);
                    assertThat(evidence.promotion().reasons())
                            .contains("EVIDENCE_INCOMPLETE");
                });
        assertThat(response.attestation().schemaVersion())
                .isEqualTo(TestSuiteRunAttestation.SCHEMA_VERSION_V3);
        assertThat(response.attestation().terminallyVerifiable()).isTrue();
        assertThat(response.attestation().childEvidenceRefs()).isEmpty();
        assertThat(runRepository.records.get(response.suiteRunId()).evidence())
                .isInstanceOf(TestSuiteRunEvidenceV3.class);
        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
    }

    @Test
    void tamperedPersistedAggregateIsAuditedAndRejectedOnRead() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));
        TestSuiteExecutionResponse run = service.execute("suite-a", request("tamper-read",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        TestSuiteRunRecord stored = runRepository.records.get(run.suiteRunId());
        TestSuiteRunEvidence evidence = (TestSuiteRunEvidence) stored.evidence();
        Map<String, Object> metadata = new LinkedHashMap<>(evidence.metadata());
        metadata.put("tampered", true);
        TestSuiteRunEvidence altered = new TestSuiteRunEvidence("", evidence.suiteRunId(),
                evidence.clientRequestId(), evidence.status(), evidence.executionPurpose(),
                evidence.suiteRef(), evidence.target(), evidence.startedAt(), evidence.completedAt(),
                evidence.caseResults(), evidence.coverage(), evidence.promotion(), evidence.diagnostics(),
                metadata);
        runRepository.records.put(run.suiteRunId(), new TestSuiteRunRecord(stored.suiteRunId(),
                stored.clientRequestId(), stored.requestFingerprint(), stored.tenantId(),
                stored.organizationId(), stored.projectId(), stored.environmentId(), stored.actorId(),
                stored.classification(), stored.evidenceFingerprint(), altered, stored.attestation(),
                stored.createdAt(), stored.expiresAt()));

        assertThatThrownBy(() -> service.find(run.suiteRunId(), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.TEST.SUITE_STORAGE_INTEGRITY_INVALID"));
        verify(securityEvents).append(org.mockito.ArgumentMatchers.argThat(event ->
                event.eventType().equals("TEST_SUITE_STORAGE_INTEGRITY_INVALID")));
    }

    @Test
    void unavailableInitialAttestationPreventsAnyCaseOrCheckpointWrite() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        TestSuiteExecutionService unavailable = new TestSuiteExecutionService(
                registry, executions, runRepository, objectMapper, securityEvents,
                Duration.ofDays(30), TestSuiteRunLeaseCoordinator.passive(Duration.ofMinutes(5)),
                new TestSuiteRunAttestationService(objectMapper,
                        com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner.unavailable()));

        assertThatThrownBy(() -> unavailable.execute("suite-a", request("no-signer",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.TEST.SUITE_ATTESTATION_UNAVAILABLE"));
        assertThat(runRepository.records).isEmpty();
        verifyNoInteractions(executions);
    }

    @Test
    void unsignedOrTamperedChildEvidenceCannotSatisfySuitePromotion() {
        StoredTestSuite stored = storedSuite();
        TestExecutionApiResponse child = response("run-golden", "golden", "/root/fetch#PRIMARY",
                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE);
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity))).thenReturn(child);
        when(executions.verifyEvidence(child)).thenReturn(false);

        TestSuiteExecutionResponse result = service.execute("suite-a", request("request-unsigned",
                TestSuiteExecutionRequest.Strategy.FAIL_FAST), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(result.evidence().caseResults().getFirst().status())
                .isEqualTo(TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE);
        assertThat(result.evidence().caseResults().getFirst().diagnosticCode())
                .isEqualTo("RG.TEST.SUITE_CHILD_EVIDENCE_INTEGRITY_INVALID");
        assertThat(result.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
    }

    @Test
    void replayPurposeExecutesSuiteThroughTheRealServiceBoundary() {
        IntegrationRequestContext replayIdentity = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "local", "WORKLOAD", "runner",
                "", "TEST_REPLAY", "correlation-replay", Set.of("quality"), "CONFIDENTIAL", "");
        when(registry.find("suite-a", 3, replayIdentity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", replayIdentity))
                .thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(replayIdentity)))
                .thenReturn(response("run-golden-replay", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED,
                                TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative-replay", "negative", "/root/output#PRIMARY",
                                "", "", TestRunEvidence.Status.PASSED,
                                TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("suite-a", request("replay-request",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), replayIdentity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(replayIdentity));
    }

    @Test
    void failFastStopsSchedulingAfterFirstFailedCaseAndCannotPromotePartialEvidence() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity))).thenReturn(response(
                "run-golden", "golden", "/root/fetch#PRIMARY", "", "",
                TestRunEvidence.Status.ASSERTION_FAILED, TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("suite-a", request("request-fail-fast",
                TestSuiteExecutionRequest.Strategy.FAIL_FAST), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PARTIAL);
        assertThat(result.evidence().caseResults()).extracting(TestSuiteRunEvidence.CaseResult::status)
                .containsExactly(TestSuiteRunEvidence.CaseStatus.FAILED,
                        TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
        assertThat(result.evidence().coverage().status())
                .isEqualTo(TestSuiteRunEvidence.CoverageStatus.INCOMPLETE);
        assertThat(result.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
        assertThat(result.evidence().promotion().reasons()).contains("SUITE_RUN_INCOMPLETE");
        verify(executions).executeAdmittedSuiteGraphCase(any(), eq(identity));
    }

    @Test
    void targetDriftProducesAuditablePartialRunWithoutExecutingAnyCase() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(
                graphTarget("sha256:" + "e".repeat(64), true));

        TestSuiteExecutionResponse result = service.execute("suite-a", request("request-stale",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PARTIAL);
        assertThat(result.evidence().diagnostics()).contains("TARGET_FINGERPRINT_CONFLICT");
        assertThat(result.evidence().caseResults()).allMatch(item ->
                item.status() == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED);
        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
        assertThat(runRepository.find("tenant-a", "test", result.suiteRunId())).isPresent();
    }

    @Test
    void clientRequestIdCannotBeReusedForDifferentExecutionIntent() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity))).thenReturn(response(
                "run-golden", "golden", "/root/fetch#PRIMARY",
                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE), response(
                "run-negative", "negative", "/root/output#PRIMARY", "", "",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));
        service.execute("suite-a", request("same-request", TestSuiteExecutionRequest.Strategy.COLLECT_ALL),
                identity);

        assertThatThrownBy(() -> service.execute("suite-a",
                request("same-request", TestSuiteExecutionRequest.Strategy.FAIL_FAST), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT"));
        verify(executions, times(2)).executeAdmittedSuiteGraphCase(any(), eq(identity));
    }

    @Test
    void operatorSuiteUsesMicroGraphExecutionWithExactFixtureReference() {
        TestSuite graphSuite = (TestSuite) storedSuite().suite();
        TestSuite operatorSuite = new TestSuite("", "operator-suite", 1,
                new TestSuite.Target("OPERATOR", "customer.normalize", TARGET), "INTERNAL",
                List.of(graphSuite.cases().getFirst()),
                new TestSuite.CoveragePolicy(1, List.of(TestSuite.CaseType.GOLDEN),
                        List.of("/root/operator#PRIMARY"), List.of(), 1, true),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of());
        StoredTestSuite stored = new StoredTestSuite("", "tenant-a", "test", "operator-suite", 1,
                SUITE, operatorSuite, Instant.now(), "runner");
        when(registry.find("operator-suite", 1, identity)).thenReturn(stored);
        TestOperatorTargetDescriptor descriptor = mock(TestOperatorTargetDescriptor.class);
        when(descriptor.target()).thenReturn(new TestExecutionApiRequest.Target(
                "OPERATOR", "customer.normalize", TARGET));
        when(descriptor.certificationEligible()).thenReturn(true);
        when(executions.describeOperatorTarget("customer.normalize", identity)).thenReturn(descriptor);
        when(executions.executeAdmittedSuiteOperatorCase(
                eq("customer.normalize"), any(), eq(identity))).thenReturn(operatorResponse(
                "run-operator", "golden", "/root/operator#PRIMARY", "", "",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("operator-suite",
                new TestSuiteExecutionRequest("", new TestSuiteExecutionRequest.SuiteRef(
                        "operator-suite", 1, SUITE), "operator-request",
                        TestSuiteExecutionRequest.Strategy.COLLECT_ALL, Map.of()), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        verify(executions).executeAdmittedSuiteOperatorCase(eq("customer.normalize"),
                org.mockito.ArgumentMatchers.argThat(request -> request.fixtureBundleRef().revision() == 1
                        && request.verbosity() == TestExecutionApiRequest.Verbosity.FULL), eq(identity));
        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
    }

    @Test
    void passingCasesCannotPassSuiteWhenRequiredStructuralCoverageIsMissing() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("suite-a", request("coverage-gap",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        assertThat(result.evidence().status())
                .isEqualTo(TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES);
        assertThat(result.evidence().coverage().status())
                .isEqualTo(TestSuiteRunEvidence.CoverageStatus.UNSATISFIED);
        assertThat(result.evidence().coverage().missingEdgeTransfers())
                .containsExactly(new TestSuite.EdgeTransferRef(
                        "/root/fetch#PRIMARY", "/root/output#PRIMARY"));
        assertThat(result.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
        assertThat(result.evidence().promotion().reasons()).contains("COVERAGE_UNSATISFIED");
    }

    @Test
    void terminalPersistenceFailureFailsClosedAndBestEffortCheckpointBlocksPromotion() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.executeAdmittedSuiteGraphCase(any(), eq(identity)))
                .thenReturn(response("run-golden", "golden", "/root/fetch#PRIMARY",
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE))
                .thenReturn(response("run-negative", "negative", "/root/output#PRIMARY", "", "",
                                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));
        runRepository.failNextTerminalUpdate = true;

        TestSuiteExecutionResponse result = service.execute("suite-a", request("terminal-store-failure",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);

        assertThat(result.evidence().status())
                .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
        assertThat(result.evidence().promotion().status())
                .isEqualTo(TestSuiteRunEvidence.PromotionStatus.BLOCKED);
        assertThat(result.evidence().promotion().reasons()).contains("EVIDENCE_INCOMPLETE");
        assertThat(runRepository.find("tenant-a", "test", result.suiteRunId())
                .orElseThrow().evidence().status())
                .isEqualTo(TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE);
    }

    @Test
    void retiredIdempotencyKeyCannotSilentlyRerunAfterEvidenceRetention() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        runRepository.retiredClientRequestIds.add("retired-request");

        assertThatThrownBy(() -> service.execute("suite-a", request("retired-request",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.TEST.SUITE_RUN_IDEMPOTENCY_RETIRED"));
        verify(executions, never()).executeAdmittedSuiteGraphCase(any(), any());
    }

    @Test
    void productionIdentityIsAuditedAndRejectedBeforeSuiteLookup() {
        IntegrationRequestContext production = new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "production", "local", "WORKLOAD", "runner",
                "", "TEST_EXECUTION", "correlation-prod", Set.of(), "RESTRICTED", "");

        assertThatThrownBy(() -> service.execute("suite-a", request("production-attempt",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), production))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.TEST.ENVIRONMENT_FORBIDDEN"));
        verify(securityEvents).append(org.mockito.ArgumentMatchers.argThat(event ->
                event.eventType().equals("TEST_PURPOSE_PRODUCTION_TOUCH")
                        && event.outcome().equals("REJECTED")));
        verifyNoInteractions(registry, executions);
    }

    private StoredTestSuite storedSuite() {
        TestSuite suite = new TestSuite("", "suite-a", 3,
                new TestSuite.Target("GRAPH", "graph-a", TARGET), "INTERNAL",
                List.of(
                        new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                                Map.of("orderId", "O-1"), new TestSuite.FixtureBundleRef(
                                "fixture-golden", 1, FIXTURE_1), List.of(), Map.of()),
                        new TestSuite.TestCase("negative", TestSuite.CaseType.NEGATIVE,
                                Map.of("orderId", "missing"), new TestSuite.FixtureBundleRef(
                                "fixture-negative", 2, FIXTURE_2), List.of(), Map.of())),
                new TestSuite.CoveragePolicy(2,
                        List.of(TestSuite.CaseType.GOLDEN, TestSuite.CaseType.NEGATIVE),
                        List.of("/root/fetch#PRIMARY", "/root/output#PRIMARY"),
                        List.of(new TestSuite.EdgeTransferRef(
                                "/root/fetch#PRIMARY", "/root/output#PRIMARY")), 1, true),
                new TestSuite.PromotionPolicy(true, 2, true), Map.of("owner", "quality"));
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3, SUITE,
                suite, Instant.now(), "runner");
    }

    private StoredTestSuite storedPropertySuite() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-property", 1, FIXTURE_1);
        TestSuiteV4 suite = new TestSuiteV4("", "suite-a", 3,
                new TestSuite.Target("GRAPH", "graph-a", TARGET), "INTERNAL",
                List.of(
                        new TestSuite.TestCase("property-001", TestSuite.CaseType.PROPERTY,
                                Map.of("orderId", "generated-root"), fixture,
                                List.of("property-root"), Map.of()),
                        new TestSuite.TestCase("property-001-shrink-001",
                                TestSuite.CaseType.PROPERTY,
                                Map.of("orderId", "generated-shrink"), fixture,
                                List.of("property-shrink"), Map.of())),
                new TestSuite.CoveragePolicy(2, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), 1, false), SemanticCoveragePolicy.empty(),
                new TestSuite.PromotionPolicy(true, 2, true),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false,
                "sha256:" + "e".repeat(64), "sha256:" + "f".repeat(64),
                new TestSuiteV4.PropertyGenerationPolicy(
                        "property-cases-v1", 42, 1, 1, 2, 32, 8, 32,
                        "VISUAL_SCHEMA_VALIDATOR_PROOF"),
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of(),
                List.of(new TestSuiteV4.PropertyTrialRef(
                        "property-001", "sha256:" + "9".repeat(64), 2,
                        List.of(new TestSuiteV4.PropertyShrinkRef(
                                "property-001-shrink-001", "property-001", 1,
                                "sha256:" + "8".repeat(64), 1)))),
                Map.of("source", "seeded-property-plan"));
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                SUITE, suite, Instant.now(), "runner");
    }

    private StoredTestSuite storedMutationSuite() {
        TestSuite base = (TestSuite) storedSuite().suite();
        TestSuiteV5 suite = new TestSuiteV5("", base.suiteId(), base.revision(), base.target(),
                base.classification(), base.cases(), base.coveragePolicy(),
                SemanticCoveragePolicy.empty(), base.promotionPolicy(),
                TestSuiteV5.EvaluationMode.PURE_DSL_MUTATION, TestSuiteV5.SOURCE_FORMAT,
                "sha256:" + "1".repeat(64), "sha256:" + "2".repeat(64),
                "sha256:" + "3".repeat(64),
                new TestSuiteV5.MutationPolicy(TestSuiteV5.PLANNER_VERSION, 1,
                        TestSuiteV5.SOURCE_FORMAT, TestSuiteV5.VERIFICATION_MODE, false, false),
                TestSuiteV5.SourcePlanStatus.GENERATED, false, List.of(),
                List.of(new TestSuiteV5.MutantRef("mutant-001",
                        TestSuiteV5.MutationKind.FALLBACK_REMOVED, "/members/1/fallback", 3, 5,
                        "sha256:" + "4".repeat(64), "sha256:" + "5".repeat(64),
                        "sha256:" + "6".repeat(64),
                        TestSuiteV5.EquivalenceClassification.UNKNOWN)),
                new TestSuiteV5.OracleSuiteRef("oracle", 1,
                        "sha256:" + "7".repeat(64), TestSuite.SCHEMA_VERSION),
                new TestSuiteV5.MutationScorePolicy(8_000, 0, false, false), Map.of());
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3, SUITE,
                suite, Instant.EPOCH, "author");
    }

    private StoredTestSuite storedTwoTrialPropertySuite() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "fixture-property", 1, FIXTURE_1);
        List<TestSuite.TestCase> cases = List.of(
                propertyCase("property-001", "root-1", fixture),
                propertyCase("property-001-shrink-001", "shrink-1", fixture),
                propertyCase("property-002", "root-2", fixture),
                propertyCase("property-002-shrink-001", "shrink-2", fixture));
        TestSuiteV4 suite = new TestSuiteV4("", "suite-a", 3,
                new TestSuite.Target("GRAPH", "graph-a", TARGET), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(4, List.of(TestSuite.CaseType.PROPERTY),
                        List.of(), List.of(), 1, false), SemanticCoveragePolicy.empty(),
                new TestSuite.PromotionPolicy(true, 4, true),
                TestSuiteV4.EvaluationMode.PROPERTY_EXECUTION,
                TestSuiteV4.Quantification.BOUNDED_SAMPLED, false,
                "sha256:" + "e".repeat(64), "sha256:" + "f".repeat(64),
                new TestSuiteV4.PropertyGenerationPolicy(
                        "property-cases-v1", 42, 2, 1, 4, 32, 8, 32,
                        "VISUAL_SCHEMA_VALIDATOR_PROOF"),
                TestSuiteV4.SourcePlanStatus.GENERATED, false, List.of(),
                List.of(
                        propertyTrial(1, '9', '8'),
                        propertyTrial(2, '7', '6')),
                Map.of("source", "seeded-property-plan"));
        return new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                SUITE, suite, Instant.now(), "runner");
    }

    private static TestSuite.TestCase propertyCase(
            String caseId, String orderId, TestSuite.FixtureBundleRef fixture) {
        return new TestSuite.TestCase(caseId, TestSuite.CaseType.PROPERTY,
                Map.of("orderId", orderId), fixture, List.of("property"), Map.of());
    }

    private static TestSuiteV4.PropertyTrialRef propertyTrial(
            int trial, char rootFingerprint, char shrinkFingerprint) {
        String root = "property-%03d".formatted(trial);
        return new TestSuiteV4.PropertyTrialRef(root,
                "sha256:" + String.valueOf(rootFingerprint).repeat(64), 2,
                List.of(new TestSuiteV4.PropertyShrinkRef(
                        root + "-shrink-001", root, 1,
                        "sha256:" + String.valueOf(shrinkFingerprint).repeat(64), 1)));
    }

    private TestSuiteV3 admissionSuite(TestBoundaryCasePlan plan, int caseCount) {
        List<TestBoundaryCasePlan.BoundaryCase> selected =
                plan.cases().stream().limit(caseCount).toList();
        List<TestSuite.TestCase> cases = selected.stream().map(source ->
                new TestSuite.TestCase(source.caseId(), TestSuite.CaseType.BOUNDARY,
                        source.input(), new TestSuite.FixtureBundleRef(
                                "boundary-fixture", 1, FIXTURE_1),
                        List.of("schema-admission"), Map.of())).toList();
        Map<String, TestSuiteV3.AdmissionExpectation> expectations = new LinkedHashMap<>();
        selected.forEach(source -> expectations.put(source.caseId(),
                new TestSuiteV3.AdmissionExpectation(
                        TestSuiteV3.ExpectedOutcome.valueOf(source.expectedOutcome().name()),
                        source.validationCodes())));
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "schema-boundary-plan");
        metadata.put("evaluationMode", TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION.name());
        metadata.put("boundaryPlanStatus", plan.status().name());
        metadata.put("coverageGapCount", plan.gaps().size());
        metadata.put("coverageGapsAccepted", plan.status() == TestBoundaryCasePlan.Status.PARTIAL);
        metadata.put("selectedCaseCount", cases.size());
        return new TestSuiteV3("", "suite-a", 3,
                new TestSuite.Target(plan.target().kind(), plan.target().id(),
                        plan.target().fingerprint()), "INTERNAL", cases,
                new TestSuite.CoveragePolicy(cases.size(), List.of(TestSuite.CaseType.BOUNDARY),
                        List.of(), List.of(), 0, false), SemanticCoveragePolicy.empty(),
                new TestSuite.PromotionPolicy(true, 0, false),
                TestSuiteV3.EvaluationMode.SCHEMA_ADMISSION,
                plan.planFingerprint(), plan.inputSchemaFingerprint(), expectations, metadata);
    }

    private AdmissionScenario admissionScenario(int caseCount) {
        SchemaEnvelope schema = SchemaEnvelope.object(Map.of(
                "name", Map.of("type", "string", "minLength", 1)), List.of("name"));
        TestExecutionApiRequest.Target target = new TestExecutionApiRequest.Target(
                "GRAPH", "graph-a", TARGET);
        TestBoundaryCasePlan plan = new TestBoundaryCasePlanner(objectMapper,
                new JsonSchemaSampleGenerator()).plan(target, schema, List.of());
        TestSuiteV3 admission = admissionSuite(plan, caseCount);
        StoredTestSuite stored = new StoredTestSuite("", "tenant-a", "test", "suite-a", 3,
                SUITE, admission, Instant.now(), "runner");
        return new AdmissionScenario(stored,
                TestSchemaAdmissionTarget.verified(objectMapper, target, schema, plan));
    }

    private static TestSuiteExecutionRequest request(String requestId,
                                                     TestSuiteExecutionRequest.Strategy strategy) {
        return new TestSuiteExecutionRequest("", new TestSuiteExecutionRequest.SuiteRef(
                "suite-a", 3, SUITE), requestId, strategy, Map.of("pipeline", "nightly"));
    }

    private static TestGraphTargetDescriptor graphTarget(String fingerprint, boolean eligible) {
        return new TestGraphTargetDescriptor("", new TestExecutionApiRequest.Target(
                "GRAPH", "graph-a", fingerprint), null, Map.of(), "NONE", eligible, List.of());
    }

    private static TestExecutionApiResponse response(String runId, String caseId, String siteId,
                                                     String edgeFrom, String edgeTo,
                                                     TestRunEvidence.Status status,
                                                     TestRunEvidence.EvidenceClass evidenceClass) {
        return response(runId, caseId, siteId, edgeFrom, edgeTo, status, evidenceClass,
                "GRAPH", "graph-a");
    }

    private static TestExecutionApiResponse operatorResponse(
            String runId, String caseId, String siteId, String edgeFrom, String edgeTo,
            TestRunEvidence.Status status, TestRunEvidence.EvidenceClass evidenceClass) {
        return response(runId, caseId, siteId, edgeFrom, edgeTo, status, evidenceClass,
                "OPERATOR", "customer.normalize");
    }

    private static TestExecutionApiResponse propertyResponse(
            String runId, String caseId, TestRunEvidence.Status status) {
        Instant now = Instant.now();
        boolean passed = status == TestRunEvidence.Status.PASSED;
        TestRunEvidence evidence = new TestRunEvidence("", runId, status,
                TestRunEvidence.EvidenceClass.CERTIFIABLE, "GRAPH_CONTRACT_TEST", TARGET,
                FIXTURE_1, "sha256:" + "7".repeat(64), now, now, List.of(), List.of(),
                List.of(new TestRunEvidence.FixtureConsumption(
                        "rule-property", 1, true, "SATISFIED")),
                List.of(new TestRunEvidence.AssertionResult(
                        "OUTPUT", "/accepted", passed, true, passed,
                        passed ? "" : "property counterexample")),
                passed ? List.of() : List.of("PROPERTY_ASSERTION_FAILED"),
                Map.of("caseId", caseId));
        return new TestExecutionApiResponse("", runId,
                new TestExecutionApiRequest.Target("GRAPH", "graph-a", TARGET),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", "fixture-property", 1, FIXTURE_1), null, evidence);
    }

    private static TestExecutionApiResponse response(
            String runId, String caseId, String siteId, String edgeFrom, String edgeTo,
            TestRunEvidence.Status status, TestRunEvidence.EvidenceClass evidenceClass,
            String targetKind, String targetId) {
        Instant now = Instant.now();
        boolean negative = "negative".equals(caseId);
        String fixtureId = "fixture-" + caseId;
        long fixtureRevision = negative ? 2 : 1;
        String fixtureFingerprint = negative ? FIXTURE_2 : FIXTURE_1;
        List<TestRunEvidence.EdgeTrace> edges = edgeFrom.isBlank() ? List.of() : List.of(
                new TestRunEvidence.EdgeTrace("edge", "TRANSFERRED", null, "/root", "", 1,
                        edgeFrom, edgeTo));
        TestRunEvidence evidence = new TestRunEvidence("", runId, status, evidenceClass,
                "GRAPH_CONTRACT_TEST", TARGET, fixtureFingerprint, "sha256:" + "f".repeat(64),
                now, now, List.of(new TestRunEvidence.NodeTrace("node", "operator", "SUCCESS", "REAL",
                null, null, "", 1, siteId, "/root", "", 1, 1, List.of())), edges,
                List.of(new TestRunEvidence.FixtureConsumption("rule-" + caseId, 1, true, "SATISFIED")),
                List.of(new TestRunEvidence.AssertionResult("OUTPUT", "/", true, true, true, "")),
                List.of(), Map.of("caseId", caseId));
        return new TestExecutionApiResponse("", runId,
                new TestExecutionApiRequest.Target(targetKind, targetId, TARGET),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", fixtureId, fixtureRevision, fixtureFingerprint), null, evidence);
    }

    private static TestSuiteRunEvidenceV2 withSchema(TestSuiteRunEvidenceV2 source,
                                                     String schemaVersion) {
        return new TestSuiteRunEvidenceV2(schemaVersion, source.suiteRunId(),
                source.clientRequestId(), source.status(), source.executionPurpose(),
                source.suiteRef(), source.target(), source.startedAt(), source.completedAt(),
                source.caseResults(), source.coverage(), source.semanticCoverage(),
                source.promotion(), source.diagnostics(), source.metadata());
    }

    private record AdmissionScenario(
            StoredTestSuite stored,
            TestSchemaAdmissionTarget current
    ) {
    }

    private static final class InMemorySuiteRunRepository implements TestSuiteRunRepository {
        private final Map<String, TestSuiteRunRecord> records = new LinkedHashMap<>();
        private final Set<String> retiredClientRequestIds = new java.util.HashSet<>();
        private boolean failNextTerminalUpdate;

        @Override
        public TestSuiteRunRecord create(TestSuiteRunRecord record, TestSuiteRunLease lease) {
            if (retiredClientRequestIds.contains(record.clientRequestId())) {
                throw new TestSuiteRunConflictException("retired idempotency key");
            }
            if (records.values().stream().anyMatch(value -> value.tenantId().equals(record.tenantId())
                    && value.environmentId().equals(record.environmentId())
                    && value.clientRequestId().equals(record.clientRequestId()))) {
                throw new TestSuiteRunConflictException("duplicate idempotency key");
            }
            records.put(record.suiteRunId(), record);
            return record;
        }

        @Override
        public TestSuiteRunRecord update(TestSuiteRunRecord record, TestSuiteRunLease lease,
                                         Instant observedAt) {
            if (failNextTerminalUpdate
                    && record.evidence().status() != TestSuiteRunEvidence.Status.RUNNING) {
                failNextTerminalUpdate = false;
                throw new IllegalStateException("terminal store unavailable");
            }
            records.put(record.suiteRunId(), record);
            return record;
        }

        @Override
        public boolean renewLease(String tenantId, String environmentId, String suiteRunId,
                                  String ownerId, Instant expiresAt, Instant observedAt) {
            return records.containsKey(suiteRunId);
        }

        @Override
        public List<AbandonedTestSuiteRun> findAbandoned(Instant observedAt, int limit) {
            return List.of();
        }

        @Override
        public boolean reconcileAbandoned(AbandonedTestSuiteRun abandoned,
                                          TestSuiteRunRecord terminal, Instant observedAt) {
            return false;
        }

        @Override
        public Optional<TestSuiteRunRecord> find(String tenantId, String environmentId, String suiteRunId) {
            return Optional.ofNullable(records.get(suiteRunId)).filter(record ->
                    record.tenantId().equals(tenantId) && record.environmentId().equals(environmentId));
        }

        @Override
        public Optional<TestSuiteRunRecord> findByClientRequestId(String tenantId, String environmentId,
                                                                 String clientRequestId) {
            return records.values().stream().filter(record -> record.tenantId().equals(tenantId)
                    && record.environmentId().equals(environmentId)
                    && record.clientRequestId().equals(clientRequestId)).findFirst();
        }
    }
}
