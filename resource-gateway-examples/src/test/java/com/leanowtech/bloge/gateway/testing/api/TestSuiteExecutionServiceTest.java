package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private TestSuiteExecutionService service;
    private IntegrationRequestContext identity;

    @BeforeEach
    void setUp() {
        registry = mock(TestSuiteRegistryService.class);
        executions = mock(TestExecutionApiService.class);
        runRepository = new InMemorySuiteRunRepository();
        securityEvents = mock(TestSecurityEventRepository.class);
        service = new TestSuiteExecutionService(registry, executions, runRepository,
                new ObjectMapper().findAndRegisterModules(), securityEvents,
                Duration.ofDays(30));
        when(executions.verifyEvidence(any())).thenReturn(true);
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test", "local",
                "WORKLOAD", "runner", "", "TEST_EXECUTION", "correlation-a",
                Set.of("quality"), "CONFIDENTIAL", "");
    }

    @Test
    void collectAllExecutesExactCasesAndComputesCoverageAndPromotionVerdict() {
        StoredTestSuite stored = storedSuite();
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.execute(any(), eq(identity)))
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

        TestSuiteExecutionResponse retry = service.execute("suite-a", request("request-a",
                TestSuiteExecutionRequest.Strategy.COLLECT_ALL), identity);
        assertThat(retry).isEqualTo(result);
        verify(executions, times(2)).execute(any(), eq(identity));
        assertThat(runRepository.records).hasSize(1);
    }

    @Test
    void unsignedOrTamperedChildEvidenceCannotSatisfySuitePromotion() {
        StoredTestSuite stored = storedSuite();
        TestExecutionApiResponse child = response("run-golden", "golden", "/root/fetch#PRIMARY",
                "/root/fetch#PRIMARY", "/root/output#PRIMARY",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE);
        when(registry.find("suite-a", 3, identity)).thenReturn(stored);
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.execute(any(), eq(identity))).thenReturn(child);
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
        when(executions.execute(any(), eq(replayIdentity)))
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
        verify(executions, times(2)).execute(any(), eq(replayIdentity));
    }

    @Test
    void failFastStopsSchedulingAfterFirstFailedCaseAndCannotPromotePartialEvidence() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.execute(any(), eq(identity))).thenReturn(response(
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
        verify(executions).execute(any(), eq(identity));
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
        verify(executions, never()).execute(any(), any());
        assertThat(runRepository.find("tenant-a", "test", result.suiteRunId())).isPresent();
    }

    @Test
    void clientRequestIdCannotBeReusedForDifferentExecutionIntent() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.execute(any(), eq(identity))).thenReturn(response(
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
        verify(executions, times(2)).execute(any(), eq(identity));
    }

    @Test
    void operatorSuiteUsesMicroGraphExecutionWithExactFixtureReference() {
        TestSuite graphSuite = storedSuite().suite();
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
        when(executions.executeOperator(eq("customer.normalize"), any(), eq(identity))).thenReturn(operatorResponse(
                "run-operator", "golden", "/root/operator#PRIMARY", "", "",
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE));

        TestSuiteExecutionResponse result = service.execute("operator-suite",
                new TestSuiteExecutionRequest("", new TestSuiteExecutionRequest.SuiteRef(
                        "operator-suite", 1, SUITE), "operator-request",
                        TestSuiteExecutionRequest.Strategy.COLLECT_ALL, Map.of()), identity);

        assertThat(result.evidence().status()).isEqualTo(TestSuiteRunEvidence.Status.PASSED);
        verify(executions).executeOperator(eq("customer.normalize"),
                org.mockito.ArgumentMatchers.argThat(request -> request.fixtureBundleRef().revision() == 1
                        && request.verbosity() == TestExecutionApiRequest.Verbosity.FULL), eq(identity));
        verify(executions, never()).execute(any(), any());
    }

    @Test
    void passingCasesCannotPassSuiteWhenRequiredStructuralCoverageIsMissing() {
        when(registry.find("suite-a", 3, identity)).thenReturn(storedSuite());
        when(executions.describeGraphTarget("graph-a", identity)).thenReturn(graphTarget(TARGET, true));
        when(executions.execute(any(), eq(identity)))
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
        when(executions.execute(any(), eq(identity)))
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
        verify(executions, never()).execute(any(), any());
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
