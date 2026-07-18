package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionIntent;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionSubjects;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.Kind;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV5;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestMutationSuiteEvidenceEvaluator;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteEvidenceAggregator;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunEvidenceProtocolCodec;
import com.leanowtech.bloge.gateway.testing.planning.TestDslMutationPlanner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Isolated, idempotent executor for immutable pure-DSL mutation suites.
 *
 * <p>The service regenerates the complete mutant closure before it creates a RUNNING record. It
 * executes the unmodified baseline oracle first, then every mutant serially under one parent
 * admission permit. A mutant is killed only by independently verified assertion-failure evidence;
 * runtime, fixture, timeout, lease, persistence, and signature failures remain inconclusive or
 * unclassified. Every child transition is checkpointed under a renewable durable lease.</p>
 */
public final class TestMutationSuiteExecutionService {
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 255;
    private static final int MAX_METADATA_BYTES = 16_384;
    private static final int MAX_DIAGNOSTIC_LENGTH = 512;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> EXECUTION_PURPOSES = Set.of("TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestExecutionApiService executions;
    private final TestSuiteRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final TestSecurityEventRepository securityEvents;
    private final TestSuiteRunLeaseCoordinator leaseCoordinator;
    private final TestSuiteRunAttestationService attestations;
    private final TestRuntimeAdmissionGate admissions;
    private final TestSuiteEvidenceAggregator baselineAggregator;
    private final TestMutationSuiteEvidenceEvaluator mutationEvaluator;
    private final TestSuiteRunEvidenceProtocolCodec evidenceCodec;
    private final Duration retention;

    /**
     * Creates an embedded runner with passive leases and no signing authority.
     *
     * <p>Production composition must use the complete constructor with an active lease coordinator
     * and available signer; this constructor is retained for focused adapters and fail-closed tests.</p>
     */
    public TestMutationSuiteExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestExecutionApiService executions,
            TestSuiteRunRepository runRepository,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            Duration retention) {
        this(suiteRegistry, executions, runRepository, objectMapper, securityEvents, retention,
                TestSuiteRunLeaseCoordinator.passive(Duration.ofMinutes(5)),
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()),
                TestRuntimeAdmissionGate.unbounded());
    }

    /**
     * Creates the complete mutation runner.
     *
     * @param suiteRegistry immutable suite and oracle registry
     * @param executions exact graph regeneration and isolated child execution boundary
     * @param runRepository durable aggregate checkpoint store shared with ordinary suites
     * @param objectMapper canonical protocol mapper
     * @param securityEvents mandatory fail-closed security audit sink
     * @param retention terminal aggregate retention
     * @param leaseCoordinator renewable process ownership coordinator
     * @param attestations checkpoint and terminal signing boundary
     * @param admissions database-authoritative all-dimension capacity gate
     */
    public TestMutationSuiteExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestExecutionApiService executions,
            TestSuiteRunRepository runRepository,
            ObjectMapper objectMapper,
            TestSecurityEventRepository securityEvents,
            Duration retention,
            TestSuiteRunLeaseCoordinator leaseCoordinator,
            TestSuiteRunAttestationService attestations,
            TestRuntimeAdmissionGate admissions) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.admissions = Objects.requireNonNull(admissions, "admissions");
        this.baselineAggregator = new TestSuiteEvidenceAggregator(objectMapper);
        this.mutationEvaluator = new TestMutationSuiteEvidenceEvaluator(objectMapper);
        this.evidenceCodec = new TestSuiteRunEvidenceProtocolCodec(objectMapper);
        this.retention = retention == null || retention.isZero() || retention.isNegative()
                ? Duration.ofDays(30) : retention;
    }

    /**
     * Executes or idempotently resolves one exact immutable V5 mutation suite.
     *
     * @param suiteId path-bound suite id
     * @param request exact suite revision, idempotency key, and per-mutant strategy
     * @param identity verified test-execution identity
     * @return latest durable mutation aggregate
     */
    public TestSuiteExecutionResponse execute(
            String suiteId,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        validateRequest(suiteId, request, identity);
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
        Optional<TestSuiteRunRecord> existing = findByClientRequestId(
                request.clientRequestId(), identity);
        if (existing.isPresent()) {
            return idempotentResponse(existing.get(), requestFingerprint, identity);
        }

        StoredTestSuite stored = suiteRegistry.find(request.suiteRef().suiteId(),
                request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_FINGERPRINT_CONFLICT",
                    "Stored mutation suite differs from the exact execution reference.", Map.of());
        }
        if (!(stored.suite() instanceof TestSuiteV5 suite)) {
            throw conflict(identity, "RG.TEST.MUTATION_SUITE_REQUIRED",
                    "The mutation execution endpoint accepts only immutable V5 suites.",
                    Map.of("schemaVersion", stored.suite().schemaVersion()));
        }
        requireOracle(suite, identity);

        TestMutationCasePlan reviewedPlan = mutationPlan(suite);
        List<TestDslMutationPlanner.RegeneratedMutant> regenerated;
        TestGraphTargetDescriptor descriptor;
        try {
            regenerated = executions.regenerateGraphMutations(
                    suite.target().id(), reviewedPlan, identity);
            descriptor = executions.describeGraphTarget(suite.target().id(), identity);
        } catch (IntegrationProblemException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.MUTATION_PREFLIGHT_UNAVAILABLE",
                    "The complete mutation closure could not be proven before execution.");
        }
        if (!suite.target().fingerprint().equals(descriptor.target().fingerprint())
                || regenerated.size() != suite.mutants().size()) {
            throw conflict(identity, "RG.TEST.MUTATION_PREFLIGHT_MISMATCH",
                    "The current baseline or regenerated mutant closure differs from the V5 suite.",
                    Map.of());
        }

        Instant startedAt = Instant.now();
        String suiteRunId = UUID.randomUUID().toString();
        List<TestSuiteEvidenceAggregator.CaseObservation> baseline = pendingCases(suite);
        List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix = pendingMatrix(suite);
        TestSuiteEvidenceAggregator.TargetState targetState =
                new TestSuiteEvidenceAggregator.TargetState(true,
                        descriptor.certificationEligible());
        TestSuiteRunEvidenceV5 running = evidence(stored, suite, request, identity,
                suiteRunId, startedAt, null, true, false, baseline, matrix, targetState, List.of());
        TestSuiteRunAttestationService.SealResult initialSeal = attestations.seal(running,
                requestFingerprint, List.of(), TestSuiteRunAttestation.Scope.CHECKPOINT);
        if (!initialSeal.verified()) {
            throw unavailable(identity, "RG.TEST.SUITE_ATTESTATION_UNAVAILABLE",
                    "Mutation execution cannot start because its initial checkpoint cannot be signed.");
        }
        TestSuiteRunRecord initial = new TestSuiteRunRecord(suiteRunId,
                request.clientRequestId(), requestFingerprint, identity.tenantId(),
                identity.organizationId(), identity.projectId(), identity.environmentId(),
                identity.actorId(), suite.classification(), "", running,
                initialSeal.attestation(), startedAt, startedAt.plus(retention));

        AdmissionSubjects subjects = executions.admissionSubjects(target(suite), identity);
        String admissionFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testMutationSuiteAdmissionIntent.v1",
                "requestFingerprint", requestFingerprint,
                "suiteFingerprint", stored.fingerprint(),
                "mutationPlanFingerprint", suite.mutationPlanFingerprint(),
                "operatorRefs", subjects.operatorRefs(),
                "dependencyRefs", subjects.dependencyRefs()));
        AdmissionIntent intent = new AdmissionIntent(Kind.SUITE, request.clientRequestId(),
                admissionFingerprint, stored.suiteId(), subjects.operatorRefs(),
                subjects.dependencyRefs());
        try (AdmissionGuard admission = admissions.admit(identity, intent)) {
            TestSuiteExecutionResponse response = executeAdmitted(initial, stored, suite, request,
                    identity, regenerated, baseline, matrix, targetState);
            admission.checkpoint();
            return response;
        }
    }

    private void requireOracle(TestSuiteV5 suite, IntegrationRequestContext identity) {
        TestSuiteV5.OracleSuiteRef ref = suite.oracleSuiteRef();
        StoredTestSuite oracle = suiteRegistry.find(ref.suiteId(), ref.revision(), identity);
        requireClearance(oracle.suite().classification(), identity);
        TestSuiteProtocol oracleSuite = oracle.suite();
        if (!ref.fingerprint().equals(oracle.fingerprint())
                || !ref.schemaVersion().equals(oracleSuite.schemaVersion())
                || !suite.target().equals(oracleSuite.target())
                || !suite.cases().equals(oracleSuite.cases())) {
            throw conflict(identity, "RG.TEST.MUTATION_ORACLE_MISMATCH",
                    "The immutable business oracle closure differs from the V5 suite.", Map.of());
        }
    }

    private TestSuiteExecutionResponse executeAdmitted(
            TestSuiteRunRecord initial,
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestDslMutationPlanner.RegeneratedMutant> regenerated,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            TestSuiteEvidenceAggregator.TargetState targetState) {
        TestSuiteRunRecord record;
        try {
            record = runRepository.create(initial, leaseCoordinator.newLease());
        } catch (TestSuiteRunConflictException race) {
            TestSuiteRunRecord winner = findByClientRequestId(
                    request.clientRequestId(), identity).orElseThrow(() -> conflict(identity,
                    "RG.TEST.SUITE_RUN_IDEMPOTENCY_RETIRED",
                    "clientRequestId is already retired; use a new key.", Map.of()));
            return idempotentResponse(winner, initial.requestFingerprint(), identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_RUN_STORE_UNAVAILABLE",
                    "The independent suite-run store is unavailable.");
        }
        try (TestSuiteRunLeaseCoordinator.LeaseGuard lease = leaseCoordinator.monitor(record)) {
            return executeOwned(record, stored, suite, request, identity, regenerated,
                    baseline, matrix, targetState, lease);
        }
    }

    private TestSuiteExecutionResponse executeOwned(
            TestSuiteRunRecord record,
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestDslMutationPlanner.RegeneratedMutant> regenerated,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            TestSuiteEvidenceAggregator.TargetState targetState,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
            if (!lease.held()) {
                markBaselineRemaining(suite, baseline, caseIndex, "SUITE_RUN_LEASE_LOST");
                markAllPending(suite, matrix, "SUITE_RUN_LEASE_LOST");
                return finish(record, stored, suite, request, identity, baseline, matrix,
                        targetState, true, List.of("SUITE_RUN_LEASE_LOST"), lease);
            }
            baseline.set(caseIndex, executeBaselineCase(stored, suite, request,
                    record.suiteRunId(), suite.cases().get(caseIndex), identity));
            boolean baselineHasMoreWork = caseIndex + 1 < suite.cases().size()
                    || baselineStatus(baseline, false)
                    == TestSuiteRunEvidenceV5.BaselineStatus.PASSED;
            if (baselineHasMoreWork
                    && !checkpoint(record, stored, suite, request, identity, baseline, matrix,
                    targetState, lease)) {
                markBaselineRemaining(suite, baseline, caseIndex + 1,
                        "SUITE_RUN_STORE_UNAVAILABLE");
                markAllPending(suite, matrix, "SUITE_RUN_STORE_UNAVAILABLE");
                return finish(record, stored, suite, request, identity, baseline, matrix,
                        targetState, true, List.of("SUITE_RUN_STORE_UNAVAILABLE"), lease);
            }
        }

        TestSuiteRunEvidenceV5.BaselineStatus baselineStatus = baselineStatus(baseline, false);
        if (baselineStatus != TestSuiteRunEvidenceV5.BaselineStatus.PASSED) {
            markAllPending(suite, matrix, "BASELINE_ORACLE_NOT_PASSED");
            return finish(record, stored, suite, request, identity, baseline, matrix,
                    targetState, baselineStatus
                    == TestSuiteRunEvidenceV5.BaselineStatus.EVIDENCE_INCOMPLETE,
                    List.of("BASELINE_ORACLE_NOT_PASSED"), lease);
        }

        for (int mutantIndex = 0; mutantIndex < suite.mutants().size(); mutantIndex++) {
            TestDslMutationPlanner.RegeneratedMutant mutant = regenerated.get(mutantIndex);
            List<TestSuiteEvidenceAggregator.CaseObservation> row = matrix.get(mutantIndex);
            for (int caseIndex = 0; caseIndex < suite.cases().size(); caseIndex++) {
                if (!lease.held()) {
                    markAllPending(suite, matrix, "SUITE_RUN_LEASE_LOST");
                    return finish(record, stored, suite, request, identity, baseline, matrix,
                            targetState, true, List.of("SUITE_RUN_LEASE_LOST"), lease);
                }
                TestSuite.TestCase testCase = suite.cases().get(caseIndex);
                TestSuiteEvidenceAggregator.CaseObservation observation = executeMutationCase(
                        stored, suite, request, record.suiteRunId(), mutant, testCase, identity);
                row.set(caseIndex, observation);
                boolean shortCircuit = request.strategy()
                        == TestMutationSuiteExecutionRequest.Strategy.STOP_AFTER_KILL
                        && provenKill(observation);
                if (shortCircuit) {
                    markRowRemaining(suite, row, caseIndex + 1,
                            "MUTANT_KILL_SHORT_CIRCUIT");
                }
                if (hasPendingMutationCases(matrix)
                        && !checkpoint(record, stored, suite, request, identity, baseline, matrix,
                        targetState, lease)) {
                    markAllPending(suite, matrix, "SUITE_RUN_STORE_UNAVAILABLE");
                    return finish(record, stored, suite, request, identity, baseline, matrix,
                            targetState, true, List.of("SUITE_RUN_STORE_UNAVAILABLE"), lease);
                }
                if (shortCircuit) {
                    break;
                }
            }
        }
        return finish(record, stored, suite, request, identity, baseline, matrix,
                targetState, false, List.of(), lease);
    }

    private static boolean hasPendingMutationCases(
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix) {
        return matrix.stream().flatMap(List::stream).anyMatch(observation ->
                observation.result().status() == TestSuiteRunEvidence.CaseStatus.PENDING);
    }

    private TestSuiteEvidenceAggregator.CaseObservation executeBaselineCase(
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            String suiteRunId,
            TestSuite.TestCase testCase,
            IntegrationRequestContext identity) {
        try {
            Map<String, Object> context = objectMapper.convertValue(
                    testCase.input(), new TypeReference<>() { });
            TestExecutionApiResponse child = executions.executeAdmittedSuiteGraphCase(
                    new TestExecutionApiRequest("", target(suite),
                            TestExecutionApiService.AUTHORIZED_PURPOSE, context, null,
                            fixtureRef(testCase), TestExecutionApiRequest.Verbosity.FULL,
                            caseMetadata(stored, request, suiteRunId, "baseline", testCase)),
                    identity);
            return childObservation(suite, testCase, suite.target().fingerprint(), child);
        } catch (IntegrationProblemException rejected) {
            return rejectedObservation(testCase, rejected.problem().status() >= 500,
                    rejected.problem().code(), rejected.problem().title());
        } catch (RuntimeException failure) {
            return rejectedObservation(testCase, true,
                    "RG.TEST.MUTATION_BASELINE_UNEXPECTED_FAILURE",
                    "Baseline child failed without durable evidence: "
                            + failure.getClass().getSimpleName());
        }
    }

    private TestSuiteEvidenceAggregator.CaseObservation executeMutationCase(
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            String suiteRunId,
            TestDslMutationPlanner.RegeneratedMutant mutant,
            TestSuite.TestCase testCase,
            IntegrationRequestContext identity) {
        try {
            Map<String, Object> context = objectMapper.convertValue(
                    testCase.input(), new TypeReference<>() { });
            TestExecutionApiRequest childRequest = new TestExecutionApiRequest("",
                    new TestExecutionApiRequest.Target("GRAPH", suite.target().id(),
                            mutant.coordinate().mutantTargetFingerprint()),
                    TestExecutionApiService.AUTHORIZED_PURPOSE, context, null,
                    fixtureRef(testCase), TestExecutionApiRequest.Verbosity.FULL,
                    caseMetadata(stored, request, suiteRunId,
                            mutant.coordinate().mutantId(), testCase));
            TestExecutionApiResponse child = executions.executeAdmittedMutationGraphCase(
                    mutant, suite.target().fingerprint(), childRequest, identity);
            return childObservation(suite, testCase,
                    mutant.coordinate().mutantTargetFingerprint(), child);
        } catch (IntegrationProblemException rejected) {
            return rejectedObservation(testCase, rejected.problem().status() >= 500,
                    rejected.problem().code(), rejected.problem().title());
        } catch (RuntimeException failure) {
            return rejectedObservation(testCase, true,
                    "RG.TEST.MUTATION_CHILD_UNEXPECTED_FAILURE",
                    "Mutation child failed without durable evidence: "
                            + failure.getClass().getSimpleName());
        }
    }

    private TestSuiteEvidenceAggregator.CaseObservation childObservation(
            TestSuiteV5 suite,
            TestSuite.TestCase testCase,
            String expectedTarget,
            TestExecutionApiResponse child) {
        if (!validChildIdentity(suite, testCase, expectedTarget, child)
                || !executions.verifyEvidence(child)) {
            return rejectedObservation(testCase, true,
                    "RG.TEST.MUTATION_CHILD_EVIDENCE_INVALID",
                    "Child evidence did not preserve its signed target, fixture, and run identity.");
        }
        TestRunEvidence evidence = child.evidence();
        int assertions = evidence.assertionResults().size();
        int passed = (int) evidence.assertionResults().stream()
                .filter(TestRunEvidence.AssertionResult::passed).count();
        TestSuiteRunEvidence.CaseStatus status = evidence.status() == TestRunEvidence.Status.PASSED
                ? TestSuiteRunEvidence.CaseStatus.PASSED
                : evidence.status() == TestRunEvidence.Status.EVIDENCE_INCOMPLETE
                ? TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                : TestSuiteRunEvidence.CaseStatus.FAILED;
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), status, child.runId(), evidence.status(),
                        evidence.evidenceClass(), assertions, passed,
                        status == TestSuiteRunEvidence.CaseStatus.PASSED
                                ? "" : evidence.status().name(),
                        evidence.diagnostics().stream().findFirst()
                                .map(TestMutationSuiteExecutionService::bounded).orElse("")),
                evidence);
    }

    private static boolean validChildIdentity(
            TestSuiteV5 suite,
            TestSuite.TestCase testCase,
            String expectedTarget,
            TestExecutionApiResponse child) {
        if (child == null || child.evidence() == null || child.target() == null
                || child.fixtureBundleRef() == null || child.runId().isBlank()
                || !child.runId().equals(child.evidence().runId())) {
            return false;
        }
        TestSuite.FixtureBundleRef fixture = testCase.fixtureBundleRef();
        String expectedPurpose = expectedTarget.equals(suite.target().fingerprint())
                ? TestExecutionApiService.AUTHORIZED_PURPOSE
                : TestSuiteRunEvidenceV5.EXECUTION_PURPOSE;
        return "GRAPH".equals(child.target().kind())
                && suite.target().id().equals(child.target().id())
                && expectedTarget.equals(child.target().fingerprint())
                && expectedTarget.equals(child.evidence().targetFingerprint())
                && expectedPurpose.equals(child.evidence().executionPurpose())
                && "STORED".equals(child.fixtureBundleRef().source())
                && fixture.fixtureBundleId().equals(child.fixtureBundleRef().fixtureBundleId())
                && fixture.revision() == child.fixtureBundleRef().revision()
                && fixture.fingerprint().equals(child.fixtureBundleRef().fingerprint())
                && fixture.fingerprint().equals(child.evidence().fixtureBundleFingerprint());
    }

    private static TestSuiteEvidenceAggregator.CaseObservation rejectedObservation(
            TestSuite.TestCase testCase,
            boolean incomplete,
            String code,
            String diagnostic) {
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), incomplete
                        ? TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                        : TestSuiteRunEvidence.CaseStatus.FAILED,
                        "", null, null, 0, 0, normalized(code), bounded(diagnostic)), null);
    }

    private boolean checkpoint(
            TestSuiteRunRecord record,
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            TestSuiteEvidenceAggregator.TargetState targetState,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        try {
            TestSuiteRunEvidenceV5 running = evidence(stored, suite, request, identity,
                    record.suiteRunId(), record.createdAt(), null, true, false,
                    baseline, matrix, targetState, List.of());
            List<TestSuiteRunAttestation.ChildEvidenceRef> children =
                    childRefs(suite, baseline, matrix);
            TestSuiteRunAttestationService.SealResult seal = attestations.seal(running,
                    record.requestFingerprint(), children, TestSuiteRunAttestation.Scope.CHECKPOINT);
            if (!seal.verified()) {
                return false;
            }
            updateOwned(withEvidence(record, running, "", seal.attestation()), lease);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private TestSuiteExecutionResponse finish(
            TestSuiteRunRecord record,
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            TestSuiteEvidenceAggregator.TargetState targetState,
            boolean forceIncomplete,
            List<String> diagnostics,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TestSuiteRunEvidenceV5 terminal = evidence(stored, suite, request, identity,
                record.suiteRunId(), record.createdAt(), Instant.now(), false,
                forceIncomplete, baseline, matrix, targetState, diagnostics);
        List<TestSuiteRunAttestation.ChildEvidenceRef> children =
                childRefs(suite, baseline, matrix);
        TestSuiteRunRecord completed = terminalRecord(record, terminal, children);
        try {
            return response(updateOwned(completed, lease));
        } catch (RuntimeException persistenceFailure) {
            TestSuiteRunEvidenceV5 incomplete = failClosed(
                    (TestSuiteRunEvidenceV5) completed.evidence(),
                    "SUITE_RUN_TERMINAL_PERSISTENCE_FAILED");
            TestSuiteRunRecord failed = terminalRecord(record, incomplete, children);
            try {
                failed = updateOwned(failed, lease);
            } catch (RuntimeException ignored) {
                // Response remains explicitly incomplete; reconciliation owns the durable checkpoint.
            }
            return response(failed);
        }
    }

    private TestSuiteRunEvidenceV5 evidence(
            StoredTestSuite stored,
            TestSuiteV5 suite,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            String suiteRunId,
            Instant startedAt,
            Instant completedAt,
            boolean running,
            boolean forceIncomplete,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            TestSuiteEvidenceAggregator.TargetState targetState,
            List<String> diagnostics) {
        TestSuiteRunEvidenceV5.BaselineStatus baselineStatus = baselineStatus(baseline, running);
        TestMutationSuiteEvidenceEvaluator.Evaluation evaluated = mutationEvaluator.evaluate(
                suite, baselineStatus, matrix);
        TestSuiteEvidenceAggregator.Aggregate baselineAggregate = baselineAggregator.aggregate(
                suite, baseline, targetState);
        TestSuiteRunEvidence.Status status = running ? TestSuiteRunEvidence.Status.RUNNING
                : forceIncomplete ? TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE
                : terminalStatus(baselineStatus, evaluated.score().status());
        TestSuiteRunEvidence.PromotionVerdict promotion = promotion(
                baselineAggregate.promotion(), evaluated.score(), status, forceIncomplete);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", identity.tenantId());
        metadata.put("organizationId", identity.organizationId());
        metadata.put("projectId", identity.projectId());
        metadata.put("environmentId", identity.environmentId());
        metadata.put("actorId", identity.actorId());
        metadata.put("correlationId", identity.correlationId());
        metadata.put("strategy", request.strategy().name());
        metadata.put("suiteFingerprint", stored.fingerprint());
        metadata.put("requestMetadataFingerprint",
                ProtocolFingerprint.of(objectMapper, request.metadata()));
        metadata.put("baselineChildRunCount", childCount(baseline));
        metadata.put("mutantChildRunCount", matrix.stream()
                .mapToInt(TestMutationSuiteExecutionService::childCount).sum());
        return new TestSuiteRunEvidenceV5("", suiteRunId, request.clientRequestId(), status,
                TestSuiteRunEvidenceV5.EXECUTION_PURPOSE, request.suiteRef(), suite.target(),
                startedAt, completedAt, baseline.stream()
                .map(TestSuiteEvidenceAggregator.CaseObservation::result).toList(),
                baselineAggregate.coverage(), promotion, suite.evaluationMode(),
                suite.sourceFormat(), suite.baselineSourceFingerprint(),
                suite.baselineGraphArtifactFingerprint(), suite.mutationPlanFingerprint(),
                suite.mutationPolicy(), suite.sourcePlanStatus(), suite.planningGapsAccepted(),
                suite.planningGaps(), suite.oracleSuiteRef(), baselineStatus,
                evaluated.mutantResults(), evaluated.score(), diagnostics, Map.copyOf(metadata));
    }

    private static TestSuiteRunEvidence.PromotionVerdict promotion(
            TestSuiteRunEvidence.PromotionVerdict baseline,
            TestSuiteRunEvidenceV5.MutationScoreVerdict score,
            TestSuiteRunEvidence.Status status,
            boolean forceIncomplete) {
        if (status == TestSuiteRunEvidence.Status.RUNNING) {
            return new TestSuiteRunEvidence.PromotionVerdict(
                    TestSuiteRunEvidence.PromotionStatus.NOT_EVALUATED, List.of(),
                    baseline.allCasesPassed(), baseline.certifiableCases(),
                    baseline.minimumCertifiableCases(), baseline.targetCertificationEligible(),
                    baseline.coverageSatisfied(), baseline.allCasesCompleted());
        }
        List<String> reasons = new ArrayList<>(baseline.reasons());
        score.reasons().stream().filter(reason -> !reasons.contains(reason)).forEach(reasons::add);
        if (forceIncomplete && !reasons.contains("EVIDENCE_INCOMPLETE")) {
            reasons.add("EVIDENCE_INCOMPLETE");
        }
        boolean eligible = status == TestSuiteRunEvidence.Status.PASSED
                && baseline.status() == TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                && score.status() == TestSuiteRunEvidenceV5.MutationScoreStatus.SATISFIED
                && reasons.isEmpty();
        return new TestSuiteRunEvidence.PromotionVerdict(eligible
                ? TestSuiteRunEvidence.PromotionStatus.ELIGIBLE
                : TestSuiteRunEvidence.PromotionStatus.BLOCKED, reasons,
                baseline.allCasesPassed(), baseline.certifiableCases(),
                baseline.minimumCertifiableCases(), baseline.targetCertificationEligible(),
                baseline.coverageSatisfied(), baseline.allCasesCompleted());
    }

    private static TestSuiteRunEvidence.Status terminalStatus(
            TestSuiteRunEvidenceV5.BaselineStatus baseline,
            TestSuiteRunEvidenceV5.MutationScoreStatus score) {
        if (baseline == TestSuiteRunEvidenceV5.BaselineStatus.EVIDENCE_INCOMPLETE
                || score == TestSuiteRunEvidenceV5.MutationScoreStatus.INCOMPLETE
                || score == TestSuiteRunEvidenceV5.MutationScoreStatus.NOT_EVALUATED) {
            return TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE;
        }
        if (baseline == TestSuiteRunEvidenceV5.BaselineStatus.FAILED
                || score == TestSuiteRunEvidenceV5.MutationScoreStatus.UNSATISFIED) {
            return TestSuiteRunEvidence.Status.COMPLETED_WITH_FAILURES;
        }
        return TestSuiteRunEvidence.Status.PASSED;
    }

    private static TestSuiteRunEvidenceV5.BaselineStatus baselineStatus(
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            boolean running) {
        List<TestSuiteRunEvidence.CaseStatus> statuses = baseline.stream()
                .map(value -> value.result().status()).toList();
        if (statuses.stream().allMatch(value -> value == TestSuiteRunEvidence.CaseStatus.PENDING)) {
            return TestSuiteRunEvidenceV5.BaselineStatus.PENDING;
        }
        if (running && statuses.stream().anyMatch(
                value -> value == TestSuiteRunEvidence.CaseStatus.PENDING)) {
            return TestSuiteRunEvidenceV5.BaselineStatus.RUNNING;
        }
        if (statuses.stream().allMatch(value -> value == TestSuiteRunEvidence.CaseStatus.PASSED)) {
            return TestSuiteRunEvidenceV5.BaselineStatus.PASSED;
        }
        if (statuses.stream().anyMatch(value -> value
                == TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                || value == TestSuiteRunEvidence.CaseStatus.PENDING
                || value == TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED)) {
            return TestSuiteRunEvidenceV5.BaselineStatus.EVIDENCE_INCOMPLETE;
        }
        return TestSuiteRunEvidenceV5.BaselineStatus.FAILED;
    }

    private TestSuiteRunRecord terminalRecord(
            TestSuiteRunRecord record,
            TestSuiteRunEvidenceV5 evidence,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        TestSuiteRunEvidenceV5 safe = evidence;
        TestSuiteRunAttestationService.SealResult seal = attestations.seal(safe,
                record.requestFingerprint(), children, TestSuiteRunAttestation.Scope.TERMINAL);
        if (!seal.verified()) {
            safe = failClosed(safe, seal.failureCode());
            seal = attestations.seal(safe, record.requestFingerprint(), children,
                    TestSuiteRunAttestation.Scope.TERMINAL);
        }
        return withEvidence(record, safe, evidenceCodec.fingerprint(safe), seal.attestation());
    }

    private static TestSuiteRunEvidenceV5 failClosed(
            TestSuiteRunEvidenceV5 previous,
            String diagnostic) {
        List<String> diagnostics = new ArrayList<>(previous.diagnostics());
        String code = normalized(diagnostic);
        if (!code.isBlank() && !diagnostics.contains(code)) {
            diagnostics.add(code);
        }
        List<String> reasons = new ArrayList<>(previous.promotion().reasons());
        if (!reasons.contains("EVIDENCE_INCOMPLETE")) {
            reasons.add("EVIDENCE_INCOMPLETE");
        }
        TestSuiteRunEvidence.PromotionVerdict blocked =
                new TestSuiteRunEvidence.PromotionVerdict(
                        TestSuiteRunEvidence.PromotionStatus.BLOCKED, reasons,
                        previous.promotion().allCasesPassed(),
                        previous.promotion().certifiableCases(),
                        previous.promotion().minimumCertifiableCases(),
                        previous.promotion().targetCertificationEligible(),
                        previous.promotion().coverageSatisfied(),
                        previous.promotion().allCasesCompleted());
        return new TestSuiteRunEvidenceV5("", previous.suiteRunId(),
                previous.clientRequestId(), TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                previous.executionPurpose(), previous.suiteRef(), previous.target(),
                previous.startedAt(), previous.completedAt() == null
                ? Instant.now() : previous.completedAt(), previous.caseResults(),
                previous.coverage(), blocked, previous.evaluationMode(), previous.sourceFormat(),
                previous.baselineSourceFingerprint(), previous.baselineGraphArtifactFingerprint(),
                previous.mutationPlanFingerprint(), previous.mutationPolicy(),
                previous.sourcePlanStatus(), previous.planningGapsAccepted(),
                previous.planningGaps(), previous.oracleSuiteRef(), previous.baselineStatus(),
                previous.mutantResults(), previous.mutationScore(), diagnostics, previous.metadata());
    }

    private List<TestSuiteRunAttestation.ChildEvidenceRef> childRefs(
            TestSuiteV5 suite,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix) {
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = new ArrayList<>();
        appendChildren(children, "baseline", baseline);
        for (int index = 0; index < suite.mutants().size(); index++) {
            appendChildren(children, suite.mutants().get(index).mutantId(), matrix.get(index));
        }
        return List.copyOf(children);
    }

    private void appendChildren(
            List<TestSuiteRunAttestation.ChildEvidenceRef> target,
            String prefix,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations) {
        for (TestSuiteEvidenceAggregator.CaseObservation observation : observations) {
            if (observation.result().runId().isBlank()) {
                continue;
            }
            if (observation.evidence() == null) {
                throw new IllegalStateException("Child run id has no verified evidence value");
            }
            target.add(new TestSuiteRunAttestation.ChildEvidenceRef(
                    prefix + "/" + observation.result().caseId(),
                    observation.result().runId(),
                    ProtocolFingerprint.of(objectMapper, observation.evidence())));
        }
    }

    private static List<TestSuiteEvidenceAggregator.CaseObservation> pendingCases(
            TestSuiteV5 suite) {
        List<TestSuiteEvidenceAggregator.CaseObservation> result = new ArrayList<>();
        suite.cases().forEach(testCase -> result.add(pending(testCase)));
        return result;
    }

    private static List<List<TestSuiteEvidenceAggregator.CaseObservation>> pendingMatrix(
            TestSuiteV5 suite) {
        List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix = new ArrayList<>();
        for (int index = 0; index < suite.mutants().size(); index++) {
            matrix.add(new ArrayList<>(pendingCases(suite)));
        }
        return matrix;
    }

    private static TestSuiteEvidenceAggregator.CaseObservation pending(TestSuite.TestCase testCase) {
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.PENDING,
                        "", null, null, 0, 0, "", ""), null);
    }

    private static TestSuiteEvidenceAggregator.CaseObservation notScheduled(
            TestSuite.TestCase testCase,
            String code) {
        return new TestSuiteEvidenceAggregator.CaseObservation(
                new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                        testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED,
                        "", null, null, 0, 0, code, ""), null);
    }

    private static void markBaselineRemaining(
            TestSuiteV5 suite,
            List<TestSuiteEvidenceAggregator.CaseObservation> baseline,
            int fromIndex,
            String code) {
        for (int index = fromIndex; index < baseline.size(); index++) {
            if (baseline.get(index).result().status() == TestSuiteRunEvidence.CaseStatus.PENDING) {
                baseline.set(index, notScheduled(suite.cases().get(index), code));
            }
        }
    }

    private static void markAllPending(
            TestSuiteV5 suite,
            List<List<TestSuiteEvidenceAggregator.CaseObservation>> matrix,
            String code) {
        matrix.forEach(row -> markRowRemaining(suite, row, 0, code));
    }

    private static void markRowRemaining(
            TestSuiteV5 suite,
            List<TestSuiteEvidenceAggregator.CaseObservation> row,
            int fromIndex,
            String code) {
        for (int index = fromIndex; index < row.size(); index++) {
            if (row.get(index).result().status() == TestSuiteRunEvidence.CaseStatus.PENDING) {
                row.set(index, notScheduled(suite.cases().get(index), code));
            }
        }
    }

    private static boolean provenKill(TestSuiteEvidenceAggregator.CaseObservation observation) {
        return observation.evidence() != null
                && observation.evidence().status() == TestRunEvidence.Status.ASSERTION_FAILED
                && !observation.result().runId().isBlank();
    }

    private static int childCount(
            List<TestSuiteEvidenceAggregator.CaseObservation> observations) {
        return (int) observations.stream()
                .filter(value -> !value.result().runId().isBlank()).count();
    }

    private static TestExecutionApiRequest.FixtureBundleRef fixtureRef(
            TestSuite.TestCase testCase) {
        return new TestExecutionApiRequest.FixtureBundleRef(
                testCase.fixtureBundleRef().fixtureBundleId(),
                testCase.fixtureBundleRef().revision(),
                testCase.fixtureBundleRef().fingerprint());
    }

    private Map<String, Object> caseMetadata(
            StoredTestSuite stored,
            TestMutationSuiteExecutionRequest request,
            String suiteRunId,
            String coordinate,
            TestSuite.TestCase testCase) {
        return Map.of(
                "suiteRunId", suiteRunId,
                "suiteId", stored.suiteId(),
                "suiteRevision", stored.revision(),
                "suiteFingerprint", stored.fingerprint(),
                "mutationCoordinate", coordinate,
                "caseId", testCase.caseId(),
                "caseType", testCase.caseType().name(),
                "strategy", request.strategy().name());
    }

    private static TestMutationCasePlan mutationPlan(TestSuiteV5 suite) {
        TestMutationCasePlan.MutationPolicy policy = new TestMutationCasePlan.MutationPolicy(
                suite.mutationPolicy().plannerVersion(), suite.mutationPolicy().maxMutants(),
                suite.mutationPolicy().sourceFormat(), suite.mutationPolicy().verificationMode(),
                suite.mutationPolicy().externalOperatorMutation(),
                suite.mutationPolicy().equivalentMutantDetection());
        List<TestMutationCasePlan.PlanningGap> gaps = suite.planningGaps().stream()
                .map(value -> new TestMutationCasePlan.PlanningGap(
                        TestMutationCasePlan.GapCode.valueOf(value.code().name()),
                        value.astPath(), value.mutationKind())).toList();
        List<TestMutationCasePlan.PlannedMutant> mutants = suite.mutants().stream()
                .map(value -> new TestMutationCasePlan.PlannedMutant(value.mutantId(),
                        TestMutationCasePlan.MutationKind.valueOf(value.kind().name()),
                        value.astPath(), value.sourceLine(), value.sourceColumn(),
                        value.mutantSourceFingerprint(), value.mutantGraphArtifactFingerprint(),
                        value.mutantTargetFingerprint(),
                        TestMutationCasePlan.EquivalenceClassification.valueOf(
                                value.equivalenceClassification().name()))).toList();
        return new TestMutationCasePlan("",
                new TestExecutionApiRequest.Target(suite.target().kind(), suite.target().id(),
                        suite.target().fingerprint()), suite.sourceFormat(),
                suite.baselineSourceFingerprint(), suite.baselineGraphArtifactFingerprint(),
                suite.mutationPlanFingerprint(), TestMutationCasePlan.Status.valueOf(
                suite.sourcePlanStatus().name()), policy, mutants, gaps);
    }

    private Optional<TestSuiteRunRecord> findByClientRequestId(
            String clientRequestId,
            IntegrationRequestContext identity) {
        try {
            return runRepository.findByClientRequestId(identity.tenantId(),
                    identity.environmentId(), clientRequestId);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_RUN_STORE_UNAVAILABLE",
                    "The independent suite-run store is unavailable.");
        }
    }

    private TestSuiteExecutionResponse idempotentResponse(
            TestSuiteRunRecord existing,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        requireClearance(existing.classification(), identity);
        if (!requestFingerprint.equals(existing.requestFingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies a different suite execution intent.", Map.of());
        }
        verifyRecord(existing, identity);
        if (!(existing.evidence() instanceof TestSuiteRunEvidenceV5)) {
            throw conflict(identity, "RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT",
                    "clientRequestId belongs to a different suite execution protocol.", Map.of());
        }
        return response(existing);
    }

    private void verifyRecord(
            TestSuiteRunRecord record,
            IntegrationRequestContext identity) {
        TestSuiteRunAttestationService.Verification verification = attestations.verify(
                record.evidence(), record.attestation());
        boolean running = record.evidence().status() == TestSuiteRunEvidence.Status.RUNNING;
        boolean scope = record.attestation().scope() == (running
                ? TestSuiteRunAttestation.Scope.CHECKPOINT
                : TestSuiteRunAttestation.Scope.TERMINAL);
        boolean fingerprint = running ? record.evidenceFingerprint().isBlank()
                : record.evidenceFingerprint().equals(
                record.attestation().aggregateEvidenceFingerprint());
        if (verification == TestSuiteRunAttestationService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.SUITE_ATTESTATION_VERIFICATION_UNAVAILABLE",
                    "Mutation suite evidence verification is unavailable.");
        }
        if (verification != TestSuiteRunAttestationService.Verification.VERIFIED
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || !scope || !fingerprint || !closureMatches(record)) {
            securityEvent(identity, "TEST_SUITE_ATTESTATION_INVALID", "REJECTED",
                    "RG.TEST.SUITE_ATTESTATION_INVALID",
                    Map.of("suiteRunId", record.suiteRunId()));
            throw conflict(identity, "RG.TEST.SUITE_ATTESTATION_INVALID",
                    "Mutation evidence or child closure failed integrity verification.", Map.of());
        }
    }

    private static boolean closureMatches(TestSuiteRunRecord record) {
        if (!(record.evidence() instanceof TestSuiteRunEvidenceV5 evidence)) {
            return false;
        }
        List<ExpectedChild> expected = new ArrayList<>();
        evidence.caseResults().stream().filter(value -> !value.runId().isBlank())
                .forEach(value -> expected.add(new ExpectedChild(
                        "baseline/" + value.caseId(), value.runId())));
        evidence.mutantResults().forEach(mutant -> mutant.caseResults().stream()
                .filter(value -> !value.runId().isBlank())
                .forEach(value -> expected.add(new ExpectedChild(
                        mutant.mutant().mutantId() + "/" + value.caseId(), value.runId()))));
        List<TestSuiteRunAttestation.ChildEvidenceRef> actual =
                record.attestation().childEvidenceRefs();
        if (expected.size() != actual.size()) {
            return false;
        }
        for (int index = 0; index < expected.size(); index++) {
            if (!expected.get(index).caseId().equals(actual.get(index).caseId())
                    || !expected.get(index).runId().equals(actual.get(index).runId())) {
                return false;
            }
        }
        return true;
    }

    private TestSuiteRunRecord updateOwned(
            TestSuiteRunRecord record,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        Instant observedAt = runRepository.currentTime();
        return runRepository.update(record, lease.renewal(), observedAt);
    }

    private static TestSuiteRunRecord withEvidence(
            TestSuiteRunRecord record,
            TestSuiteRunEvidenceV5 evidence,
            String fingerprint,
            TestSuiteRunAttestation attestation) {
        return new TestSuiteRunRecord(record.suiteRunId(), record.clientRequestId(),
                record.requestFingerprint(), record.tenantId(), record.organizationId(),
                record.projectId(), record.environmentId(), record.actorId(),
                record.classification(), fingerprint, evidence, attestation,
                record.createdAt(), record.expiresAt());
    }

    private static TestSuiteExecutionResponse response(TestSuiteRunRecord record) {
        return new TestSuiteExecutionResponse(TestSuiteExecutionResponse.SCHEMA_VERSION_V6,
                record.suiteRunId(), record.evidenceFingerprint(), record.evidence(),
                record.attestation());
    }

    private void validateRequest(
            String pathSuiteId,
            TestMutationSuiteExecutionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !TestMutationSuiteExecutionRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.suiteRef() == null || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || request.suiteRef().revision() <= 0
                || !validFingerprint(request.suiteRef().fingerprint())
                || request.clientRequestId().isBlank()
                || request.clientRequestId().length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw badRequest(identity, "RG.TEST.MUTATION_EXECUTION_REQUEST_INVALID",
                    "Path, exact suite reference, schemaVersion, and clientRequestId are required.",
                    Map.of());
        }
        if (request.metadata().containsKey(null) || request.metadata().containsValue(null)) {
            throw badRequest(identity, "RG.TEST.METADATA_INVALID",
                    "Mutation execution metadata cannot contain null keys or values.", Map.of());
        }
        requireBounded(request.metadata(), MAX_METADATA_BYTES, identity);
    }

    private void requireExecutionIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            securityEvent(identity, "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                    "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of());
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Mutation execution is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
        if (!EXECUTION_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.EXECUTION_IDENTITY_PURPOSE_INVALID",
                    "Verified identity purpose cannot execute mutation suites.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void requireClearance(
            String classification,
            IntegrationRequestContext identity) {
        String required = normalized(classification).isBlank()
                ? "INTERNAL" : normalized(classification).toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_INVALID",
                    "Suite classification is invalid.", Map.of());
        }
        if (!identity.hasClearanceAtLeast(required)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot execute this suite.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void requireBounded(
            Object value,
            int maximumBytes,
            IntegrationRequestContext identity) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maximumBytes) {
                throw badRequest(identity, "RG.TEST.REQUEST_FIELD_TOO_LARGE",
                        "metadata exceeds its protocol bound.", Map.of("maximumBytes", maximumBytes));
            }
        } catch (JsonProcessingException invalid) {
            throw badRequest(identity, "RG.TEST.REQUEST_FIELD_INVALID",
                    "metadata cannot be serialized as protocol JSON.", Map.of());
        }
    }

    private void securityEvent(
            IntegrationRequestContext identity,
            String type,
            String outcome,
            String reason,
            Map<String, Object> facts) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                    identity.tenantId(), identity.environmentId(), identity.actorId(), type,
                    outcome, reason, facts));
        } catch (RuntimeException unavailable) {
            throw new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                    "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Mutation execution requires an available security audit sink.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static TestExecutionApiRequest.Target target(TestSuiteV5 suite) {
        return new TestExecutionApiRequest.Target(suite.target().kind(), suite.target().id(),
                suite.target().fingerprint());
    }

    private static boolean validFingerprint(String value) {
        return FINGERPRINT.matcher(normalized(value)).matches();
    }

    private static String bounded(String value) {
        String safe = normalized(value);
        return safe.length() <= MAX_DIAGNOSTIC_LENGTH
                ? safe : safe.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title,
            Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private record ExpectedChild(String caseId, String runId) {
    }
}
