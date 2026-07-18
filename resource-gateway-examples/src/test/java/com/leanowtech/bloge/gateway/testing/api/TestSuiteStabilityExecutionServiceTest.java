package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestEvidenceIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSemanticResultFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunEvidenceProtocolCodec;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestSuiteStabilityExecutionServiceTest {
    private static final String SUITE_FINGERPRINT = fingerprint('a');
    private static final String TARGET = fingerprint('b');
    private static final String FIXTURE = fingerprint('c');
    private static final String PLAN = fingerprint('d');

    private ObjectMapper mapper;
    private TestSuiteRegistryService suites;
    private TestSuiteExecutionService suiteExecutions;
    private TestExecutionApiService childExecutions;
    private InMemoryRepository repository;
    private IntegrationRequestContext identity;
    private TestSuite suite;
    private Map<String, TestSuiteExecutionResponse> sourceById;
    private Map<String, TestExecutionApiResponse> childById;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        suites = mock(TestSuiteRegistryService.class);
        suiteExecutions = mock(TestSuiteExecutionService.class);
        childExecutions = mock(TestExecutionApiService.class);
        repository = new InMemoryRepository();
        identity = new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                "local", "WORKLOAD", "runner", "", "TEST_EXECUTION", "correlation-a",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
        suite = suite();
        sourceById = new LinkedHashMap<>();
        childById = new LinkedHashMap<>();
        for (int attempt = 1; attempt <= 29; attempt++) {
            TestSuiteExecutionResponse source = source(attempt);
            sourceById.put(source.suiteRunId(), source);
        }
        StoredTestSuite stored = new StoredTestSuite("", identity.tenantId(),
                identity.environmentId(), suite.suiteId(), suite.revision(), SUITE_FINGERPRINT,
                suite, Instant.now(), identity.actorId());
        when(suites.find(eq(suite.suiteId()), eq(suite.revision()), eq(identity)))
                .thenReturn(stored);
        when(suiteExecutions.execute(eq(suite.suiteId()), any(), eq(identity)))
                .thenAnswer(invocation -> {
                    TestSuiteExecutionRequest request = invocation.getArgument(1);
                    int attempt = ((Number) request.metadata().get("stabilityAttempt")).intValue();
                    return sourceById.get("suite-run-" + attempt);
                });
        when(suiteExecutions.find(any(), eq(identity)))
                .thenAnswer(invocation -> sourceById.get(invocation.getArgument(0)));
        when(childExecutions.find(any(), eq(TestExecutionApiRequest.Verbosity.FULL), eq(identity)))
                .thenAnswer(invocation -> childById.get(invocation.getArgument(0)));
    }

    @Test
    void executesThreeIndependentCollectAllAttemptsAndReplaysTheTerminalParent() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());
        TestSuiteStabilityExecutionRequest request = request(3);

        TestSuiteStabilityExecutionResponse first = service.execute(
                suite.suiteId(), request, identity);
        TestSuiteStabilityExecutionResponse replay = service.execute(
                suite.suiteId(), request, identity);

        assertThat(first).isEqualTo(replay);
        assertThat(first.evidence().status())
                .isEqualTo(TestSuiteStabilityEvidence.Status.STABLE);
        assertThat(first.evidence().promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.ELIGIBLE);
        assertThat(first.evidence().attempts())
                .allMatch(attempt -> attempt.sourcePromotionStatus()
                        == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE);
        assertThat(first.attestation().terminallyVerifiable()).isTrue();
        assertThat(repository.records).hasSize(1);
        assertThat(repository.renewals).isEqualTo(4);
        assertThat(repository.progressCheckpoints).isEqualTo(3);
        assertThat(repository.completions).isEqualTo(1);
        assertThat(repository.progress).isEmpty();

        ArgumentCaptor<TestSuiteExecutionRequest> requests =
                ArgumentCaptor.forClass(TestSuiteExecutionRequest.class);
        verify(suiteExecutions, times(3))
                .execute(eq(suite.suiteId()), requests.capture(), eq(identity));
        assertThat(requests.getAllValues())
                .allMatch(value -> value.strategy()
                        == TestSuiteExecutionRequest.Strategy.COLLECT_ALL)
                .extracting(TestSuiteExecutionRequest::clientRequestId)
                .doesNotHaveDuplicates();
        assertThat(requests.getAllValues())
                .extracting(value -> value.metadata().get("stabilityAttempt"))
                .containsExactly(1, 2, 3);
        verify(suiteExecutions, times(3)).find(any(), eq(identity));
        verify(childExecutions, times(3)).find(any(),
                eq(TestExecutionApiRequest.Verbosity.FULL), eq(identity));
    }

    @Test
    void executesThePrecommittedStatisticalHorizonAndReturnsV3Evidence() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        TestSuiteStabilityExecutionResponse response = service.execute(
                suite.suiteId(), statisticalRequest(29), identity);

        assertThat(response.schemaVersion())
                .isEqualTo(TestSuiteStabilityExecutionResponse.SCHEMA_VERSION);
        assertThat(response.evidence().schemaVersion())
                .isEqualTo(TestSuiteStabilityEvidence.SCHEMA_VERSION);
        assertThat(response.evidence().statisticalAssessment().status())
                .isEqualTo(TestSuiteStabilityEvidence.StatisticalStatus.SATISFIED);
        assertThat(response.evidence().statisticalAssessment().requiredAttempts()).isEqualTo(29);
        assertThat(response.evidence().promotion().status())
                .isEqualTo(TestSuiteStabilityEvidence.PromotionStatus.ELIGIBLE);
        verify(suiteExecutions, times(29)).execute(eq(suite.suiteId()), any(), eq(identity));
    }

    @Test
    void rejectsAnInsufficientStatisticalHorizonBeforeSuiteResolution() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service.execute(
                suite.suiteId(), statisticalRequest(28), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_STATISTICAL_HORIZON_INVALID");
                });
        verify(suites, times(0)).find(any(), anyLong(), eq(identity));
        verify(suiteExecutions, times(0)).execute(any(), any(), eq(identity));
    }

    @Test
    void parentIdempotencyConflictIsRejectedBeforeAnotherAttemptExecutes() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());
        service.execute(suite.suiteId(), request(3), identity);

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(4), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().code())
                                .isEqualTo("RG.TEST.STABILITY_IDEMPOTENCY_CONFLICT"));
        verify(suiteExecutions, times(3)).execute(eq(suite.suiteId()), any(), eq(identity));
    }

    @Test
    void unavailableStabilitySignerNeverPersistsAnUnsignedAnalysis() {
        TestSuiteStabilityExecutionService service = service(
                VisualEvidenceSigner.unavailable());

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_ATTESTATION_UNAVAILABLE");
                });
        assertThat(repository.records).isEmpty();
    }

    @Test
    void activeCrossReplicaParentReturnsRetryable429BeforeAnyAttemptExecutes() {
        repository.forceInProgress = true;
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(429);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_EXECUTION_IN_PROGRESS");
                    assertThat(failure.problem().details())
                            .containsEntry("retryAfterSeconds", 7L);
                });
        verify(suiteExecutions, times(0)).execute(any(), any(), eq(identity));
        assertThat(repository.records).isEmpty();
    }

    @Test
    void leaseLossStopsBeforeTheNextAttemptAndNeverPublishesTerminalEvidence() {
        repository.failRenewalAt = 2;
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_EXECUTION_LEASE_LOST");
                });
        verify(suiteExecutions, times(1))
                .execute(eq(suite.suiteId()), any(), eq(identity));
        assertThat(repository.records).isEmpty();
        assertThat(repository.progress.values()).singleElement()
                .satisfies(value -> assertThat(value.completedAttempts()).isEqualTo(1));
        assertThat(repository.completions).isZero();
    }

    @Test
    void retryAfterLeaseLossRestoresTheDurablePrefixAndExecutesOnlyRemainingAttempts() {
        repository.failRenewalAt = 2;
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOf(IntegrationProblemException.class);
        repository.failRenewalAt = Integer.MAX_VALUE;
        repository.renewals = 0;
        repository.leases.clear();

        TestSuiteStabilityExecutionResponse resumed = service.execute(
                suite.suiteId(), request(3), identity);

        assertThat(resumed.evidence().attempts())
                .extracting(TestSuiteStabilityEvidence.AttemptResult::attempt)
                .containsExactly(1, 2, 3);
        ArgumentCaptor<TestSuiteExecutionRequest> requests =
                ArgumentCaptor.forClass(TestSuiteExecutionRequest.class);
        verify(suiteExecutions, times(3))
                .execute(eq(suite.suiteId()), requests.capture(), eq(identity));
        assertThat(requests.getAllValues())
                .extracting(value -> value.metadata().get("stabilityAttempt"))
                .containsExactly(1, 2, 3);
        verify(suiteExecutions, times(4)).find(any(), eq(identity));
        assertThat(repository.records).hasSize(1);
        assertThat(repository.progress).isEmpty();
    }

    @Test
    void shuttingDownCoordinatorRejectsBeforeClaimOrChildExecution() {
        TestSuiteStabilityLeaseCoordinator coordinator =
                TestSuiteStabilityLeaseCoordinator.passive(
                        repository, Duration.ofSeconds(30));
        coordinator.close();
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner(), coordinator);

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_LEASE_COORDINATOR_UNAVAILABLE");
                });
        verify(suiteExecutions, times(0)).execute(any(), any(), eq(identity));
        assertThat(repository.leases).isEmpty();
    }

    @Test
    void nestedMetadataIsRejectedBeforeSuiteResolutionOrExecution() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());
        TestSuiteStabilityExecutionRequest request = new TestSuiteStabilityExecutionRequest("",
                new TestSuiteExecutionRequest.SuiteRef(
                        suite.suiteId(), suite.revision(), SUITE_FINGERPRINT),
                "stability-ci-nested-metadata", 3,
                Map.of("pipeline", Map.of("name", "nightly")));

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request, identity))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.STABILITY_METADATA_INVALID");
                });
        verify(suites, times(0)).find(any(), anyLong(), eq(identity));
        verify(suiteExecutions, times(0)).execute(any(), any(), eq(identity));
    }

    @Test
    void retainedAnalysisIsScopeAndClearanceCheckedAndReverifiedOnRead() {
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());
        TestSuiteStabilityExecutionResponse created = service.execute(
                suite.suiteId(), request(3), identity);

        assertThat(service.find(created.stabilityRunId(), identity)).isEqualTo(created);
        assertThat(service.findProgress(created.stabilityRunId(), identity).status())
                .isEqualTo(TestSuiteStabilityProgressResponse.Status.COMPLETED);

        IntegrationRequestContext otherTenant = new IntegrationRequestContext(
                "tenant-b", "org-a", "project-a", "test", "local", "WORKLOAD",
                "runner", "", "TEST_EXECUTION", "correlation-b",
                java.util.Set.of(), "CONFIDENTIAL", "");
        assertThatThrownBy(() -> service.find(created.stabilityRunId(), otherTenant))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().status()).isEqualTo(404));
    }

    @Test
    void progressQueryDistinguishesRecoverableAndLiveOwnershipWithoutLeakingTheJournal() {
        repository.failRenewalAt = 2;
        TestSuiteStabilityExecutionService service = service(
                new InMemoryVisualEvidenceSigner());

        assertThatThrownBy(() -> service.execute(suite.suiteId(), request(3), identity))
                .isInstanceOf(IntegrationProblemException.class);
        TestSuiteStabilityExecutionProgress progress = repository.progress.values()
                .iterator().next();

        TestSuiteStabilityProgressResponse recoverable = service.findProgress(
                progress.stabilityRunId(), identity);
        assertThat(recoverable.status())
                .isEqualTo(TestSuiteStabilityProgressResponse.Status.RECOVERABLE);
        assertThat(recoverable.completedAttempts()).isEqualTo(1);

        repository.leases.put(progress.clientRequestId(),
                new TestSuiteStabilityExecutionLease(
                        progress.stabilityRunId(), progress.tenantId(), progress.environmentId(),
                        progress.clientRequestId(), progress.requestFingerprint(), "owner-b", 1,
                        Instant.now().plusSeconds(30)));
        assertThat(service.findProgress(progress.stabilityRunId(), identity).status())
                .isEqualTo(TestSuiteStabilityProgressResponse.Status.RUNNING);

        IntegrationRequestContext insufficientClearance = new IntegrationRequestContext(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.region(), identity.actorType(),
                identity.actorId(), identity.delegatedBy(), identity.purpose(),
                "correlation-low", identity.groups(), "PUBLIC",
                identity.delegationGrantId());
        assertThatThrownBy(() -> service.findProgress(
                progress.stabilityRunId(), insufficientClearance))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure ->
                        assertThat(failure.problem().status()).isEqualTo(403));
    }

    private TestSuiteStabilityExecutionService service(VisualEvidenceSigner signer) {
        return service(signer, TestSuiteStabilityLeaseCoordinator.passive(
                repository, Duration.ofSeconds(30)));
    }

    private TestSuiteStabilityExecutionService service(
            VisualEvidenceSigner signer,
            TestSuiteStabilityLeaseCoordinator coordinator) {
        return new TestSuiteStabilityExecutionService(suites, suiteExecutions, childExecutions,
                repository, mapper, new TestSuiteStabilityAttestationService(mapper, signer),
                coordinator, Duration.ofDays(30));
    }

    private TestSuiteStabilityExecutionRequest request(int attempts) {
        return new TestSuiteStabilityExecutionRequest("",
                new TestSuiteExecutionRequest.SuiteRef(
                        suite.suiteId(), suite.revision(), SUITE_FINGERPRINT),
                "stability-ci-42", attempts, Map.of("pipeline", "nightly"));
    }

    private TestSuiteStabilityExecutionRequest statisticalRequest(int attempts) {
        TestSuiteStabilityStatisticalPolicy policy = new TestSuiteStabilityStatisticalPolicy(
                TestSuiteStabilityStatisticalPolicy.Model.ZERO_INSTABILITY_EXACT_BINOMIAL,
                TestSuiteStabilityStatisticalPolicy.ClaimScope.SUITE_ATTEMPT_ANY_CASE,
                TestSuiteStabilityStatisticalPolicy.StoppingRule.PRECOMMITTED_FIXED_HORIZON,
                TestSuiteStabilityStatisticalPolicy.CensoringPolicy.FAIL_CLOSED,
                9_500, 1_000);
        return new TestSuiteStabilityExecutionRequest(
                TestSuiteStabilityExecutionRequest.SCHEMA_VERSION,
                new TestSuiteExecutionRequest.SuiteRef(
                        suite.suiteId(), suite.revision(), SUITE_FINGERPRINT),
                "stability-statistical-ci-42", attempts, policy,
                Map.of("pipeline", "nightly"));
    }

    private TestSuiteExecutionResponse source(int attempt) {
        TestSuite.TestCase testCase = suite.cases().getFirst();
        TestRunEvidence child = child(attempt, testCase);
        String childFingerprint = ProtocolFingerprint.of(mapper, child);
        TestEvidenceIntegrity integrity = new TestEvidenceIntegrity("", childFingerprint,
                TestEvidenceIntegrity.SignatureStatus.VERIFIED, "test-key", "Ed25519",
                Instant.now(), "signature", TestEvidenceIntegrity.Projection.FULL,
                childFingerprint, true);
        TestExecutionApiResponse childResponse = new TestExecutionApiResponse("",
                child.runId(), new TestExecutionApiRequest.Target("GRAPH", "orders", TARGET),
                new TestExecutionApiResponse.ResolvedFixtureBundleRef(
                        "STORED", testCase.fixtureBundleRef().fixtureBundleId(),
                        testCase.fixtureBundleRef().revision(), FIXTURE), null, integrity, child);
        childById.put(child.runId(), childResponse);

        TestSuiteRunEvidence.CaseResult result = new TestSuiteRunEvidence.CaseResult(
                testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(),
                TestSuiteRunEvidence.CaseStatus.PASSED, child.runId(),
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                1, 1, "", "");
        Instant startedAt = Instant.now().minusSeconds(60 - attempt);
        TestSuiteRunEvidence evidence = new TestSuiteRunEvidence("", "suite-run-" + attempt,
                "attempt-request-" + attempt, TestSuiteRunEvidence.Status.PASSED,
                TestSuiteExecutionService.AUTHORIZED_PURPOSE,
                new TestSuiteExecutionRequest.SuiteRef(
                        suite.suiteId(), suite.revision(), SUITE_FINGERPRINT),
                suite.target(), startedAt, startedAt.plusSeconds(1), List.of(result),
                TestSuiteRunEvidence.CoverageVerdict.notEvaluated(),
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.ELIGIBLE, List.of(), true,
                        1, 1, true, true, true), List.of(), Map.of());
        String aggregateFingerprint = new TestSuiteRunEvidenceProtocolCodec(mapper)
                .fingerprint(evidence);
        TestSuiteRunAttestation attestation = new TestSuiteRunAttestation("",
                TestSuiteRunAttestation.SignatureStatus.VERIFIED,
                TestSuiteRunAttestation.Scope.TERMINAL, evidence.suiteRunId(), evidence.suiteRef(),
                fingerprint('8'), aggregateFingerprint,
                List.of(new TestSuiteRunAttestation.ChildEvidenceRef(
                        testCase.caseId(), child.runId(), childFingerprint)),
                Instant.now(), "test-key", "Ed25519", "signature", true);
        return new TestSuiteExecutionResponse("", evidence.suiteRunId(), aggregateFingerprint,
                evidence, attestation);
    }

    private TestRunEvidence child(int attempt, TestSuite.TestCase testCase) {
        TestRunEvidence raw = new TestRunEvidence("", "child-run-" + attempt,
                TestRunEvidence.Status.PASSED, TestRunEvidence.EvidenceClass.CERTIFIABLE,
                "GRAPH_CONTRACT_TEST", TARGET, FIXTURE, PLAN, "",
                Instant.now().minusSeconds(2), Instant.now().minusSeconds(1),
                List.of(new TestRunEvidence.NodeTrace("result", "operator.result", "SUCCESS",
                        "OUTPUT_LEVEL", Map.of(), Map.of("decision", "ALLOW"), "", 1)),
                List.of(), List.of(), List.of(new TestRunEvidence.AssertionResult(
                "GRAPH_OUTPUT", "/decision", true, "ALLOW", "ALLOW", "")),
                List.of(), Map.of("caseId", testCase.caseId()));
        return TestSemanticResultFingerprint.attach(mapper, raw);
    }

    private static TestSuite suite() {
        TestSuite.FixtureBundleRef fixture = new TestSuite.FixtureBundleRef(
                "orders-fixture", 2, FIXTURE);
        return new TestSuite("", "orders-suite", 7,
                new TestSuite.Target("GRAPH", "orders", TARGET), "INTERNAL",
                List.of(new TestSuite.TestCase("golden", TestSuite.CaseType.GOLDEN,
                        Map.of(), fixture, List.of(), Map.of())),
                new TestSuite.CoveragePolicy(1, List.of(), List.of(), List.of(), 1, true),
                new TestSuite.PromotionPolicy(true, 1, true), Map.of());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static final class InMemoryRepository implements TestSuiteStabilityRunRepository {
        private final Map<String, TestSuiteStabilityRunRecord> records = new LinkedHashMap<>();
        private final Map<String, TestSuiteStabilityExecutionLease> leases = new LinkedHashMap<>();
        private final Map<String, TestSuiteStabilityExecutionProgress> progress =
                new LinkedHashMap<>();
        private boolean forceInProgress;
        private int failRenewalAt = Integer.MAX_VALUE;
        private int renewals;
        private int progressCheckpoints;
        private int completions;

        @Override
        public TestSuiteStabilityLeaseClaim claim(TestSuiteStabilityLeaseRequest request) {
            Optional<TestSuiteStabilityRunRecord> terminal = findByClientRequestId(
                    request.tenantId(), request.environmentId(), request.clientRequestId());
            if (terminal.isPresent()) {
                return TestSuiteStabilityLeaseClaim.completed(terminal.get());
            }
            if (forceInProgress) {
                return TestSuiteStabilityLeaseClaim.inProgress(7);
            }
            TestSuiteStabilityExecutionLease existing = leases.get(request.clientRequestId());
            if (existing != null && existing.expiresAt().isAfter(Instant.now())) {
                return TestSuiteStabilityLeaseClaim.inProgress(7);
            }
            long epoch = existing == null ? 0 : existing.epoch() + 1;
            Instant now = Instant.now();
            TestSuiteStabilityExecutionProgress current = progress.computeIfAbsent(
                    request.clientRequestId(), ignored ->
                            new TestSuiteStabilityExecutionProgress(
                                    request.stabilityRunId(), request.tenantId(),
                                    request.environmentId(), request.clientRequestId(),
                                    request.requestFingerprint(), request.suiteRef(),
                                    request.classification(), request.plannedAttempts(), List.of(),
                                    now, now, now.plus(request.progressRetention())));
            TestSuiteStabilityExecutionLease acquired = new TestSuiteStabilityExecutionLease(
                    request.stabilityRunId(), request.tenantId(), request.environmentId(),
                    request.clientRequestId(), request.requestFingerprint(), request.ownerId(),
                    epoch, now.plus(request.leaseDuration()));
            leases.put(request.clientRequestId(), acquired);
            return TestSuiteStabilityLeaseClaim.acquired(acquired, current);
        }

        @Override
        public Optional<TestSuiteStabilityExecutionLease> renew(
                TestSuiteStabilityExecutionLease lease,
                Duration leaseDuration) {
            renewals++;
            if (renewals >= failRenewalAt) {
                return Optional.empty();
            }
            TestSuiteStabilityExecutionLease stored = leases.get(lease.clientRequestId());
            if (!sameFence(stored, lease) || !stored.expiresAt().isAfter(Instant.now())) {
                return Optional.empty();
            }
            TestSuiteStabilityExecutionLease renewed = new TestSuiteStabilityExecutionLease(
                    lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), Instant.now().plus(leaseDuration));
            leases.put(lease.clientRequestId(), renewed);
            return Optional.of(renewed);
        }

        @Override
        public TestSuiteStabilityProgressCheckpoint checkpoint(
                TestSuiteStabilityExecutionLease lease,
                TestSuiteStabilityExecutionProgress.AttemptReference attempt,
                Duration leaseDuration,
                Duration progressRetention) {
            progressCheckpoints++;
            TestSuiteStabilityExecutionLease stored = leases.get(lease.clientRequestId());
            if (!sameFence(stored, lease) || !stored.expiresAt().isAfter(Instant.now())) {
                throw new TestSuiteStabilityRunConflictException(
                        TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                        "lease lost");
            }
            TestSuiteStabilityExecutionProgress current = progress.get(lease.clientRequestId());
            if (current == null) {
                throw new TestSuiteStabilityRunConflictException(
                        TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                        "progress missing");
            }
            Instant now = Instant.now();
            TestSuiteStabilityExecutionProgress successor = current.append(
                    attempt, now, now.plus(progressRetention));
            TestSuiteStabilityExecutionLease renewed = new TestSuiteStabilityExecutionLease(
                    lease.stabilityRunId(), lease.tenantId(), lease.environmentId(),
                    lease.clientRequestId(), lease.requestFingerprint(), lease.ownerId(),
                    lease.epoch(), now.plus(leaseDuration));
            progress.put(lease.clientRequestId(), successor);
            leases.put(lease.clientRequestId(), renewed);
            return new TestSuiteStabilityProgressCheckpoint(renewed, successor);
        }

        @Override
        public boolean release(TestSuiteStabilityExecutionLease lease) {
            TestSuiteStabilityExecutionLease stored = leases.get(lease.clientRequestId());
            return sameFence(stored, lease) && leases.remove(lease.clientRequestId(), stored);
        }

        @Override
        public TestSuiteStabilityRunRecord complete(
                TestSuiteStabilityRunRecord record,
                TestSuiteStabilityExecutionLease lease) {
            TestSuiteStabilityExecutionLease stored = leases.get(lease.clientRequestId());
            if (!sameFence(stored, lease) || !stored.expiresAt().isAfter(Instant.now())) {
                throw new TestSuiteStabilityRunConflictException(
                        TestSuiteStabilityRunConflictException.Reason.LEASE_LOST,
                        "lease lost");
            }
            TestSuiteStabilityExecutionProgress current = progress.get(
                    lease.clientRequestId());
            if (current == null || current.completedAttempts() != current.plannedAttempts()) {
                throw new TestSuiteStabilityRunConflictException(
                        TestSuiteStabilityRunConflictException.Reason.PROGRESS_CONFLICT,
                        "progress incomplete");
            }
            completions++;
            records.put(record.stabilityRunId(), record);
            progress.remove(lease.clientRequestId());
            leases.remove(lease.clientRequestId());
            return record;
        }

        @Override
        public int purgeExpiredLeases(int limit) {
            int before = leases.size();
            leases.values().removeIf(value -> !value.expiresAt().isAfter(Instant.now()));
            return before - leases.size();
        }

        @Override
        public Optional<TestSuiteStabilityRunRecord> find(
                String tenantId, String environmentId, String stabilityRunId) {
            return Optional.ofNullable(records.get(stabilityRunId)).filter(value ->
                    value.tenantId().equals(tenantId)
                            && value.environmentId().equals(environmentId));
        }

        @Override
        public Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
                String tenantId, String environmentId, String clientRequestId) {
            return records.values().stream().filter(value -> value.tenantId().equals(tenantId)
                    && value.environmentId().equals(environmentId)
                    && value.clientRequestId().equals(clientRequestId)).findFirst();
        }

        @Override
        public Optional<TestSuiteStabilityProgressSnapshot> findProgress(
                String tenantId,
                String environmentId,
                String stabilityRunId) {
            Instant now = Instant.now();
            return progress.values().stream()
                    .filter(value -> value.tenantId().equals(tenantId)
                            && value.environmentId().equals(environmentId)
                            && value.stabilityRunId().equals(stabilityRunId)
                            && value.expiresAt().isAfter(now))
                    .findFirst()
                    .map(value -> {
                        TestSuiteStabilityExecutionLease owner = leases.get(
                                value.clientRequestId());
                        boolean live = owner != null && owner.expiresAt().isAfter(now);
                        return new TestSuiteStabilityProgressSnapshot(value, live, now);
                    });
        }

        private static boolean sameFence(
                TestSuiteStabilityExecutionLease left,
                TestSuiteStabilityExecutionLease right) {
            return left != null && right != null
                    && left.stabilityRunId().equals(right.stabilityRunId())
                    && left.ownerId().equals(right.ownerId())
                    && left.epoch() == right.epoch()
                    && left.requestFingerprint().equals(right.requestFingerprint());
        }
    }
}
