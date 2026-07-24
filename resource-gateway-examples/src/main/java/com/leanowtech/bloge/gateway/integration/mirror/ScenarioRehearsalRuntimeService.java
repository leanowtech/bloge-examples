package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.StoredTestSuite;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteRegistryService;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Synchronous generation-one runtime for one exact compiled Scenario rehearsal plan.
 *
 * <p>The service is an orchestrator over existing protected boundaries. It never executes BLOGE
 * directly: TestSuite supplies the immutable context, an optional checkpoint supplies an exact
 * Session state fence, {@link MirrorRunIntegrationService} owns child idempotency and live-state
 * admission, and handling assertions consume only independently verified evidence. Passing the
 * original fence to that coordinator lets a completed stateful retry resolve before the current
 * Session head is inspected.</p>
 *
 * <p>The aggregate has its own database-clock lease and epoch, append-only case-progress prefix,
 * and atomic signed-evidence commit. Stable child request ids and durable aggregate checkpoints
 * let a takeover resume at the first incomplete case while fencing stale workers. Batch
 * scheduling and checkpoint cloning remain later control-plane work.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalRuntimeService {
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final Duration COMMIT_RESERVE =
            Duration.ofSeconds(30);
    private static final Duration REQUEST_RETENTION =
            Duration.ofDays(30);

    private final ScenarioRehearsalIntegrationService rehearsals;
    private final ScenarioArtifactRegistryService scenarioArtifacts;
    private final TestSuiteRegistryService testSuites;
    private final MirrorRunIntegrationService mirrorRuns;
    private final MirrorEvidenceIntegrityService evidenceIntegrity;
    private final ScenarioHandlingAssertionEvaluator assertionEvaluator;
    private final ScenarioRehearsalEvidenceIntegrityService
            rehearsalEvidenceIntegrity;
    private final ScenarioRehearsalEvidenceRepository rehearsalEvidence;
    private final ScenarioRehearsalRunRepository rehearsalRequests;
    private final ScenarioRehearsalCommitService rehearsalCommits;
    private final ScenarioRehearsalRetentionRepository retention;
    private final MirrorOperationObservability observations;
    private final ObjectMapper mapper;
    private final MirrorSessionIntegrationService sessions;
    private final Clock clock;

    /** Creates the protected runtime with optional stateful Session support. */
    @Autowired
    public ScenarioRehearsalRuntimeService(
            ScenarioRehearsalIntegrationService rehearsals,
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            MirrorRunIntegrationService mirrorRuns,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            ScenarioHandlingAssertionEvaluator assertionEvaluator,
            ScenarioRehearsalEvidenceIntegrityService
                    rehearsalEvidenceIntegrity,
            ScenarioRehearsalEvidenceRepository rehearsalEvidence,
            ScenarioRehearsalRunRepository rehearsalRequests,
            ScenarioRehearsalCommitService rehearsalCommits,
            ScenarioRehearsalRetentionRepository retention,
            MirrorOperationObservability observations,
            ObjectMapper mapper,
            ObjectProvider<MirrorSessionIntegrationService> sessionProvider) {
        this(
                rehearsals, scenarioArtifacts, testSuites, mirrorRuns,
                evidenceIntegrity, assertionEvaluator,
                rehearsalEvidenceIntegrity, rehearsalEvidence,
                rehearsalRequests, rehearsalCommits, retention,
                observations, mapper,
                Objects.requireNonNull(
                        sessionProvider, "sessionProvider").getIfAvailable(),
                Clock.systemUTC());
    }

    /** Full constructor for deterministic service tests. */
    public ScenarioRehearsalRuntimeService(
            ScenarioRehearsalIntegrationService rehearsals,
            ScenarioArtifactRegistryService scenarioArtifacts,
            TestSuiteRegistryService testSuites,
            MirrorRunIntegrationService mirrorRuns,
            MirrorEvidenceIntegrityService evidenceIntegrity,
            ScenarioHandlingAssertionEvaluator assertionEvaluator,
            ScenarioRehearsalEvidenceIntegrityService
                    rehearsalEvidenceIntegrity,
            ScenarioRehearsalEvidenceRepository rehearsalEvidence,
            ScenarioRehearsalRunRepository rehearsalRequests,
            ScenarioRehearsalCommitService rehearsalCommits,
            ScenarioRehearsalRetentionRepository retention,
            MirrorOperationObservability observations,
            ObjectMapper mapper,
            MirrorSessionIntegrationService sessions,
            Clock clock) {
        this.rehearsals = Objects.requireNonNull(rehearsals, "rehearsals");
        this.scenarioArtifacts = Objects.requireNonNull(
                scenarioArtifacts, "scenarioArtifacts");
        this.testSuites = Objects.requireNonNull(testSuites, "testSuites");
        this.mirrorRuns = Objects.requireNonNull(mirrorRuns, "mirrorRuns");
        this.evidenceIntegrity = Objects.requireNonNull(
                evidenceIntegrity, "evidenceIntegrity");
        this.assertionEvaluator = Objects.requireNonNull(
                assertionEvaluator, "assertionEvaluator");
        this.rehearsalEvidenceIntegrity = Objects.requireNonNull(
                rehearsalEvidenceIntegrity,
                "rehearsalEvidenceIntegrity");
        this.rehearsalEvidence = Objects.requireNonNull(
                rehearsalEvidence, "rehearsalEvidence");
        this.rehearsalRequests = Objects.requireNonNull(
                rehearsalRequests, "rehearsalRequests");
        this.rehearsalCommits = Objects.requireNonNull(
                rehearsalCommits, "rehearsalCommits");
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.observations = Objects.requireNonNull(
                observations, "observations");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.sessions = sessions;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Executes every compiled case sequentially and returns a sealed aggregate interpretation.
     *
     * @param request exact payload-free rehearsal command
     * @param identity authenticated full enterprise mirror identity
     * @return signed portable aggregate over verified child evidence
     */
    public ScenarioRehearsalEvidenceBundle execute(
            ScenarioRehearsalExecutionRequest request,
            IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_CREATE,
                        identity,
                        request == null ? "" : request.requestId(),
                        request == null
                                || request.compiledPlanRef() == null
                                ? ""
                                : request.compiledPlanRef().id(),
                        "");
        try {
            return executeObserved(request, identity, observation);
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private ScenarioRehearsalEvidenceBundle executeObserved(
            ScenarioRehearsalExecutionRequest request,
            IntegrationRequestContext identity,
            MirrorOperationObservability.Observation observation) {
        Objects.requireNonNull(request, "request");
        requirePurpose(identity);
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        MirrorArtifactRef planRef = request.compiledPlanRef();
        CompiledScenarioRehearsalPlan plan = rehearsals.find(
                planRef.id(), planRef.revision(),
                planRef.fingerprint(), identity);
        String runId = ScenarioRehearsalRunIdentity.derive(
                mapper, scope, request.requestId());
        ScenarioRehearsalEvidenceBundle completed =
                completedRetry(runId, request, plan, scope, identity);
        if (completed != null) {
            observation.succeeded(runId);
            return completed;
        }
        ScenarioRehearsalRunRepository.Registration registration =
                registration(request, plan, scope, runId, identity);
        ScenarioRehearsalRunRepository.Claim claim =
                claim(registration, plan, identity);
        if (claim.outcome()
                == ScenarioRehearsalRunRepository.Outcome.IN_PROGRESS) {
            throw new IntegrationProblemException(
                    IntegrationProblem.retryableConflict(
                            "RG.MIRROR.REHEARSAL.REQUEST_IN_PROGRESS",
                            "An identical Scenario rehearsal is already in progress.",
                            identity.correlationId(),
                            Map.of(
                                    "retryAfterSeconds",
                                    claim.retryAfterSeconds())));
        }
        if (claim.outcome()
                == ScenarioRehearsalRunRepository.Outcome.COMPLETED) {
            ScenarioRehearsalEvidenceBundle terminal =
                    completedRetry(
                            runId, request, plan, scope, identity);
            if (terminal == null
                    || !claim.state().evidenceBundleFingerprint().equals(
                    terminal.bundleFingerprint())) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.REHEARSAL.EVIDENCE_INCONSISTENT",
                        "Completed Scenario coordination state differs from signed evidence.");
            }
            observation.succeeded(runId);
            return terminal;
        }

        ScenarioRehearsalRunRepository.Lease lease = claim.lease();
        try {
            Instant startedAt = claim.state().startedAt();
            List<ResolvedCase> resolved = resolveCases(
                    plan, scope, identity, startedAt);
            List<ScenarioCaseRehearsalResult> results =
                    new ArrayList<>(
                            rehearsalRequests.progress(lease));
            requireProgress(
                    results, request, plan, identity);
            Instant deadline = deadline(
                    startedAt, plan, identity);
            for (int index = results.size();
                 index < resolved.size();
                 index++) {
                ResolvedCase current = resolved.get(index);
                Instant now = clock.instant();
                ScenarioCaseRehearsalResult caseResult =
                        now.isAfter(
                                deadline.minus(
                                        plan.policy().caseTimeout()))
                                ? unscheduled(
                                index,
                                request.requestId(),
                                current,
                                now,
                                "RG.MIRROR.REHEARSAL.TOTAL_TIMEOUT_EXCEEDED")
                                : executeCase(
                                index,
                                request.requestId(),
                                current,
                                scope,
                                identity);
                rehearsalRequests.checkpoint(
                        lease, caseResult);
                results.add(caseResult);
            }
            ScenarioRehearsalEvidenceBundle sealed =
                    sealAggregate(
                            request, plan, scope, runId,
                            startedAt, results, identity);
            return rehearsalCommits.commit(
                    lease, sealed, observation);
        } catch (ScenarioRehearsalLeaseLostException stale) {
            throw new IntegrationProblemException(
                    IntegrationProblem.retryableConflict(
                            "RG.MIRROR.REHEARSAL.LEASE_LOST",
                            "Scenario rehearsal authority expired before durable progress or evidence commit.",
                            identity.correlationId(),
                            Map.of("retryAfterSeconds", 1)));
        } catch (IntegrationProblemException expected) {
            release(lease, expected.problem().code());
            throw expected;
        } catch (ScenarioRehearsalEvidenceStoreException classified) {
            release(
                    lease,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_STORE_REJECTED");
            throw evidenceStoreFailure(classified, identity);
        } catch (RuntimeException unavailable) {
            release(
                    lease,
                    "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE");
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.RUNTIME_UNAVAILABLE",
                    "Scenario rehearsal coordination, execution, or evidence commit is unavailable.");
        }
    }

    /**
     * Reads and re-verifies one signed Scenario aggregate in the authorized scope.
     *
     * @param runId stable aggregate run identity
     * @param identity authenticated full enterprise mirror identity
     * @return independently verified portable evidence
     */
    public ScenarioRehearsalEvidenceBundle evidence(
            String runId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_EVIDENCE_READ,
                        identity, "", "", runId);
        try {
            ScenarioRehearsalEvidenceBundle bundle =
                    evidenceObserved(runId, identity);
            observation.succeeded(
                    bundle.attestation().runId());
            return bundle;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private ScenarioRehearsalEvidenceBundle evidenceObserved(
            String runId, IntegrationRequestContext identity) {
        requirePurpose(identity);
        CapabilitySnapshot.Scope scope =
                MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        String id = runId == null ? "" : runId.trim();
        if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(id)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL.RUN_ID_INVALID",
                            "Scenario rehearsal run id is invalid.",
                            identity.correlationId(),
                            Map.of()));
        }
        try {
            Optional<ScenarioRehearsalEvidenceBundle> stored =
                    rehearsalEvidence.find(scope, id);
            if (stored.isEmpty()) {
                requireNotPurged(scope, id, identity);
            }
            ScenarioRehearsalEvidenceBundle bundle =
                    stored.orElseThrow(() ->
                            new IntegrationProblemException(
                                    IntegrationProblem.notFound(
                                            "RG.MIRROR.REHEARSAL.RUN_NOT_FOUND",
                                            "Scenario rehearsal run was not found in the authorized scope.",
                                            identity.correlationId(),
                                            Map.of())));
            return rehearsalEvidenceIntegrity
                    .requireVerified(bundle)
                    .bundle();
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (ScenarioRehearsalEvidenceStoreException classified) {
            throw evidenceStoreFailure(classified, identity);
        } catch (IllegalStateException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_VERIFIER_UNAVAILABLE",
                    "Scenario evidence verification authority is unavailable.");
        } catch (IllegalArgumentException invalid) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_INCONSISTENT",
                    "Stored Scenario evidence failed independent verification.");
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_STORE_UNAVAILABLE",
                    "Scenario rehearsal evidence could not be read safely.");
        }
    }

    private ScenarioRehearsalEvidenceBundle completedRetry(
            String runId,
            ScenarioRehearsalExecutionRequest request,
            CompiledScenarioRehearsalPlan plan,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Optional<ScenarioRehearsalEvidenceBundle> existing;
        try {
            existing = rehearsalEvidence.find(scope, runId);
        } catch (ScenarioRehearsalEvidenceStoreException classified) {
            throw evidenceStoreFailure(classified, identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_STORE_UNAVAILABLE",
                    "Scenario rehearsal evidence could not be read safely.");
        }
        if (existing.isEmpty()) {
            requireNotPurged(scope, runId, identity);
            return null;
        }
        ScenarioRehearsalEvidenceBundle bundle;
        try {
            bundle = rehearsalEvidenceIntegrity
                    .requireVerified(existing.orElseThrow())
                    .bundle();
        } catch (IllegalStateException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_VERIFIER_UNAVAILABLE",
                    "Scenario evidence verification authority is unavailable.");
        } catch (IllegalArgumentException invalid) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_INCONSISTENT",
                    "Stored Scenario evidence failed independent verification.");
        }
        ScenarioRehearsalResult result = bundle.result();
        if (!request.requestId().equals(result.requestId())
                || !request.compiledPlanRef().equals(
                result.compiledPlanRef())
                || !scope.equals(result.scope())
                || !plan.targetCapabilityRef().equals(
                result.targetCapabilityRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.IDEMPOTENCY_CONFLICT",
                    "The request id already identifies different immutable rehearsal inputs.");
        }
        return bundle;
    }

    private ScenarioRehearsalRunRepository.Registration registration(
            ScenarioRehearsalExecutionRequest request,
            CompiledScenarioRehearsalPlan plan,
            CapabilitySnapshot.Scope scope,
            String runId,
            IntegrationRequestContext identity) {
        try {
            LinkedHashMap<String, Object> semantics =
                    new LinkedHashMap<>();
            semantics.put("schemaVersion", request.schemaVersion());
            semantics.put("requestId", request.requestId());
            semantics.put(
                    "compiledPlanRef",
                    request.compiledPlanRef());
            semantics.put("scope", scope);
            semantics.put(
                    "authorizedPurpose", identity.purpose());
            String requestFingerprint =
                    ProtocolFingerprint.of(mapper, semantics);
            Instant now = clock.instant();
            Instant runBoundary = now.plus(
                    plan.policy().totalTimeout())
                    .plus(COMMIT_RESERVE);
            Instant retainUntil = later(
                    now.plus(REQUEST_RETENTION),
                    runBoundary);
            return new ScenarioRehearsalRunRepository.Registration(
                    scope,
                    request.requestId(),
                    requestFingerprint,
                    request.compiledPlanRef(),
                    runId,
                    plan.cases().size(),
                    retainUntil);
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "Compiled rehearsal time or retention bounds are invalid.");
        }
    }

    private void requireNotPurged(
            CapabilitySnapshot.Scope scope,
            String runId,
            IntegrationRequestContext identity) {
        try {
            Optional<ScenarioRehearsalRetentionState> state =
                    retention.find(scope, runId);
            if (state.isPresent()
                    && state.orElseThrow().status()
                    == ScenarioRehearsalRetentionState.Status.PURGED) {
                ScenarioRehearsalRetentionEvent proof =
                        state.orElseThrow().deletionProof();
                throw new IntegrationProblemException(
                        IntegrationProblem.gone(
                                "RG.MIRROR.REHEARSAL.EVIDENCE_PURGED",
                                "Scenario rehearsal aggregate evidence was deleted under its retention policy.",
                                identity.correlationId(),
                                Map.of(
                                        "deletionProofFingerprint",
                                        proof.eventFingerprint(),
                                        "purgedAt",
                                        proof.occurredAt().toString())));
            }
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.RETENTION_UNAVAILABLE",
                    "Scenario rehearsal retention authority is unavailable.");
        }
    }

    private ScenarioRehearsalRunRepository.Claim claim(
            ScenarioRehearsalRunRepository.Registration registration,
            CompiledScenarioRehearsalPlan plan,
            IntegrationRequestContext identity) {
        try {
            Duration leaseDuration =
                    plan.policy().totalTimeout()
                            .plus(COMMIT_RESERVE);
            return rehearsalRequests.claim(
                    registration,
                    "scenario-rehearsal-attempt-"
                            + UUID.randomUUID(),
                    leaseDuration);
        } catch (ScenarioRehearsalRunRequestConflictException conflict) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.IDEMPOTENCY_CONFLICT",
                    "The request id already identifies different immutable rehearsal inputs.");
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.COORDINATION_UNAVAILABLE",
                    "The durable Scenario rehearsal coordinator is unavailable.");
        }
    }

    private Instant deadline(
            Instant startedAt,
            CompiledScenarioRehearsalPlan plan,
            IntegrationRequestContext identity) {
        try {
            return startedAt.plus(
                    plan.policy().totalTimeout());
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "Compiled rehearsal time bounds are invalid.");
        }
    }

    private void requireProgress(
            List<ScenarioCaseRehearsalResult> results,
            ScenarioRehearsalExecutionRequest request,
            CompiledScenarioRehearsalPlan plan,
            IntegrationRequestContext identity) {
        if (results.size() > plan.cases().size()) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.PROGRESS_INCONSISTENT",
                    "Durable Scenario progress exceeds the compiled case closure.");
        }
        for (int index = 0; index < results.size(); index++) {
            ScenarioCaseRehearsalResult result =
                    results.get(index);
            CompiledScenarioRehearsalPlan.CaseBinding binding =
                    plan.cases().get(index);
            try {
                ScenarioRehearsalResultIntegrity.verifyCase(
                        mapper, result);
            } catch (IllegalArgumentException invalid) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.REHEARSAL.PROGRESS_INCONSISTENT",
                        "Durable Scenario progress failed content-address verification.");
            }
            if (result.caseIndex() != index
                    || !childRequestId(
                    request.requestId(), index).equals(
                    result.childRequestId())
                    || !binding.scenarioCaseRef().equals(
                    result.scenarioCaseRef())
                    || binding.caseType() != result.caseType()
                    || !binding.testSuiteRef().equals(
                    result.testSuiteRef())
                    || !binding.testCaseId().equals(
                    result.testCaseId())
                    || !binding.mirrorPlanRef().equals(
                    result.mirrorPlanRef())
                    || !binding.fixtureBundleRef().equals(
                    result.fixtureBundleRef())
                    || !Objects.equals(
                    binding.sessionCheckpointRef(),
                    result.sessionCheckpointRef())) {
                throw unavailable(
                        identity,
                        "RG.MIRROR.REHEARSAL.PROGRESS_INCONSISTENT",
                        "Durable Scenario progress differs from the compiled plan.");
            }
        }
    }

    private ScenarioRehearsalEvidenceBundle sealAggregate(
            ScenarioRehearsalExecutionRequest request,
            CompiledScenarioRehearsalPlan plan,
            CapabilitySnapshot.Scope scope,
            String runId,
            Instant admittedAt,
            List<ScenarioCaseRehearsalResult> results,
            IntegrationRequestContext identity) {
        Instant aggregateStartedAt = results.stream()
                .map(ScenarioCaseRehearsalResult::startedAt)
                .min(Instant::compareTo)
                .orElse(admittedAt);
        Instant completedAt = results.stream()
                .map(ScenarioCaseRehearsalResult::completedAt)
                .max(Instant::compareTo)
                .orElse(aggregateStartedAt);
        ScenarioCaseRehearsalResult.Outcome outcome =
                ScenarioRehearsalResult.deriveOutcome(results);
        ScenarioRehearsalResult material =
                new ScenarioRehearsalResult(
                        "", "", request.requestId(),
                        request.compiledPlanRef(), scope,
                        plan.targetCapabilityRef(), outcome,
                        results,
                        ScenarioRehearsalResult.Summary.from(results),
                        aggregateStartedAt, completedAt);
        ScenarioRehearsalResult result =
                ScenarioRehearsalResultIntegrity.seal(
                        mapper, material);
        ScenarioRehearsalEvidenceIntegrityService.SealResult sealed =
                rehearsalEvidenceIntegrity.seal(runId, result);
        if (!sealed.verified()) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_SIGNING_UNAVAILABLE",
                    "Scenario rehearsal evidence could not be signed and verified.");
        }
        return sealed.bundle();
    }

    private void release(
            ScenarioRehearsalRunRepository.Lease lease,
            String failureCode) {
        try {
            rehearsalRequests.release(lease, failureCode);
        } catch (RuntimeException ignored) {
            // Bounded lease expiry remains the takeover path during coordinator outage.
        }
    }

    private List<ResolvedCase> resolveCases(
            CompiledScenarioRehearsalPlan plan,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity,
            Instant admittedAt) {
        List<ResolvedCase> resolved =
                new ArrayList<>(plan.cases().size());
        Instant requiredUntil;
        try {
            requiredUntil = admittedAt.plus(
                    plan.policy().totalTimeout());
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TIME_BOUNDS_INVALID",
                    "Compiled rehearsal time bounds are invalid.");
        }
        for (CompiledScenarioRehearsalPlan.CaseBinding binding
                : plan.cases()) {
            ScenarioCase scenarioCase = scenarioArtifacts.requireCase(
                    scope, binding.scenarioCaseRef(), identity);
            requireBinding(binding, scenarioCase, plan, identity);
            requireLiveCase(
                    scenarioCase, plan.policy(), admittedAt,
                    requiredUntil, identity);
            StoredTestSuite suite = testSuites.find(
                    binding.testSuiteRef().id(),
                    binding.testSuiteRef().revision(),
                    identity);
            TestSuite.TestCase testCase =
                    requireTestCase(binding, suite, identity);
            Map<String, Object> context =
                    requireGraphContext(testCase, identity);
            List<CaseHandlingAssertion> assertions =
                    binding.assertionRefs().stream()
                            .map(ref -> requireLiveAssertion(
                                    scenarioArtifacts.requireAssertion(
                                            scope, ref, identity),
                                    scope, admittedAt, requiredUntil,
                                    identity))
                            .toList();
            MirrorSessionCheckpointBundle checkpoint =
                    binding.sessionCheckpointRef() == null
                            ? null
                            : scenarioArtifacts.requireCheckpoint(
                            scope,
                            binding.sessionCheckpointRef(),
                            identity);
            if (checkpoint != null
                    && !checkpoint.checkpoint().sessionExpiresAt()
                    .isAfter(requiredUntil)) {
                throw conflict(
                        identity,
                        "RG.MIRROR.REHEARSAL.CHECKPOINT_EXPIRES_DURING_RUN",
                        "Scenario checkpoint does not cover the rehearsal deadline.");
            }
            resolved.add(new ResolvedCase(
                    binding, scenarioCase, context, assertions, checkpoint));
        }
        return List.copyOf(resolved);
    }

    private ScenarioCaseRehearsalResult executeCase(
            int caseIndex,
            String aggregateRequestId,
            ResolvedCase resolved,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        Instant startedAt = clock.instant();
        String childRequestId = childRequestId(
                aggregateRequestId, caseIndex);
        MirrorSessionRunBinding sessionBinding =
                sessionBinding(resolved, identity);
        MirrorExecutionRequest childRequest = new MirrorExecutionRequest(
                sessionBinding == null
                        ? MirrorExecutionRequest.SCHEMA_VERSION
                        : MirrorExecutionRequest.STATEFUL_SCHEMA_VERSION,
                childRequestId,
                resolved.binding().mirrorPlanRef().id(),
                resolved.binding().mirrorPlanRef().fingerprint(),
                resolved.context(),
                sessionBinding);
        MirrorRunSummary summary;
        try {
            summary = mirrorRuns.execute(childRequest, identity);
        } catch (IntegrationProblemException rejected) {
            if (rejected.problem().retryable()) {
                throw rejected;
            }
            return preEvidence(
                    caseIndex, childRequestId, resolved,
                    ScenarioCaseRehearsalResult.Outcome.FAIL,
                    machineCode(rejected.problem().code()),
                    startedAt, clock.instant());
        }

        MirrorEvidenceIntegrityService.VerifiedBundle verified;
        try {
            verified = evidenceIntegrity.requireVerified(
                    mirrorRuns.evidence(summary.runId(), identity));
        } catch (IllegalStateException unavailable) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_VERIFIER_UNAVAILABLE",
                    "Scenario evidence verification authority is unavailable.");
        } catch (IllegalArgumentException invalid) {
            return preEvidence(
                    caseIndex, childRequestId, resolved,
                    ScenarioCaseRehearsalResult.Outcome.INDETERMINATE,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_INTEGRITY_INVALID",
                    startedAt, clock.instant());
        }
        MirrorEvidenceBundle bundle = verified.bundle();
        MirrorRunEvidence evidence = bundle.evidence();
        requireChildEvidence(
                resolved.binding(), childRequestId, scope,
                summary, bundle, identity);
        List<ScenarioHandlingAssertionResult> assertionResults =
                resolved.assertions().stream()
                        .map(assertion ->
                                assertionEvaluator.evaluate(
                                        assertion, verified))
                        .toList();
        ScenarioCaseRehearsalResult.Outcome outcome =
                ScenarioCaseRehearsalResult.deriveOutcome(
                        evidence.status(), assertionResults);
        String diagnosticCode =
                diagnosticCode(outcome, evidence.status());
        ScenarioCaseRehearsalResult material =
                new ScenarioCaseRehearsalResult(
                        "", "", caseIndex,
                        resolved.binding().scenarioCaseRef(),
                        resolved.binding().caseType(),
                        resolved.binding().testSuiteRef(),
                        resolved.binding().testCaseId(),
                        resolved.binding().mirrorPlanRef(),
                        resolved.binding().fixtureBundleRef(),
                        resolved.binding().sessionCheckpointRef(),
                        childRequestId, outcome, evidence.runId(),
                        bundle.bundleFingerprint(), evidence.status(),
                        evidence.evidenceClass(), assertionResults,
                        diagnosticCode, evidence.startedAt(),
                        evidence.completedAt());
        return ScenarioRehearsalResultIntegrity.sealCase(mapper, material);
    }

    private MirrorSessionRunBinding sessionBinding(
            ResolvedCase resolved,
            IntegrationRequestContext identity) {
        if (resolved.checkpoint() == null) {
            return null;
        }
        if (sessions == null) {
            throw unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.STATEFUL_RUNTIME_UNAVAILABLE",
                    "Stateful Scenario rehearsal runtime is unavailable.");
        }
        MirrorSessionCheckpoint checkpoint =
                resolved.checkpoint().checkpoint();
        return new MirrorSessionRunBinding(
                checkpoint.sessionId(),
                checkpoint.stateFingerprint());
    }

    private ScenarioCaseRehearsalResult unscheduled(
            int caseIndex,
            String aggregateRequestId,
            ResolvedCase resolved,
            Instant now,
            String code) {
        return preEvidence(
                caseIndex,
                childRequestId(aggregateRequestId, caseIndex),
                resolved,
                ScenarioCaseRehearsalResult.Outcome.INDETERMINATE,
                code, now, now);
    }

    private ScenarioCaseRehearsalResult preEvidence(
            int caseIndex,
            String childRequestId,
            ResolvedCase resolved,
            ScenarioCaseRehearsalResult.Outcome outcome,
            String code,
            Instant startedAt,
            Instant completedAt) {
        ScenarioCaseRehearsalResult material =
                new ScenarioCaseRehearsalResult(
                        "", "", caseIndex,
                        resolved.binding().scenarioCaseRef(),
                        resolved.binding().caseType(),
                        resolved.binding().testSuiteRef(),
                        resolved.binding().testCaseId(),
                        resolved.binding().mirrorPlanRef(),
                        resolved.binding().fixtureBundleRef(),
                        resolved.binding().sessionCheckpointRef(),
                        childRequestId, outcome,
                        "", "", null, null, List.of(),
                        code, startedAt, completedAt);
        return ScenarioRehearsalResultIntegrity.sealCase(mapper, material);
    }

    private static void requireBinding(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            ScenarioCase scenarioCase,
            CompiledScenarioRehearsalPlan plan,
            IntegrationRequestContext identity) {
        if (!plan.scope().equals(scenarioCase.scope())
                || !plan.targetCapabilityRef().equals(
                scenarioCase.targetCapabilityRef())
                || binding.caseType() != scenarioCase.caseType()
                || !binding.testSuiteRef().equals(
                scenarioCase.testSuiteRef())
                || !binding.testCaseId().equals(
                scenarioCase.testCaseId())
                || !binding.mirrorPlanRef().equals(
                scenarioCase.mirrorPlanRef())
                || !binding.fixtureBundleRef().equals(
                scenarioCase.fixtureBundleRef())
                || !Objects.equals(
                binding.sessionCheckpointRef(),
                scenarioCase.sessionCheckpointRef())
                || !binding.executionServices().equals(
                scenarioCase.executionServices())
                || !binding.assertionRefs().equals(
                scenarioCase.assertionRefs())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_BINDING_STALE",
                    "ScenarioCase differs from the compiled execution binding.");
        }
    }

    private static void requireLiveCase(
            ScenarioCase scenarioCase,
            ScenarioPack.RehearsalPolicy policy,
            Instant admittedAt,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        ArtifactProvenance provenance = scenarioCase.provenance();
        boolean inactive = scenarioCase.lifecycle()
                == CapabilitySnapshot.Lifecycle.STALE
                || scenarioCase.lifecycle()
                == CapabilitySnapshot.Lifecycle.REVOKED
                || policy.certificationRequired()
                && scenarioCase.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE;
        boolean invalidProvenance =
                scenarioCase.createdAt().isAfter(admittedAt)
                        || !provenance.revocationRef().isBlank()
                        || provenance.expiresAt() != null
                        && !provenance.expiresAt().isAfter(requiredUntil)
                        || policy.certificationRequired()
                        && (provenance.approvedAt() == null
                        || provenance.approvedAt().isAfter(admittedAt));
        if (inactive || invalidProvenance) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_LIFECYCLE_INVALID",
                    "ScenarioCase is not admitted at execution time.");
        }
    }

    private static CaseHandlingAssertion requireLiveAssertion(
            CaseHandlingAssertion assertion,
            CapabilitySnapshot.Scope scope,
            Instant admittedAt,
            Instant requiredUntil,
            IntegrationRequestContext identity) {
        ArtifactProvenance provenance = assertion.provenance();
        if (!scope.equals(assertion.scope())
                || assertion.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE
                || assertion.createdAt().isAfter(admittedAt)
                || provenance.approvedAt() == null
                || provenance.approvedAt().isAfter(admittedAt)
                || provenance.expiresAt() != null
                && !provenance.expiresAt().isAfter(requiredUntil)
                || !provenance.revocationRef().isBlank()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.ASSERTION_LIFECYCLE_INVALID",
                    "Handling assertion is not approved for the rehearsal window.");
        }
        return assertion;
    }

    private TestSuite.TestCase requireTestCase(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            StoredTestSuite suite,
            IntegrationRequestContext identity) {
        MirrorArtifactRef actual = new MirrorArtifactRef(
                "TEST_SUITE", suite.suiteId(), suite.revision(),
                suite.fingerprint());
        if (!binding.testSuiteRef().equals(actual)
                || !suite.enterpriseScoped()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_SUITE_STALE",
                    "TestSuite differs from the compiled execution binding.");
        }
        List<TestSuite.TestCase> matches = suite.suite().cases().stream()
                .filter(candidate -> binding.testCaseId().equals(
                        candidate.caseId()))
                .toList();
        if (matches.size() != 1) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_CASE_STALE",
                    "Compiled TestSuite case is missing or ambiguous.");
        }
        TestSuite.TestCase testCase = matches.getFirst();
        TestSuite.FixtureBundleRef fixture =
                testCase.fixtureBundleRef();
        MirrorArtifactRef fixtureRef = new MirrorArtifactRef(
                "FIXTURE_BUNDLE",
                fixture.fixtureBundleId(),
                fixture.revision(),
                fixture.fingerprint());
        if (!binding.fixtureBundleRef().equals(fixtureRef)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.TEST_CASE_FIXTURE_STALE",
                    "TestSuite case fixture differs from the compiled binding.");
        }
        return testCase;
    }

    private Map<String, Object> requireGraphContext(
            TestSuite.TestCase testCase,
            IntegrationRequestContext identity) {
        if (!(testCase.input() instanceof Map<?, ?>)) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_INPUT_NOT_CONTEXT",
                    "Generation-one Mirror rehearsal requires a graph-context test input.");
        }
        try {
            return mapper.convertValue(
                    testCase.input(),
                    new TypeReference<>() {
                    });
        } catch (RuntimeException invalid) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CASE_INPUT_INVALID",
                    "TestSuite case input cannot be detached as a graph context.");
        }
    }

    private static void requireChildEvidence(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            String childRequestId,
            CapabilitySnapshot.Scope scope,
            MirrorRunSummary summary,
            MirrorEvidenceBundle bundle,
            IntegrationRequestContext identity) {
        MirrorRunEvidence evidence = bundle.evidence();
        if (!summary.runId().equals(evidence.runId())
                || !childRequestId.equals(summary.requestId())
                || !childRequestId.equals(evidence.requestId())
                || !binding.mirrorPlanRef().id().equals(
                evidence.planId())
                || !binding.mirrorPlanRef().fingerprint().equals(
                evidence.planFingerprint())
                || !binding.fixtureBundleRef().equals(
                evidence.fixtureBundleRef())
                || !scope.equals(evidence.scope())
                || !bundle.bundleFingerprint().equals(
                summary.evidenceBundleFingerprint())
                || summary.status() != evidence.status()
                || summary.evidenceClass() != evidence.evidenceClass()) {
            throw conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.CHILD_EVIDENCE_IDENTITY_INVALID",
                    "Mirror child evidence differs from the compiled case binding.");
        }
    }

    private static String diagnosticCode(
            ScenarioCaseRehearsalResult.Outcome outcome,
            MirrorRunEvidence.Status status) {
        if (outcome == ScenarioCaseRehearsalResult.Outcome.PASS) {
            return "";
        }
        if (status == MirrorRunEvidence.Status.PASSED) {
            return outcome == ScenarioCaseRehearsalResult.Outcome.FAIL
                    ? "RG.MIRROR.REHEARSAL.BLOCKER_ASSERTION_FAILED"
                    : "RG.MIRROR.REHEARSAL.BLOCKER_ASSERTION_INDETERMINATE";
        }
        return outcome == ScenarioCaseRehearsalResult.Outcome.FAIL
                ? "RG.MIRROR.REHEARSAL.CASE_EXECUTION_FAILED"
                : "RG.MIRROR.REHEARSAL.CASE_EVIDENCE_INDETERMINATE";
    }

    private static String childRequestId(
            String aggregateRequestId, int caseIndex) {
        return aggregateRequestId + ":case:"
                + String.format("%03d", caseIndex);
    }

    private static Instant later(
            Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private static String machineCode(String value) {
        String normalized = value == null ? "" : value.trim();
        return normalized.matches("[A-Z][A-Z0-9_.-]{0,254}")
                ? normalized
                : "RG.MIRROR.REHEARSAL.CASE_REJECTED";
    }

    private static void requirePurpose(IntegrationRequestContext identity) {
        if (identity == null || !PURPOSE.equals(identity.purpose())) {
            throw new IntegrationProblemException(
                    IntegrationProblem.forbidden(
                            "RG.MIRROR.REHEARSAL.PURPOSE_REQUIRED",
                            "Scenario rehearsal requires MIRROR_REHEARSAL purpose.",
                            identity == null ? "" : identity.correlationId(),
                            Map.of()));
        }
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.conflict(
                        code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity,
            String code,
            String title) {
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        code, title, identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException evidenceStoreFailure(
            ScenarioRehearsalEvidenceStoreException failure,
            IntegrationRequestContext identity) {
        return switch (failure.reason()) {
            case CONFLICT -> conflict(
                    identity,
                    "RG.MIRROR.REHEARSAL.RUN_ID_CONFLICT",
                    "Scenario run id already identifies different terminal evidence.");
            case INTEGRITY_INVALID -> unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_INCONSISTENT",
                    "Stored Scenario evidence failed independent verification.");
            case VERIFICATION_UNAVAILABLE -> unavailable(
                    identity,
                    "RG.MIRROR.REHEARSAL.EVIDENCE_VERIFIER_UNAVAILABLE",
                    "Scenario evidence verification authority is unavailable.");
        };
    }

    private record ResolvedCase(
            CompiledScenarioRehearsalPlan.CaseBinding binding,
            ScenarioCase scenarioCase,
            Map<String, Object> context,
            List<CaseHandlingAssertion> assertions,
            MirrorSessionCheckpointBundle checkpoint) {
        private ResolvedCase {
            binding = Objects.requireNonNull(binding, "binding");
            scenarioCase = Objects.requireNonNull(
                    scenarioCase, "scenarioCase");
            context = Collections.unmodifiableMap(
                    new LinkedHashMap<>(
                            Objects.requireNonNull(context, "context")));
            assertions = List.copyOf(assertions);
        }
    }
}
