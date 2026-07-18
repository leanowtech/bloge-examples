package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteProtocol;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityEvidence;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteStabilityStatisticalPolicy;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV3;
import com.leanowtech.bloge.gateway.testing.domain.TestSuiteV5;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityAttestationService;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityEvidenceEvaluator;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseCoordinator.LeaseGuard;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityLeaseCoordinator.LeaseLostException;

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
import java.util.regex.Pattern;

/**
 * Idempotent bounded rerun service for signed suite-stability evidence.
 *
 * <p>Each attempt delegates to the ordinary immutable suite runner with a deterministic derived
 * idempotency key and {@code COLLECT_ALL}. After every verified source attempt, a payload-free
 * parent journal is checkpointed under the live owner/epoch fence before another attempt may be
 * scheduled. A successor refetches and verifies that exact prefix. Only a fully signed terminal
 * analysis consumes the journal and lease; concurrent creators converge on one stored winner.
 * An optional payload-free cooperative controller can stop work at source boundaries and
 * linearize an external queue before parent terminal publication.</p>
 */
public final class TestSuiteStabilityExecutionService {
    private static final int MAX_CLIENT_REQUEST_ID_LENGTH = 255;
    private static final int MAX_METADATA_BYTES = 16_384;
    private static final int MAX_METADATA_PROPERTIES = 32;
    private static final int MAX_METADATA_STRING_LENGTH = 512;
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> EXECUTION_PURPOSES = Set.of("TEST_EXECUTION", "TEST_REPLAY");
    private static final List<String> CLASSIFICATIONS = List.of(
            "PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern METADATA_KEY = Pattern.compile(
            "[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final TestSuiteRegistryService suiteRegistry;
    private final TestSuiteExecutionService suiteExecutions;
    private final TestExecutionApiService childExecutions;
    private final TestSuiteStabilityRunRepository repository;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityEvidenceEvaluator evaluator;
    private final TestSuiteStabilityAttestationService attestations;
    private final TestSuiteStabilityLeaseCoordinator leaseCoordinator;
    private final Duration retention;

    /**
     * @param suiteRegistry immutable suite registry
     * @param suiteExecutions ordinary durable suite runner
     * @param childExecutions verified full child-evidence reader
     * @param repository immutable terminal stability store
     * @param objectMapper canonical protocol mapper
     * @param attestations stability-specific signing boundary
     * @param leaseCoordinator cross-replica parent execution ownership
     * @param retention maximum analysis retention, bounded by earliest source retention
     */
    public TestSuiteStabilityExecutionService(
            TestSuiteRegistryService suiteRegistry,
            TestSuiteExecutionService suiteExecutions,
            TestExecutionApiService childExecutions,
            TestSuiteStabilityRunRepository repository,
            ObjectMapper objectMapper,
            TestSuiteStabilityAttestationService attestations,
            TestSuiteStabilityLeaseCoordinator leaseCoordinator,
            Duration retention) {
        this.suiteRegistry = Objects.requireNonNull(suiteRegistry, "suiteRegistry");
        this.suiteExecutions = Objects.requireNonNull(suiteExecutions, "suiteExecutions");
        this.childExecutions = Objects.requireNonNull(childExecutions, "childExecutions");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.evaluator = new TestSuiteStabilityEvidenceEvaluator(objectMapper);
        this.attestations = Objects.requireNonNull(attestations, "attestations");
        this.leaseCoordinator = Objects.requireNonNull(leaseCoordinator, "leaseCoordinator");
        this.retention = retention == null || retention.isNegative() || retention.isZero()
                ? Duration.ofDays(30) : retention;
    }

    /**
     * Executes or idempotently resolves one exact bounded stability rerun.
     *
     * @param suiteId path-bound suite id
     * @param request exact suite, idempotency, attempt, and provenance intent
     * @param identity verified test-runtime identity
     * @return signed terminal stability evidence
     */
    public TestSuiteStabilityExecutionResponse execute(
            String suiteId,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity) {
        return executeControlled(suiteId, request, identity,
                TestSuiteStabilityExecutionControl.uncontrolled());
    }

    /**
     * Executes through an external payload-free cooperative control boundary.
     *
     * <p>The controller is bound before any new source attempt and must linearize its terminal
     * authority before the signed parent record is published. This method is intended for the
     * durable server-owned worker; callers still supply the same verified identity and immutable
     * request used by the synchronous API.</p>
     *
     * @param suiteId path-bound suite id
     * @param request exact suite, idempotency, attempt, and provenance intent
     * @param identity verified test-runtime identity
     * @param control fail-closed cooperative execution controller
     * @return signed terminal stability evidence
     */
    public TestSuiteStabilityExecutionResponse executeControlled(
            String suiteId,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity,
            TestSuiteStabilityExecutionControl control) {
        TestSuiteStabilityExecutionControl executionControl =
                Objects.requireNonNull(control, "control");
        requireExecutionIdentity(identity);
        validateRequest(suiteId, request, identity);
        String requestFingerprint = ProtocolFingerprint.of(objectMapper, request);
        Optional<TestSuiteStabilityRunRecord> existing = findByClientRequestId(
                request.clientRequestId(), identity);
        if (existing.isPresent()) {
            TestSuiteStabilityExecutionResponse response =
                    idempotentResponse(existing.get(), requestFingerprint, identity);
            executionControl.executionStarted(descriptor(existing.get()));
            executionControl.prepareTerminal();
            return response;
        }

        StoredTestSuite stored = suiteRegistry.find(request.suiteRef().suiteId(),
                request.suiteRef().revision(), identity);
        requireClearance(stored.suite().classification(), identity);
        if (!request.suiteRef().fingerprint().equals(stored.fingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_SUITE_FINGERPRINT_CONFLICT",
                    "Stored suite differs from the exact stability execution reference.");
        }
        requireSupportedSuite(stored.suite(), identity);
        requireStatisticalWorkBudget(stored.suite(), request, identity);

        TestSuiteStabilityExecutionDescriptor execution =
                TestSuiteStabilityExecutionIdentity.descriptor(
                        objectMapper, identity, request.clientRequestId(),
                        requestFingerprint, stored.suite().classification());
        String stabilityRunId = execution.stabilityRunId();
        executionControl.executionStarted(execution);
        TestSuiteStabilityLeaseRequest leaseRequest;
        try {
            leaseRequest = leaseCoordinator.request(
                    stabilityRunId, identity.tenantId(), identity.environmentId(),
                    request.clientRequestId(), requestFingerprint, request.suiteRef(),
                    stored.suite().classification(), request.attempts(), retention);
        } catch (LeaseLostException closed) {
            throw unavailable(identity, "RG.TEST.STABILITY_LEASE_COORDINATOR_UNAVAILABLE",
                    "The local suite-stability lease coordinator is shutting down.");
        }
        TestSuiteStabilityLeaseClaim claim;
        try {
            claim = repository.claim(leaseRequest);
        } catch (TestSuiteStabilityRunConflictException conflict) {
            throw mapLeaseConflict(conflict, identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_LEASE_STORE_UNAVAILABLE",
                    "The suite-stability execution lease authority is unavailable.");
        }
        if (claim.state() == TestSuiteStabilityLeaseClaim.State.COMPLETED) {
            TestSuiteStabilityExecutionResponse response =
                    idempotentResponse(claim.terminal(), requestFingerprint, identity);
            executionControl.prepareTerminal();
            return response;
        }
        if (claim.state() == TestSuiteStabilityLeaseClaim.State.IN_PROGRESS) {
            throw throttled(identity, "RG.TEST.STABILITY_EXECUTION_IN_PROGRESS",
                    "The same immutable stability execution is active on another invocation.",
                    claim.retryAfterSeconds());
        }
        if (claim.state() == TestSuiteStabilityLeaseClaim.State.STOPPED) {
            throw stopped(identity, claim.stop());
        }

        LeaseGuard owner;
        try {
            owner = leaseCoordinator.monitor(claim.lease());
        } catch (LeaseLostException closed) {
            releaseUnmonitored(claim.lease());
            throw unavailable(identity, "RG.TEST.STABILITY_LEASE_COORDINATOR_UNAVAILABLE",
                    "The local suite-stability lease coordinator is shutting down.");
        }
        try (LeaseGuard lease = owner) {
            return executeOwned(stored, request, identity, requestFingerprint,
                    stabilityRunId, claim.progress(), lease, executionControl);
        }
    }

    private void releaseUnmonitored(TestSuiteStabilityExecutionLease lease) {
        try {
            repository.release(lease);
        } catch (RuntimeException ignored) {
            // Database expiry and the bounded cleanup sweep remain authoritative.
        }
    }

    private TestSuiteStabilityExecutionResponse executeOwned(
            StoredTestSuite stored,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity,
            String requestFingerprint,
            String stabilityRunId,
            TestSuiteStabilityExecutionProgress progress,
            LeaseGuard lease,
            TestSuiteStabilityExecutionControl control) {
        control.checkpoint(
                TestSuiteStabilityExecutionControl.Phase.BEFORE_PROGRESS_RESTORE, 0);
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> observations =
                restoreProgress(progress, request, stored, identity);
        TestSuiteStabilityExecutionProgress currentProgress = progress;
        for (int attempt = progress.completedAttempts() + 1;
             attempt <= request.attempts(); attempt++) {
            control.checkpoint(TestSuiteStabilityExecutionControl.Phase.BEFORE_ATTEMPT, attempt);
            requireLiveLease(lease, identity);
            TestSuiteExecutionRequest attemptRequest = new TestSuiteExecutionRequest("",
                    request.suiteRef(), attemptClientRequestId(
                    identity, request.clientRequestId(), attempt),
                    TestSuiteExecutionRequest.Strategy.COLLECT_ALL,
                    Map.of("stabilityRunId", stabilityRunId,
                            "stabilityAttempt", attempt,
                            "stabilityRequestFingerprint", requestFingerprint));
            TestSuiteExecutionResponse executed = suiteExecutions.execute(
                    stored.suiteId(), attemptRequest, identity);
            if (executed == null) {
                throw unavailable(identity, "RG.TEST.STABILITY_SOURCE_EXECUTION_UNAVAILABLE",
                        "A stability source suite execution returned no durable result.");
            }
            TestSuiteExecutionResponse source = suiteExecutions.find(
                    executed.suiteRunId(), identity);
            TestSuiteStabilityEvidenceEvaluator.AttemptObservation observation =
                    observeSource(attempt, source, identity);
            control.checkpoint(
                    TestSuiteStabilityExecutionControl.Phase.AFTER_SOURCE_VERIFICATION, attempt);
            currentProgress = checkpointProgress(lease, currentProgress, source, attempt, identity);
            observations.add(observation);
        }

        control.checkpoint(TestSuiteStabilityExecutionControl.Phase.BEFORE_EVIDENCE_SEAL, 0);
        TestSuiteStabilityEvidence evidence = request.statisticalPolicy() == null
                ? evaluator.evaluate(stored.suite(), request.suiteRef(), stabilityRunId,
                request.clientRequestId(), request.attempts(), observations, request.metadata())
                : evaluator.evaluateStatistical(stored.suite(), request.suiteRef(), stabilityRunId,
                request.clientRequestId(), request.attempts(), observations, request.metadata(),
                request.statisticalPolicy());
        TestSuiteStabilityAttestationService.SealResult seal =
                attestations.seal(evidence, requestFingerprint);
        if (!seal.verified()) {
            throw unavailable(identity, "RG.TEST.STABILITY_ATTESTATION_UNAVAILABLE",
                    "Stability evidence cannot be retained because its terminal signature is unavailable.");
        }
        String evidenceFingerprint = seal.attestation().evidenceFingerprint();
        Instant createdAt = Instant.now();
        Instant expiresAt = evidence.startedAt().plus(retention);
        if (!expiresAt.isAfter(createdAt)) {
            throw conflict(identity, "RG.TEST.STABILITY_SOURCE_RETENTION_EXHAUSTED",
                    "A source suite run is too close to expiry for a durable stability analysis.");
        }
        TestSuiteStabilityRunRecord record = new TestSuiteStabilityRunRecord(
                stabilityRunId, request.clientRequestId(), requestFingerprint,
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId(), identity.actorId(), stored.suite().classification(),
                evidenceFingerprint, evidence, seal.attestation(), createdAt, expiresAt);
        control.prepareTerminal();
        try {
            TestSuiteStabilityExecutionLease terminalLease = requireLiveLease(lease, identity);
            TestSuiteStabilityRunRecord completed = repository.complete(record, terminalLease);
            lease.consumed();
            return response(completed);
        } catch (TestSuiteStabilityRunConflictException conflict) {
            throw mapLeaseConflict(conflict, identity);
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
    }

    private List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> restoreProgress(
            TestSuiteStabilityExecutionProgress progress,
            TestSuiteStabilityExecutionRequest request,
            StoredTestSuite stored,
            IntegrationRequestContext identity) {
        if (progress == null
                || !progress.tenantId().equals(identity.tenantId())
                || !progress.environmentId().equals(identity.environmentId())
                || !progress.clientRequestId().equals(request.clientRequestId())
                || !progress.suiteRef().equals(request.suiteRef())
                || !progress.classification().equals(stored.suite().classification())
                || progress.plannedAttempts() != request.attempts()) {
            throw unavailable(identity, "RG.TEST.STABILITY_PROGRESS_CONFLICT",
                    "Durable suite-stability progress contradicts the immutable execution intent.");
        }
        List<TestSuiteStabilityEvidenceEvaluator.AttemptObservation> observations =
                new ArrayList<>(progress.completedAttempts());
        try {
            for (TestSuiteStabilityExecutionProgress.AttemptReference reference
                    : progress.attempts()) {
                TestSuiteExecutionResponse source = suiteExecutions.find(
                        reference.suiteRunId(), identity);
                if (source == null
                        || !reference.suiteRunId().equals(source.suiteRunId())
                        || !reference.aggregateEvidenceFingerprint().equals(
                        source.evidenceFingerprint())) {
                    throw new IllegalStateException(
                            "Source suite evidence contradicts durable progress");
                }
                observations.add(observeSource(reference.attempt(), source, identity));
            }
            return observations;
        } catch (RuntimeException unavailable) {
            throw TestSuiteStabilityExecutionService.unavailable(identity,
                    "RG.TEST.STABILITY_PROGRESS_SOURCE_UNAVAILABLE",
                    "A durable stability prefix cannot be reconstructed from its governed source evidence.");
        }
    }

    private TestSuiteStabilityEvidenceEvaluator.AttemptObservation observeSource(
            int attempt,
            TestSuiteExecutionResponse source,
            IntegrationRequestContext identity) {
        if (source == null || source.evidence() == null || source.attestation() == null
                || source.evidence().status() == com.leanowtech.bloge.gateway.testing.domain
                .TestSuiteRunEvidence.Status.RUNNING
                || !source.suiteRunId().equals(source.evidence().suiteRunId())
                || !source.suiteRunId().equals(source.attestation().suiteRunId())
                || !source.evidenceFingerprint().equals(
                source.attestation().aggregateEvidenceFingerprint())
                || !source.attestation().terminallyVerifiable()) {
            throw unavailable(identity, "RG.TEST.STABILITY_SOURCE_EVIDENCE_INVALID",
                    "A stability source suite run is not terminally verifiable.");
        }
        Map<String, TestSuiteStabilityEvidenceEvaluator.ChildObservation> children =
                new LinkedHashMap<>();
        source.attestation().childEvidenceRefs().forEach(child -> {
            TestExecutionApiResponse full = childExecutions.find(child.runId(),
                    TestExecutionApiRequest.Verbosity.FULL, identity);
            children.put(child.runId(),
                    new TestSuiteStabilityEvidenceEvaluator.ChildObservation(full, true));
        });
        return new TestSuiteStabilityEvidenceEvaluator.AttemptObservation(
                attempt, source, true, children, Instant.now(), "");
    }

    private TestSuiteStabilityExecutionProgress checkpointProgress(
            LeaseGuard lease,
            TestSuiteStabilityExecutionProgress current,
            TestSuiteExecutionResponse source,
            int attempt,
            IntegrationRequestContext identity) {
        TestSuiteStabilityExecutionProgress.AttemptReference reference =
                new TestSuiteStabilityExecutionProgress.AttemptReference(
                        attempt, source.suiteRunId(), source.evidenceFingerprint());
        try {
            TestSuiteStabilityExecutionProgress successor = lease.checkpoint(reference, retention);
            if (successor.completedAttempts() != current.completedAttempts() + 1
                    || !successor.attempts().getLast().equals(reference)
                    || !successor.stabilityRunId().equals(current.stabilityRunId())
                    || !successor.requestFingerprint().equals(current.requestFingerprint())) {
                throw new IllegalStateException(
                        "Durable stability checkpoint returned a contradictory successor");
            }
            return successor;
        } catch (RuntimeException unavailable) {
            throw TestSuiteStabilityExecutionService.unavailable(identity,
                    "RG.TEST.STABILITY_PROGRESS_CHECKPOINT_FAILED",
                    "The next stability attempt was stopped because durable parent progress could not be committed.");
        }
    }

    private static TestSuiteStabilityExecutionLease requireLiveLease(
            LeaseGuard lease,
            IntegrationRequestContext identity) {
        try {
            return lease.checkpoint();
        } catch (LeaseLostException lost) {
            throw unavailable(identity, "RG.TEST.STABILITY_EXECUTION_LEASE_LOST",
                    "Suite-stability ownership was lost before terminal evidence publication.");
        }
    }

    private static IntegrationProblemException mapLeaseConflict(
            TestSuiteStabilityRunConflictException failure,
            IntegrationRequestContext identity) {
        return switch (failure.reason()) {
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.STABILITY_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies a different stability execution intent.");
            case IDEMPOTENCY_RETIRED -> conflict(identity,
                    "RG.TEST.STABILITY_IDEMPOTENCY_RETIRED",
                    "The stability idempotency identity is retained after evidence expiry.");
            case LEASE_LOST -> unavailable(identity,
                    "RG.TEST.STABILITY_EXECUTION_LEASE_LOST",
                    "Suite-stability ownership was lost before terminal evidence publication.");
            case PROGRESS_CONFLICT -> unavailable(identity,
                    "RG.TEST.STABILITY_PROGRESS_CONFLICT",
                    "Durable suite-stability progress is missing or contradicts the execution.");
            case TERMINAL_CONFLICT -> conflict(identity,
                    "RG.TEST.STABILITY_TERMINAL_CONFLICT",
                    "The deterministic stability identity already has a different terminal record.");
        };
    }

    /**
     * Resolves one retained signed stability analysis.
     *
     * @param stabilityRunId deterministic analysis id
     * @param identity verified test-runtime identity
     * @return retained signed terminal response
     */
    public TestSuiteStabilityExecutionResponse find(
            String stabilityRunId,
            IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        TestSuiteStabilityRunRecord record;
        try {
            record = repository.find(identity.tenantId(), identity.environmentId(),
                    normalized(stabilityRunId)).orElseThrow(() ->
                    new IntegrationProblemException(IntegrationProblem.notFound(
                            "RG.TEST.STABILITY_RUN_NOT_FOUND",
                            "Stability analysis was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
        requireClearance(record.classification(), identity);
        verifyRecord(record, identity);
        return response(record);
    }

    /**
     * Resolves payload-free active, takeover-ready, or terminal parent progress.
     *
     * @param stabilityRunId deterministic parent identity
     * @param identity verified test-runtime identity
     * @return authorized progress projection
     */
    public TestSuiteStabilityProgressResponse findProgress(
            String stabilityRunId,
            IntegrationRequestContext identity) {
        requireExecutionIdentity(identity);
        String exactRunId = normalized(stabilityRunId);
        try {
            Optional<TestSuiteStabilityRunRecord> terminal = repository.find(
                    identity.tenantId(), identity.environmentId(), exactRunId);
            if (terminal.isPresent()) {
                TestSuiteStabilityRunRecord record = terminal.get();
                requireClearance(record.classification(), identity);
                verifyRecord(record, identity);
                return completedProgress(record);
            }
            Optional<TestSuiteStabilityProgressSnapshot> active = repository.findProgress(
                    identity.tenantId(), identity.environmentId(), exactRunId);
            if (active.isPresent()) {
                TestSuiteStabilityExecutionProgress progress = active.get().progress();
                requireClearance(progress.classification(), identity);
                return new TestSuiteStabilityProgressResponse("", progress.stabilityRunId(),
                        active.get().liveOwner()
                                ? TestSuiteStabilityProgressResponse.Status.RUNNING
                                : TestSuiteStabilityProgressResponse.Status.RECOVERABLE,
                        progress.suiteRef(), progress.plannedAttempts(),
                        progress.completedAttempts(), progress.createdAt(), progress.updatedAt());
            }
            terminal = repository.find(
                    identity.tenantId(), identity.environmentId(), exactRunId);
            if (terminal.isPresent()) {
                TestSuiteStabilityRunRecord record = terminal.get();
                requireClearance(record.classification(), identity);
                verifyRecord(record, identity);
                return completedProgress(record);
            }
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.STABILITY_PROGRESS_NOT_FOUND",
                    "Stability progress was not found in the authorized retained scope.",
                    identity.correlationId(), Map.of()));
        } catch (IntegrationProblemException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw unavailable(identity, "RG.TEST.STABILITY_PROGRESS_STORE_UNAVAILABLE",
                    "The durable suite-stability progress store is unavailable.");
        }
    }

    private static TestSuiteStabilityProgressResponse completedProgress(
            TestSuiteStabilityRunRecord record) {
        TestSuiteStabilityEvidence evidence = record.evidence();
        return new TestSuiteStabilityProgressResponse("", record.stabilityRunId(),
                TestSuiteStabilityProgressResponse.Status.COMPLETED, evidence.suiteRef(),
                evidence.requestedAttempts(), evidence.attempts().size(),
                evidence.startedAt(), evidence.completedAt());
    }

    private TestSuiteStabilityExecutionResponse idempotentResponse(
            TestSuiteStabilityRunRecord existing,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        requireClearance(existing.classification(), identity);
        if (!requestFingerprint.equals(existing.requestFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies a different stability execution intent.");
        }
        verifyRecord(existing, identity);
        return response(existing);
    }

    private static TestSuiteStabilityExecutionDescriptor descriptor(
            TestSuiteStabilityRunRecord record) {
        return new TestSuiteStabilityExecutionDescriptor(
                record.stabilityRunId(), record.tenantId(), record.environmentId(),
                record.clientRequestId(), record.requestFingerprint(), record.classification());
    }

    private void verifyRecord(
            TestSuiteStabilityRunRecord record,
            IntegrationRequestContext identity) {
        TestSuiteStabilityAttestationService.Verification verification =
                attestations.verify(record.evidence(), record.attestation());
        if (verification == TestSuiteStabilityAttestationService.Verification.UNAVAILABLE) {
            throw unavailable(identity, "RG.TEST.STABILITY_ATTESTATION_VERIFICATION_UNAVAILABLE",
                    "Stability evidence cannot be read while its verification key is unavailable.");
        }
        String fingerprint;
        try {
            fingerprint = ProtocolFingerprint.of(objectMapper, record.evidence());
        } catch (RuntimeException invalid) {
            fingerprint = "";
        }
        if (verification != TestSuiteStabilityAttestationService.Verification.VERIFIED
                || !record.stabilityRunId().equals(record.evidence().stabilityRunId())
                || !record.requestFingerprint().equals(record.attestation().requestFingerprint())
                || !record.evidenceFingerprint().equals(fingerprint)
                || !record.evidenceFingerprint().equals(record.attestation().evidenceFingerprint())) {
            throw conflict(identity, "RG.TEST.STABILITY_ATTESTATION_INVALID",
                    "Stability evidence or its ordered source closure failed integrity verification.");
        }
    }

    private Optional<TestSuiteStabilityRunRecord> findByClientRequestId(
            String clientRequestId,
            IntegrationRequestContext identity) {
        try {
            return repository.findByClientRequestId(identity.tenantId(), identity.environmentId(),
                    clientRequestId);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.STABILITY_STORE_UNAVAILABLE",
                    "The independent stability evidence store is unavailable.");
        }
    }

    private void validateRequest(
            String pathSuiteId,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || request.suiteRef() == null
                || normalized(pathSuiteId).isBlank()
                || !normalized(pathSuiteId).equals(request.suiteRef().suiteId())
                || request.suiteRef().revision() <= 0
                || !fingerprint(request.suiteRef().fingerprint())) {
            throw badRequest(identity, "RG.TEST.STABILITY_REQUEST_INVALID",
                    "An exact suite reference and supported bounded request are required.");
        }
        boolean deterministic = TestSuiteStabilityExecutionRequest.SCHEMA_VERSION_V1.equals(
                request.schemaVersion());
        boolean statistical = TestSuiteStabilityExecutionRequest.SCHEMA_VERSION.equals(
                request.schemaVersion());
        if (!deterministic && !statistical
                || deterministic && (request.statisticalPolicy() != null
                || request.attempts() < TestSuiteStabilityEvidence.MIN_ATTEMPTS
                || request.attempts() > TestSuiteStabilityEvidence.MAX_ATTEMPTS)
                || statistical && (request.statisticalPolicy() == null
                || request.attempts() < TestSuiteStabilityStatisticalPolicy.MIN_ATTEMPTS
                || request.attempts() > TestSuiteStabilityStatisticalPolicy.MAX_ATTEMPTS)) {
            throw badRequest(identity, "RG.TEST.STABILITY_REQUEST_INVALID",
                    "Request v1 requires 3..20 deterministic attempts; v2 requires a 3..1000 statistical horizon.");
        }
        if (statistical) {
            try {
                int required = request.statisticalPolicy().minimumRequiredAttempts();
                if (request.attempts() < required
                        || !request.statisticalPolicy().horizonSufficient(request.attempts())) {
                    throw badRequest(identity, "RG.TEST.STABILITY_STATISTICAL_HORIZON_INVALID",
                            "The precommitted horizon cannot satisfy the requested confidence policy.");
                }
            } catch (IntegrationProblemException failure) {
                throw failure;
            } catch (IllegalArgumentException invalid) {
                throw badRequest(identity, "RG.TEST.STABILITY_STATISTICAL_HORIZON_INVALID",
                        "The statistical confidence target exceeds the bounded protocol horizon.");
            }
        }
        if (request.clientRequestId().isBlank()
                || request.clientRequestId().length() > MAX_CLIENT_REQUEST_ID_LENGTH) {
            throw badRequest(identity, "RG.TEST.STABILITY_IDEMPOTENCY_KEY_INVALID",
                    "clientRequestId must be a bounded non-empty idempotency key.");
        }
        if (request.metadata().size() > MAX_METADATA_PROPERTIES
                || request.metadata().entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !METADATA_KEY.matcher(entry.getKey()).matches()
                        || !metadataValue(entry.getValue()))) {
            throw badRequest(identity, "RG.TEST.STABILITY_METADATA_INVALID",
                    "Stability metadata must contain only bounded scalar provenance facts.");
        }
        try {
            if (objectMapper.writeValueAsBytes(request.metadata()).length > MAX_METADATA_BYTES) {
                throw badRequest(identity, "RG.TEST.STABILITY_METADATA_TOO_LARGE",
                        "Stability metadata exceeds the bounded protocol size.");
            }
        } catch (JsonProcessingException invalid) {
            throw badRequest(identity, "RG.TEST.STABILITY_METADATA_INVALID",
                    "Stability metadata cannot be serialized as protocol JSON.");
        }
    }

    private static boolean metadataValue(Object value) {
        if (value instanceof Double number) {
            return Double.isFinite(number);
        }
        if (value instanceof Float number) {
            return Float.isFinite(number);
        }
        return value instanceof Boolean || value instanceof Number
                || value instanceof String text && text.length() <= MAX_METADATA_STRING_LENGTH;
    }

    private static void requireSupportedSuite(
            TestSuiteProtocol suite,
            IntegrationRequestContext identity) {
        if (suite instanceof TestSuiteV3 || suite instanceof TestSuiteV5) {
            throw badRequest(identity, "RG.TEST.STABILITY_SUITE_GENERATION_UNSUPPORTED",
                    "Stability analysis requires executable child evidence for every suite case.");
        }
    }

    private static void requireStatisticalWorkBudget(
            TestSuiteProtocol suite,
            TestSuiteStabilityExecutionRequest request,
            IntegrationRequestContext identity) {
        if (request.statisticalPolicy() == null) {
            return;
        }
        long observations = (long) request.attempts() * suite.cases().size();
        if (observations > TestSuiteStabilityStatisticalPolicy.MAX_CASE_OBSERVATIONS) {
            throw badRequest(identity, "RG.TEST.STABILITY_STATISTICAL_WORK_BUDGET_EXCEEDED",
                    "The statistical horizon exceeds the bounded attempt-by-case work budget.");
        }
    }

    private String attemptClientRequestId(
            IntegrationRequestContext identity,
            String parentClientRequestId,
            int attempt) {
        String namespace = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", "bloge.testSuiteStabilityAttemptNamespace.v1",
                "tenantId", identity.tenantId(),
                "environmentId", identity.environmentId(),
                "clientRequestId", parentClientRequestId));
        return "stability-attempt-" + namespace.substring("sha256:".length())
                + "-%02d".formatted(attempt);
    }

    private void requireExecutionIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!EXECUTION_PURPOSES.contains(identity.purpose())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.STABILITY_PURPOSE_FORBIDDEN",
                    "Stability execution requires TEST_EXECUTION or TEST_REPLAY purpose.",
                    identity.correlationId(), Map.of()));
        }
        if (!ENABLED_ENVIRONMENTS.contains(
                identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.ENVIRONMENT_FORBIDDEN",
                    "Stability execution is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireClearance(
            String classification,
            IntegrationRequestContext identity) {
        String required = normalized(classification).toUpperCase(Locale.ROOT);
        if (!CLASSIFICATIONS.contains(required)) {
            throw badRequest(identity, "RG.TEST.SUITE_CLASSIFICATION_INVALID",
                    "Suite classification is not recognized.");
        }
        if (!identity.hasClearanceAtLeast(required)) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.SUITE_CLEARANCE_FORBIDDEN",
                    "Verified workload clearance cannot execute this suite.",
                    identity.correlationId(), Map.of("classification", required)));
        }
    }

    private static TestSuiteStabilityExecutionResponse response(
            TestSuiteStabilityRunRecord record) {
        String version;
        if (TestSuiteStabilityEvidence.SCHEMA_VERSION_V1.equals(
                record.evidence().schemaVersion())) {
            version = TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V1;
        } else if (TestSuiteStabilityEvidence.SCHEMA_VERSION_V2.equals(
                record.evidence().schemaVersion())) {
            version = TestSuiteStabilityExecutionResponse.SCHEMA_VERSION_V2;
        } else {
            version = TestSuiteStabilityExecutionResponse.SCHEMA_VERSION;
        }
        return new TestSuiteStabilityExecutionResponse(version, record.stabilityRunId(),
                record.evidenceFingerprint(), record.evidence(), record.attestation());
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String detail) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, detail, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException stopped(
            IntegrationRequestContext identity,
            TestSuiteStabilityExecutionStop stop) {
        return switch (stop.reason()) {
            case CANCELLED -> conflict(identity, "RG.TEST.STABILITY_EXECUTION_CANCELLED",
                    "The immutable suite-stability execution was cancelled.");
            case DEADLINE_EXCEEDED -> conflict(
                    identity, "RG.TEST.STABILITY_EXECUTION_DEADLINE_EXCEEDED",
                    "The immutable suite-stability execution exceeded its deadline.");
            case WORKER_FAILED -> conflict(identity, "RG.TEST.STABILITY_EXECUTION_WORKER_FAILED",
                    "The immutable suite-stability execution was terminally stopped.");
        };
    }

    private static IntegrationProblemException throttled(
            IntegrationRequestContext identity,
            String code,
            String detail,
            long retryAfterSeconds) {
        return new IntegrationProblemException(IntegrationProblem.tooManyRequests(
                code, detail, identity.correlationId(),
                Map.of("retryAfterSeconds", Math.max(1, Math.min(3_600, retryAfterSeconds)))));
    }
}
