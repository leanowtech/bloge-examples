package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV2;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteEvidenceBundle;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunAttestation;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteRunEvidenceV2;
import com.leanowtech.bloge.gateway.testing.domain.SemanticCoverageVerdict;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteEvidenceAggregator;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteRunEvidenceProtocolCodec;
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
 * Idempotent runner for exact immutable test-suite revisions.
 *
 * <p>The runner persists a RUNNING checkpoint before invoking the first case and after every child
 * run. Fail-fast stops scheduling only; it never cancels an already executing case. Child payloads
 * remain in the independently governed run store while this aggregate retains stable run ids and
 * server-derived coverage facts.</p>
 */
public final class TestSuiteExecutionService {

    /** Fixed server-authorized purpose recorded in aggregate suite evidence. */
    public static final String AUTHORIZED_PURPOSE = "TEST_SUITE_EXECUTION";

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
    private final TestSuiteEvidenceAggregator aggregator;
    private final TestSuiteRunEvidenceProtocolCodec evidenceCodec;
    private final Duration retention;

    /**
     * Creates a suite runner over the governed registry, child execution API, and independent store.
     *
     * @param suiteRegistry immutable suite registry
     * @param executions authorized graph and operator execution adapter
     * @param runRepository durable aggregate checkpoint store
     * @param objectMapper canonical protocol serializer
     * @param securityEvents mandatory fail-closed security audit sink
     * @param retention suite-run evidence retention
     */
    public TestSuiteExecutionService(TestSuiteRegistryService suiteRegistry,
                                     TestExecutionApiService executions,
                                     TestSuiteRunRepository runRepository,
                                     ObjectMapper objectMapper,
                                     TestSecurityEventRepository securityEvents,
                                     Duration retention) {
        this(suiteRegistry, executions, runRepository, objectMapper, securityEvents, retention,
                TestSuiteRunLeaseCoordinator.passive(Duration.ofMinutes(5)),
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()));
    }

    /** Creates a runner with an explicit process-wide lease coordinator. */
    public TestSuiteExecutionService(TestSuiteRegistryService suiteRegistry,
                                     TestExecutionApiService executions,
                                     TestSuiteRunRepository runRepository,
                                     ObjectMapper objectMapper,
                                     TestSecurityEventRepository securityEvents,
                                     Duration retention,
                                     TestSuiteRunLeaseCoordinator leaseCoordinator) {
        this(suiteRegistry, executions, runRepository, objectMapper, securityEvents, retention,
                leaseCoordinator,
                new TestSuiteRunAttestationService(objectMapper, VisualEvidenceSigner.unavailable()));
    }

    /**
     * Creates a runner with explicit distributed lease and suite attestation boundaries.
     *
     * @param suiteRegistry immutable suite registry
     * @param executions authorized graph and operator execution adapter
     * @param runRepository durable aggregate checkpoint store
     * @param objectMapper canonical protocol serializer
     * @param securityEvents mandatory fail-closed security audit sink
     * @param retention suite-run evidence retention
     * @param leaseCoordinator process-wide lease coordinator
     * @param attestations checkpoint and terminal signing boundary
     */
    public TestSuiteExecutionService(TestSuiteRegistryService suiteRegistry,
                                     TestExecutionApiService executions,
                                     TestSuiteRunRepository runRepository,
                                     ObjectMapper objectMapper,
                                     TestSecurityEventRepository securityEvents,
                                     Duration retention,
                                     TestSuiteRunLeaseCoordinator leaseCoordinator,
                                     TestSuiteRunAttestationService attestations) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.executions = Objects.requireNonNull(executions, "executions");
        this.runRepository = Objects.requireNonNull(runRepository, "runRepository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.aggregator = new TestSuiteEvidenceAggregator(objectMapper);
        this.evidenceCodec = new TestSuiteRunEvidenceProtocolCodec(objectMapper);
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
    }

    /**
     * Executes or idempotently resolves one exact suite revision.
     *
     * @param suiteId path-bound suite identifier
     * @param request exact suite reference and scheduling intent
     * @param identity verified test-execution workload identity
     * @return latest durable aggregate checkpoint or terminal evidence
     */
    public TestSuiteExecutionResponse execute(String suiteId,
                                              TestSuiteExecutionRequest request,
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
                    "Stored suite fingerprint differs from the exact execution reference.", Map.of());
        }

        Instant startedAt = Instant.now();
        String suiteRunId = UUID.randomUUID().toString();
        List<TestSuiteEvidenceAggregator.CaseObservation> observations = pending(stored.suite());
        TestSuiteRunEvidenceProtocol running = evidence(stored, request, identity, suiteRunId, startedAt,
                null, TestSuiteRunEvidence.Status.RUNNING, observations,
                pendingCoverage(stored.suite()), pendingSemanticCoverage(stored.suite()),
                pendingPromotion(stored.suite()), List.of());
        TestSuiteRunAttestationService.SealResult initialSeal = attestations.seal(running,
                requestFingerprint, List.of(), TestSuiteRunAttestation.Scope.CHECKPOINT);
        if (!initialSeal.verified()) {
            throw unavailable(identity, "RG.TEST.SUITE_ATTESTATION_UNAVAILABLE",
                    "Suite execution cannot start because its initial checkpoint cannot be signed.");
        }
        TestSuiteRunRecord record = new TestSuiteRunRecord(suiteRunId, request.clientRequestId(),
                requestFingerprint, identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.actorId(), stored.suite().classification(), "", running,
                initialSeal.attestation(),
                startedAt, startedAt.plus(retention));
        try {
            record = runRepository.create(record, leaseCoordinator.newLease());
        } catch (TestSuiteRunConflictException race) {
            TestSuiteRunRecord winner = findByClientRequestId(request.clientRequestId(), identity)
                    .orElseThrow(() -> conflict(identity, "RG.TEST.SUITE_RUN_IDEMPOTENCY_RETIRED",
                            "clientRequestId is already reserved by expired or retired evidence; use a new key.",
                            Map.of()));
            return idempotentResponse(winner, requestFingerprint, identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_RUN_STORE_UNAVAILABLE",
                    "The independent suite-run store is unavailable.");
        }

        try (TestSuiteRunLeaseCoordinator.LeaseGuard lease = leaseCoordinator.monitor(record)) {
            return executeOwned(record, stored, request, identity, observations, lease);
        }
    }

    private TestSuiteExecutionResponse executeOwned(
            TestSuiteRunRecord record, StoredTestSuite stored, TestSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TargetPreflight target;
        try {
            target = currentTarget(stored.suite(), identity);
        } catch (IntegrationProblemException rejected) {
            return finishWithoutCases(record, stored, request, identity, observations, lease,
                    rejected.problem().code(), rejected.problem().title());
        } catch (RuntimeException failure) {
            return finishWithoutCases(record, stored, request, identity, observations, lease,
                    "TARGET_PREFLIGHT_UNAVAILABLE",
                    "Target preflight failed before any suite case was scheduled.");
        }
        if (!stored.suite().target().fingerprint().equals(target.fingerprint())) {
            return finishWithoutCases(record, stored, request, identity, observations, lease,
                    "TARGET_FINGERPRINT_CONFLICT",
                    "The suite target changed after this immutable revision was registered.");
        }

        for (int index = 0; index < stored.suite().cases().size(); index++) {
            if (!lease.held()) {
                markRemaining(observations, stored.suite(), index, "SUITE_RUN_LEASE_LOST",
                        "Case scheduling stopped because active suite-run ownership could not be renewed.");
                return finishEvidenceIncomplete(record, stored, request, identity, observations,
                        target.state(), "SUITE_RUN_LEASE_LOST", lease);
            }
            TestSuite.TestCase testCase = stored.suite().cases().get(index);
            observations.set(index, executeCase(stored, request, record.suiteRunId(), testCase, identity));
            try {
                checkpoint(record, stored, request, identity, observations, lease);
            } catch (RuntimeException persistenceFailure) {
                markRemaining(observations, stored.suite(), index + 1,
                        "SUITE_RUN_STORE_UNAVAILABLE",
                        "Case scheduling stopped because aggregate progress could not be persisted.");
                return finishEvidenceIncomplete(record, stored, request, identity, observations,
                        target.state(), "SUITE_RUN_STORE_UNAVAILABLE", lease);
            }
            if (request.strategy() == TestSuiteExecutionRequest.Strategy.FAIL_FAST
                    && observations.get(index).result().status()
                    != TestSuiteRunEvidence.CaseStatus.PASSED) {
                markRemaining(observations, stored.suite(), index + 1,
                        "FAIL_FAST_STOP",
                        "Not scheduled after an earlier case failed under FAIL_FAST.");
                break;
            }
        }
        return finish(record, stored, request, identity, observations, target.state(), List.of(), lease);
    }

    /**
     * Resolves one persisted suite-run checkpoint in the caller's exact scope.
     *
     * @param suiteRunId server-minted aggregate run id
     * @param identity verified test-execution identity
     * @return current or terminal aggregate response
     */
    public TestSuiteExecutionResponse find(String suiteRunId, IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        if (normalized(suiteRunId).isBlank()) {
            throw badRequest(identity, "RG.TEST.SUITE_RUN_ID_INVALID",
                    "A non-empty suiteRunId is required.", Map.of());
        }
        TestSuiteRunRecord record;
        try {
            record = runRepository.find(identity.tenantId(), identity.environmentId(), normalized(suiteRunId))
                    .orElseThrow(() -> new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.SUITE_RUN_NOT_FOUND",
                            "Suite run was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException notFound) {
            throw notFound;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_RUN_STORE_UNAVAILABLE",
                    "The independent suite-run store is unavailable.");
        }
        requireClearance(record.classification(), identity);
        verifyRecord(record, identity);
        return response(record);
    }

    /**
     * Exports a terminal, payload-free suite evidence bundle for offline verification.
     *
     * @param suiteRunId server-minted aggregate run id
     * @param identity verified test-execution identity
     * @return canonical terminal aggregate, attestation, and bundle fingerprint
     */
    public TestSuiteEvidenceBundle evidenceBundle(String suiteRunId,
                                                  IntegrationRequestContext identity) {
        TestSuiteExecutionResponse response = find(suiteRunId, identity);
        if (!response.attestation().terminallyVerifiable()) {
            throw conflict(identity, "RG.TEST.SUITE_EVIDENCE_NOT_TERMINAL",
                    "A portable evidence bundle is available only for a verified terminal suite run.",
                    Map.of("suiteRunId", response.suiteRunId()));
        }
        BundleMaterial material = new BundleMaterial(TestSuiteEvidenceBundle.PayloadPolicy.OMITTED,
                response.attestation(), response.evidence());
        String schemaVersion = response.evidence() instanceof TestSuiteRunEvidenceV2
                ? TestSuiteEvidenceBundle.SCHEMA_VERSION_V2 : TestSuiteEvidenceBundle.SCHEMA_VERSION;
        return new TestSuiteEvidenceBundle(schemaVersion, response.suiteRunId(),
                ProtocolFingerprint.of(objectMapper, material), material.payloadPolicy(),
                material.attestation(), material.evidence());
    }

    private TestSuiteEvidenceAggregator.CaseObservation executeCase(
            StoredTestSuite stored, TestSuiteExecutionRequest request, String suiteRunId,
            TestSuite.TestCase testCase, IntegrationRequestContext identity) {
        TestSuiteProtocol suite = stored.suite();
        TestExecutionApiRequest.FixtureBundleRef fixtureRef = new TestExecutionApiRequest.FixtureBundleRef(
                testCase.fixtureBundleRef().fixtureBundleId(), testCase.fixtureBundleRef().revision(),
                testCase.fixtureBundleRef().fingerprint());
        Map<String, Object> metadata = caseMetadata(stored, request, suiteRunId, testCase);
        try {
            TestExecutionApiResponse child;
            if ("GRAPH".equals(suite.target().kind())) {
                Map<String, Object> context = objectMapper.convertValue(testCase.input(),
                        new TypeReference<>() { });
                child = executions.execute(new TestExecutionApiRequest("",
                        target(suite), TestExecutionApiService.AUTHORIZED_PURPOSE, context,
                        null, fixtureRef, TestExecutionApiRequest.Verbosity.FULL, metadata), identity);
            } else {
                child = executions.executeOperator(suite.target().id(),
                        new TestOperatorExecutionApiRequest("", target(suite),
                                TestExecutionApiService.AUTHORIZED_OPERATOR_PURPOSE, testCase.input(),
                                null, fixtureRef, TestExecutionApiRequest.Verbosity.FULL, metadata), identity);
            }
            return childObservation(suite, testCase, child);
        } catch (IntegrationProblemException rejected) {
            TestSuiteRunEvidence.CaseStatus status = rejected.problem().status() >= 500
                    ? TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE
                    : TestSuiteRunEvidence.CaseStatus.FAILED;
            return new TestSuiteEvidenceAggregator.CaseObservation(new TestSuiteRunEvidence.CaseResult(
                    testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(), status,
                    "", null, null, 0, 0, rejected.problem().code(),
                    bounded(rejected.problem().title())), null);
        } catch (RuntimeException failure) {
            return new TestSuiteEvidenceAggregator.CaseObservation(new TestSuiteRunEvidence.CaseResult(
                    testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(),
                    TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE, "", null, null,
                    0, 0, "RG.TEST.SUITE_CASE_UNEXPECTED_FAILURE",
                    "Child execution failed without durable evidence: "
                            + failure.getClass().getSimpleName()), null);
        }
    }

    private TestSuiteEvidenceAggregator.CaseObservation childObservation(
            TestSuiteProtocol suite, TestSuite.TestCase testCase, TestExecutionApiResponse child) {
        if (!validChildIdentity(suite, testCase, child)) {
            return new TestSuiteEvidenceAggregator.CaseObservation(new TestSuiteRunEvidence.CaseResult(
                    testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(),
                    TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE, "", null, null,
                    0, 0, "RG.TEST.SUITE_CHILD_EVIDENCE_IDENTITY_INVALID",
                    "Child evidence did not preserve the suite target, fixture, and run identity."), null);
        }
        if (!executions.verifyEvidence(child)) {
            return new TestSuiteEvidenceAggregator.CaseObservation(new TestSuiteRunEvidence.CaseResult(
                    testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(),
                    TestSuiteRunEvidence.CaseStatus.EVIDENCE_INCOMPLETE, "", null, null,
                    0, 0, "RG.TEST.SUITE_CHILD_EVIDENCE_INTEGRITY_INVALID",
                    "Child evidence was not a complete independently verifiable signed value."), null);
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
        String diagnostic = evidence.diagnostics().stream().findFirst().map(this::bounded).orElse("");
        return new TestSuiteEvidenceAggregator.CaseObservation(new TestSuiteRunEvidence.CaseResult(
                testCase.caseId(), testCase.caseType(), testCase.fixtureBundleRef(), status,
                child.runId(), evidence.status(), evidence.evidenceClass(), assertions, passed,
                status == TestSuiteRunEvidence.CaseStatus.PASSED ? "" : evidence.status().name(),
                diagnostic), evidence);
    }

    private static boolean validChildIdentity(TestSuiteProtocol suite, TestSuite.TestCase testCase,
                                              TestExecutionApiResponse child) {
        if (child == null || child.evidence() == null || child.target() == null
                || child.fixtureBundleRef() == null || child.runId().isBlank()
                || !child.runId().equals(child.evidence().runId())) {
            return false;
        }
        TestSuite.FixtureBundleRef expectedFixture = testCase.fixtureBundleRef();
        return suite.target().kind().equals(child.target().kind())
                && suite.target().id().equals(child.target().id())
                && suite.target().fingerprint().equals(child.target().fingerprint())
                && suite.target().fingerprint().equals(child.evidence().targetFingerprint())
                && "STORED".equals(child.fixtureBundleRef().source())
                && expectedFixture.fixtureBundleId().equals(child.fixtureBundleRef().fixtureBundleId())
                && expectedFixture.revision() == child.fixtureBundleRef().revision()
                && expectedFixture.fingerprint().equals(child.fixtureBundleRef().fingerprint())
                && expectedFixture.fingerprint().equals(child.evidence().fixtureBundleFingerprint());
    }

    private TargetPreflight currentTarget(TestSuiteProtocol suite,
                                          IntegrationRequestContext identity) {
        if ("GRAPH".equals(suite.target().kind())) {
            TestGraphTargetDescriptor descriptor = executions.describeGraphTarget(suite.target().id(), identity);
            return new TargetPreflight(descriptor.target().fingerprint(),
                    new TestSuiteEvidenceAggregator.TargetState(
                            suite.target().fingerprint().equals(descriptor.target().fingerprint()),
                            descriptor.certificationEligible()));
        }
        TestOperatorTargetDescriptor descriptor = executions.describeOperatorTarget(suite.target().id(), identity);
        return new TargetPreflight(descriptor.target().fingerprint(),
                new TestSuiteEvidenceAggregator.TargetState(
                        suite.target().fingerprint().equals(descriptor.target().fingerprint()),
                        descriptor.certificationEligible()));
    }

    private TestSuiteExecutionResponse finishWithoutCases(
            TestSuiteRunRecord record, StoredTestSuite stored, TestSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease,
            String diagnosticCode, String diagnostic) {
        markRemaining(observations, stored.suite(), 0, diagnosticCode, diagnostic);
        return finish(record, stored, request, identity, observations,
                new TestSuiteEvidenceAggregator.TargetState(false, false), List.of(diagnosticCode), lease);
    }

    private TestSuiteExecutionResponse finish(
            TestSuiteRunRecord record, StoredTestSuite stored, TestSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
            TestSuiteEvidenceAggregator.TargetState targetState, List<String> diagnostics,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TestSuiteEvidenceAggregator.Aggregate aggregate = aggregator.aggregate(
                stored.suite(), observations, targetState);
        TestSuiteRunEvidenceProtocol terminal = evidence(stored, request, identity, record.suiteRunId(),
                record.createdAt(), Instant.now(), aggregate.status(), observations,
                aggregate.coverage(), aggregate.semanticCoverage(), aggregate.promotion(), diagnostics);
        TestSuiteRunRecord completed = terminalRecord(record, terminal, observations);
        try {
            return response(updateOwned(completed, lease));
        } catch (RuntimeException persistenceFailure) {
            return persistIncompleteBestEffort(completed, "SUITE_RUN_TERMINAL_PERSISTENCE_FAILED", lease);
        }
    }

    private TestSuiteExecutionResponse finishEvidenceIncomplete(
            TestSuiteRunRecord record, StoredTestSuite stored, TestSuiteExecutionRequest request,
            IntegrationRequestContext identity,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
            TestSuiteEvidenceAggregator.TargetState targetState, String diagnostic,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TestSuiteEvidenceAggregator.Aggregate aggregate = aggregator.aggregate(
                stored.suite(), observations, targetState);
        TestSuiteRunEvidenceProtocol incomplete = evidence(stored, request, identity, record.suiteRunId(),
                record.createdAt(), Instant.now(), TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE,
                observations, aggregate.coverage(), aggregate.semanticCoverage(), aggregate.promotion(),
                List.of(diagnostic));
        TestSuiteRunRecord completed = terminalRecord(record, incomplete, observations);
        try {
            updateOwned(completed, lease);
        } catch (RuntimeException ignored) {
            // The response remains explicit about incomplete persistence and exposes child run ids.
        }
        return response(completed);
    }

    private TestSuiteExecutionResponse persistIncompleteBestEffort(
            TestSuiteRunRecord record, String diagnostic,
            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TestSuiteRunEvidenceProtocol incomplete = failClosed(record.evidence(), diagnostic);
        TestSuiteRunRecord failed = terminalRecordFromChildren(record, incomplete,
                record.attestation().childEvidenceRefs());
        try {
            failed = updateOwned(failed, lease);
        } catch (RuntimeException ignored) {
            // Child run ids remain visible in this fail-closed response even if the store stays down.
        }
        return response(failed);
    }

    private void checkpoint(TestSuiteRunRecord record, StoredTestSuite stored,
                            TestSuiteExecutionRequest request, IntegrationRequestContext identity,
                            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
                            TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        TestSuiteRunEvidenceProtocol running = evidence(stored, request, identity, record.suiteRunId(),
                record.createdAt(), null, TestSuiteRunEvidence.Status.RUNNING, observations,
                pendingCoverage(stored.suite()), pendingSemanticCoverage(stored.suite()),
                pendingPromotion(stored.suite()), List.of());
        TestSuiteRunAttestationService.SealResult seal = attestations.seal(running,
                record.requestFingerprint(), childRefs(observations),
                TestSuiteRunAttestation.Scope.CHECKPOINT);
        if (!seal.verified()) {
            throw new IllegalStateException(seal.failureCode());
        }
        updateOwned(withEvidence(record, running, "", seal.attestation()), lease);
    }

    private TestSuiteRunRecord updateOwned(TestSuiteRunRecord record,
                                           TestSuiteRunLeaseCoordinator.LeaseGuard lease) {
        Instant observedAt = runRepository.currentTime();
        return runRepository.update(record, lease.renewal(), observedAt);
    }

    private TestSuiteRunEvidenceProtocol evidence(
            StoredTestSuite stored, TestSuiteExecutionRequest request, IntegrationRequestContext identity,
            String suiteRunId, Instant startedAt, Instant completedAt, TestSuiteRunEvidence.Status status,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations,
            TestSuiteRunEvidence.CoverageVerdict coverage,
            SemanticCoverageVerdict semanticCoverage,
            TestSuiteRunEvidence.PromotionVerdict promotion, List<String> diagnostics) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tenantId", identity.tenantId());
        metadata.put("organizationId", identity.organizationId());
        metadata.put("projectId", identity.projectId());
        metadata.put("environmentId", identity.environmentId());
        metadata.put("actorId", identity.actorId());
        metadata.put("correlationId", identity.correlationId());
        metadata.put("strategy", request.strategy().name());
        metadata.put("requestMetadataFingerprint", ProtocolFingerprint.of(objectMapper,
                request.metadata()));
        List<TestSuiteRunEvidence.CaseResult> results = observations.stream()
                .map(TestSuiteEvidenceAggregator.CaseObservation::result).toList();
        if (stored.suite() instanceof TestSuiteV2) {
            return new TestSuiteRunEvidenceV2("", suiteRunId, request.clientRequestId(), status,
                    AUTHORIZED_PURPOSE, request.suiteRef(), stored.suite().target(), startedAt,
                    completedAt, results, coverage, semanticCoverage, promotion, diagnostics, metadata);
        }
        return new TestSuiteRunEvidence("", suiteRunId, request.clientRequestId(), status,
                AUTHORIZED_PURPOSE, request.suiteRef(), stored.suite().target(), startedAt,
                completedAt, results, coverage, promotion, diagnostics, metadata);
    }

    private static List<TestSuiteEvidenceAggregator.CaseObservation> pending(TestSuiteProtocol suite) {
        List<TestSuiteEvidenceAggregator.CaseObservation> observations = new ArrayList<>();
        for (TestSuite.TestCase testCase : suite.cases()) {
            observations.add(new TestSuiteEvidenceAggregator.CaseObservation(
                    new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                            testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.PENDING,
                            "", null, null, 0, 0, "", ""), null));
        }
        return observations;
    }

    private static TestSuiteRunEvidence.CoverageVerdict pendingCoverage(TestSuiteProtocol suite) {
        TestSuite.CoveragePolicy policy = suite.coveragePolicy();
        return new TestSuiteRunEvidence.CoverageVerdict(
                TestSuiteRunEvidence.CoverageStatus.NOT_EVALUATED, policy.minimumCases(), 0,
                policy.requiredCaseTypes(), List.of(), policy.requiredCaseTypes(),
                policy.requiredInvocationSiteIds(), List.of(), policy.requiredInvocationSiteIds(),
                policy.requiredEdgeTransfers(), List.of(), policy.requiredEdgeTransfers(),
                policy.minimumAssertionsPerCase(), List.of(), List.of(), false);
    }

    private static SemanticCoverageVerdict pendingSemanticCoverage(TestSuiteProtocol suite) {
        return suite instanceof TestSuiteV2 v2
                ? SemanticCoverageVerdict.notEvaluated(v2.semanticCoveragePolicy().requirements())
                : SemanticCoverageVerdict.notEvaluated(List.of());
    }

    private static TestSuiteRunEvidence.PromotionVerdict pendingPromotion(TestSuiteProtocol suite) {
        return new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.NOT_EVALUATED, List.of(), false, 0,
                suite.promotionPolicy().minimumCertifiableCases(), false, false, false);
    }

    private static void markRemaining(List<TestSuiteEvidenceAggregator.CaseObservation> observations,
                                      TestSuiteProtocol suite, int fromIndex, String code,
                                      String diagnostic) {
        for (int index = fromIndex; index < suite.cases().size(); index++) {
            TestSuite.TestCase testCase = suite.cases().get(index);
            observations.set(index, new TestSuiteEvidenceAggregator.CaseObservation(
                    new TestSuiteRunEvidence.CaseResult(testCase.caseId(), testCase.caseType(),
                            testCase.fixtureBundleRef(), TestSuiteRunEvidence.CaseStatus.NOT_SCHEDULED,
                            "", null, null, 0, 0, code, diagnostic), null));
        }
    }

    private static Map<String, Object> caseMetadata(
            StoredTestSuite stored, TestSuiteExecutionRequest request, String suiteRunId,
            TestSuite.TestCase testCase) {
        return Map.of(
                "suiteRunId", suiteRunId,
                "clientRequestId", request.clientRequestId(),
                "suiteId", stored.suiteId(),
                "suiteRevision", stored.revision(),
                "suiteFingerprint", stored.fingerprint(),
                "caseId", testCase.caseId(),
                "caseType", testCase.caseType().name());
    }

    private static TestExecutionApiRequest.Target target(TestSuiteProtocol suite) {
        return new TestExecutionApiRequest.Target(suite.target().kind(), suite.target().id(),
                suite.target().fingerprint());
    }

    private Optional<TestSuiteRunRecord> findByClientRequestId(
            String clientRequestId, IntegrationRequestContext identity) {
        try {
            return runRepository.findByClientRequestId(identity.tenantId(), identity.environmentId(),
                    clientRequestId);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.SUITE_RUN_STORE_UNAVAILABLE",
                    "The independent suite-run store is unavailable.");
        }
    }

    private TestSuiteExecutionResponse idempotentResponse(
            TestSuiteRunRecord existing, String requestFingerprint, IntegrationRequestContext identity) {
        requireClearance(existing.classification(), identity);
        if (!requestFingerprint.equals(existing.requestFingerprint())) {
            throw conflict(identity, "RG.TEST.SUITE_RUN_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies a different suite execution intent.", Map.of());
        }
        verifyRecord(existing, identity);
        return response(existing);
    }

    private TestSuiteRunRecord terminalRecord(
            TestSuiteRunRecord record, TestSuiteRunEvidenceProtocol evidence,
            List<TestSuiteEvidenceAggregator.CaseObservation> observations) {
        return terminalRecordFromChildren(record, evidence, childRefs(observations));
    }

    private TestSuiteRunRecord terminalRecordFromChildren(
            TestSuiteRunRecord record, TestSuiteRunEvidenceProtocol evidence,
            List<TestSuiteRunAttestation.ChildEvidenceRef> children) {
        TestSuiteRunEvidenceProtocol safeEvidence = evidence;
        TestSuiteRunAttestationService.SealResult seal = attestations.seal(safeEvidence,
                record.requestFingerprint(), children, TestSuiteRunAttestation.Scope.TERMINAL);
        if (!seal.verified()) {
            safeEvidence = failClosed(safeEvidence, seal.failureCode());
            seal = attestations.seal(safeEvidence, record.requestFingerprint(), children,
                    TestSuiteRunAttestation.Scope.TERMINAL);
        }
        String fingerprint = evidenceCodec.fingerprint(safeEvidence);
        return withEvidence(record, safeEvidence, fingerprint, seal.attestation());
    }

    private static TestSuiteRunEvidenceProtocol failClosed(TestSuiteRunEvidenceProtocol previous,
                                                           String diagnostic) {
        List<String> diagnostics = new ArrayList<>(previous.diagnostics());
        if (diagnostic != null && !diagnostic.isBlank() && !diagnostics.contains(diagnostic)) {
            diagnostics.add(diagnostic);
        }
        List<String> promotionReasons = new ArrayList<>(previous.promotion().reasons());
        if (!promotionReasons.contains("EVIDENCE_INCOMPLETE")) {
            promotionReasons.add("EVIDENCE_INCOMPLETE");
        }
        TestSuiteRunEvidence.PromotionVerdict blocked = new TestSuiteRunEvidence.PromotionVerdict(
                TestSuiteRunEvidence.PromotionStatus.BLOCKED, promotionReasons,
                previous.promotion().allCasesPassed(), previous.promotion().certifiableCases(),
                previous.promotion().minimumCertifiableCases(),
                previous.promotion().targetCertificationEligible(),
                previous.promotion().coverageSatisfied(), previous.promotion().allCasesCompleted());
        if (previous instanceof TestSuiteRunEvidenceV2 v2) {
            SemanticCoverageVerdict semantic = new SemanticCoverageVerdict(
                    SemanticCoverageVerdict.Status.INCOMPLETE,
                    v2.semanticCoverage().required(), v2.semanticCoverage().observed(),
                    v2.semanticCoverage().missingRequirementIds(),
                    v2.semanticCoverage().unavailable());
            return new TestSuiteRunEvidenceV2("", previous.suiteRunId(), previous.clientRequestId(),
                    TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, previous.executionPurpose(),
                    previous.suiteRef(), previous.target(), previous.startedAt(), previous.completedAt(),
                    previous.caseResults(), previous.coverage(), semantic, blocked, diagnostics,
                    previous.metadata());
        }
        return new TestSuiteRunEvidence("", previous.suiteRunId(), previous.clientRequestId(),
                TestSuiteRunEvidence.Status.EVIDENCE_INCOMPLETE, previous.executionPurpose(),
                previous.suiteRef(), previous.target(), previous.startedAt(), previous.completedAt(),
                previous.caseResults(), previous.coverage(), blocked, diagnostics, previous.metadata());
    }

    private List<TestSuiteRunAttestation.ChildEvidenceRef> childRefs(
            List<TestSuiteEvidenceAggregator.CaseObservation> observations) {
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = new ArrayList<>();
        for (TestSuiteEvidenceAggregator.CaseObservation observation : observations) {
            if (observation.result().runId().isBlank()) {
                continue;
            }
            if (observation.evidence() == null) {
                throw new IllegalStateException("Child run id has no verified evidence value");
            }
            children.add(new TestSuiteRunAttestation.ChildEvidenceRef(
                    observation.result().caseId(), observation.result().runId(),
                    ProtocolFingerprint.of(objectMapper, observation.evidence())));
        }
        return List.copyOf(children);
    }

    private void verifyRecord(TestSuiteRunRecord record, IntegrationRequestContext identity) {
        TestSuiteRunAttestationService.Verification verification = attestations.verify(
                record.evidence(), record.attestation());
        if (verification == TestSuiteRunAttestationService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.SUITE_ATTESTATION_VERIFICATION_UNAVAILABLE",
                    "Suite evidence cannot be read while its verification authority is unavailable.");
        }
        boolean running = record.evidence().status() == TestSuiteRunEvidence.Status.RUNNING;
        boolean expectedScope = record.attestation().scope() == (running
                ? TestSuiteRunAttestation.Scope.CHECKPOINT
                : TestSuiteRunAttestation.Scope.TERMINAL);
        boolean fingerprintMatches = running
                ? record.evidenceFingerprint().isBlank()
                : record.evidenceFingerprint().equals(record.attestation().aggregateEvidenceFingerprint());
        if (verification != TestSuiteRunAttestationService.Verification.VERIFIED
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || !expectedScope || !fingerprintMatches || !closureMatches(record)) {
            securityEvent(identity, "TEST_SUITE_ATTESTATION_INVALID", "REJECTED",
                    "RG.TEST.SUITE_ATTESTATION_INVALID", Map.of("suiteRunId", record.suiteRunId()));
            throw conflict(identity, "RG.TEST.SUITE_ATTESTATION_INVALID",
                    "Suite evidence or its ordered child closure failed integrity verification.",
                    Map.of("suiteRunId", record.suiteRunId()));
        }
    }

    private static boolean closureMatches(TestSuiteRunRecord record) {
        List<TestSuiteRunAttestation.ChildEvidenceRef> children = record.attestation().childEvidenceRefs();
        int childIndex = 0;
        for (TestSuiteRunEvidence.CaseResult result : record.evidence().caseResults()) {
            if (result.runId().isBlank()) {
                continue;
            }
            if (childIndex >= children.size()) {
                return false;
            }
            TestSuiteRunAttestation.ChildEvidenceRef child = children.get(childIndex++);
            if (!result.caseId().equals(child.caseId()) || !result.runId().equals(child.runId())) {
                return false;
            }
        }
        return childIndex == children.size();
    }

    private static TestSuiteRunRecord withEvidence(
            TestSuiteRunRecord record, TestSuiteRunEvidenceProtocol evidence, String fingerprint,
            TestSuiteRunAttestation attestation) {
        return new TestSuiteRunRecord(record.suiteRunId(), record.clientRequestId(),
                record.requestFingerprint(), record.tenantId(), record.organizationId(), record.projectId(),
                record.environmentId(), record.actorId(), record.classification(), fingerprint, evidence,
                attestation,
                record.createdAt(), record.expiresAt());
    }

    private static TestSuiteExecutionResponse response(TestSuiteRunRecord record) {
        String schemaVersion = record.evidence() instanceof TestSuiteRunEvidenceV2
                ? TestSuiteExecutionResponse.SCHEMA_VERSION_V3
                : TestSuiteExecutionResponse.SCHEMA_VERSION;
        return new TestSuiteExecutionResponse(schemaVersion, record.suiteRunId(),
                record.evidenceFingerprint(), record.evidence(), record.attestation());
    }

    private void validateRequest(String pathSuiteId, TestSuiteExecutionRequest request,
                                 IntegrationRequestContext identity) {
        if (request == null || !TestSuiteExecutionRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                || request.suiteRef() == null || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || request.suiteRef().revision() <= 0
                || !validFingerprint(request.suiteRef().fingerprint())) {
            throw badRequest(identity, "RG.TEST.SUITE_EXECUTION_REQUEST_INVALID",
                    "Path and exact suite id, revision, fingerprint, and schemaVersion are required.",
                    Map.of());
        }
        if (request.clientRequestId().isBlank()
                || request.clientRequestId().length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw badRequest(identity, "RG.TEST.SUITE_RUN_IDEMPOTENCY_KEY_INVALID",
                    "clientRequestId must be a bounded non-empty idempotency key.", Map.of());
        }
        if (request.metadata().containsKey(null) || request.metadata().containsValue(null)) {
            throw badRequest(identity, "RG.TEST.METADATA_INVALID",
                    "Suite execution metadata keys and values must be non-null.", Map.of());
        }
        requireBounded(request.metadata(), MAX_METADATA_BYTES, "metadata", identity);
    }

    private void requireExecutionIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!EXECUTION_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_EXECUTION_PURPOSE_FORBIDDEN",
                    "Suite execution requires a verified TEST_EXECUTION or TEST_REPLAY workload purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            securityEvent(identity, "TEST_PURPOSE_PRODUCTION_TOUCH", "REJECTED",
                    "RG.TEST.ENVIRONMENT_FORBIDDEN", Map.of("allowedEnvironments", ENABLED_ENVIRONMENTS));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Suite execution is restricted to test and staging identities.",
                    identity.correlationId(), Map.of("environmentId", identity.environmentId())));
        }
    }

    private void requireClearance(String classification, IntegrationRequestContext identity) {
        String required = normalized(classification).toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_INVALID",
                    "Suite classification is not recognized.", Map.of("classification", required));
        }
        if (!identity.hasClearanceAtLeast(required)) {
            securityEvent(identity, "TEST_SUITE_CLEARANCE_VIOLATION", "REJECTED",
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN", Map.of("classification", required));
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot execute this suite.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private void requireBounded(Object value, int maximumBytes, String field,
                                IntegrationRequestContext identity) {
        try {
            if (objectMapper.writeValueAsBytes(value).length > maximumBytes) {
                throw badRequest(identity, "RG.TEST.REQUEST_FIELD_TOO_LARGE",
                        field + " exceeds the bounded protocol size.",
                        Map.of("field", field, "maximumBytes", maximumBytes));
            }
        } catch (JsonProcessingException failure) {
            throw badRequest(identity, "RG.TEST.REQUEST_FIELD_INVALID",
                    field + " cannot be serialized as protocol JSON.", Map.of("field", field));
        }
    }

    private void securityEvent(IntegrationRequestContext identity, String type, String outcome,
                               String reason, Map<String, Object> facts) {
        try {
            securityEvents.append(new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                    identity.tenantId(), identity.environmentId(), identity.actorId(), type, outcome,
                    reason, facts));
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Suite execution is unavailable because the security audit sink cannot commit.");
        }
    }

    private String bounded(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= MAX_DIAGNOSTIC_LENGTH ? safe : safe.substring(0, MAX_DIAGNOSTIC_LENGTH);
    }

    private static boolean validFingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static IntegrationProblemException badRequest(IntegrationRequestContext identity,
                                                          String code, String title,
                                                          Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException conflict(IntegrationRequestContext identity,
                                                        String code, String title,
                                                        Map<String, Object> details) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), details));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity,
                                                           String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record TargetPreflight(
            String fingerprint,
            TestSuiteEvidenceAggregator.TargetState state
    ) {
    }

    private record BundleMaterial(
            TestSuiteEvidenceBundle.PayloadPolicy payloadPolicy,
            TestSuiteRunAttestation attestation,
            TestSuiteRunEvidenceProtocol evidence
    ) {
    }
}
