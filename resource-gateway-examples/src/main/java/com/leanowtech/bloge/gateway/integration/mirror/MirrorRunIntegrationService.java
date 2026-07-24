package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.ReservedKeys;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunRejectedException;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunRequest;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunResult;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunEvidenceProjector;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorRunService;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorResolver;
import com.leanowtech.bloge.gateway.testing.runtime.MirrorStateRunSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Protected application boundary for durable, idempotent mirror execution and evidence reads.
 *
 * <p>The service admits only an authenticated test/staging scope and the MIRROR_REHEARSAL
 * purpose. It binds BLOGE tenant/namespace coordinates from that identity, claims a payload-free
 * durable request lease, rehydrates the exact sealed plan generation, executes the independent
 * engine, and atomically publishes signed evidence plus terminal idempotency state.</p>
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(prefix = "gateway.testing.mirror", name = "enabled", havingValue = "true")
public class MirrorRunIntegrationService {
    /** Minimum period in which an exact completed request remains idempotently discoverable. */
    public static final Duration REQUEST_RETENTION = Duration.ofDays(30);
    /** Wall-clock reserve for evidence projection, signing, and atomic commit. */
    public static final Duration EVIDENCE_COMMIT_RESERVE = Duration.ofMinutes(2);
    private static final String INTERNAL_NODE_OUTPUT_PREFIX = "__nodeOutput:";
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,511}");

    private final MirrorPlanIntegrationService plans;
    private final MirrorRunService runtime;
    private final MirrorRunRequestRepository requests;
    private final MirrorEvidenceRepository evidence;
    private final MirrorRunCommitService commits;
    private final ObjectMapper mapper;
    private final MirrorOperationObservability observations;
    private final Clock clock;
    private final MirrorDeploymentIsolationRunTrustAuthority deploymentTrust;
    private final MirrorSessionIntegrationService sessions;

    /** Creates the protected execution boundary using the server UTC clock. */
    @Autowired
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust,
            ObjectProvider<MirrorSessionIntegrationService> sessionProvider) {
        this(plans, runtime, requests, evidence, commits, mapper, observations,
                Clock.systemUTC(), deploymentTrust,
                Objects.requireNonNull(
                        sessionProvider, "sessionProvider").getIfAvailable());
    }

    /** Compatibility constructor for compositions without stateful mirror sessions. */
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust) {
        this(plans, runtime, requests, evidence, commits, mapper,
                observations, Clock.systemUTC(), deploymentTrust, null);
    }

    /** Full constructor for deterministic admission and lease tests. */
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock) {
        this(plans, runtime, requests, evidence, commits, mapper, observations, clock,
                MirrorDeploymentIsolationRunTrustAuthority.unavailable(), null);
    }

    /** Full constructor with deterministic time and deployment trust. */
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deploymentTrust = Objects.requireNonNull(deploymentTrust, "deploymentTrust");
        this.sessions = null;
    }

    /**
     * Full constructor with deterministic time, deployment trust, and stateful session boundary.
     */
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            MirrorOperationObservability observations,
            Clock clock,
            MirrorDeploymentIsolationRunTrustAuthority deploymentTrust,
            MirrorSessionIntegrationService sessions) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.observations = Objects.requireNonNull(observations, "observations");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.deploymentTrust = Objects.requireNonNull(
                deploymentTrust, "deploymentTrust");
        this.sessions = sessions;
    }

    /**
     * Executes an exact plan once or returns the terminal result of an identical durable retry.
     *
     * @param request strict public execution command
     * @param identity authenticated enterprise identity and mirror purpose
     * @return payload-free terminal run summary
     */
    public MirrorRunSummary execute(
            MirrorExecutionRequest request, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.RUN_CREATE, identity,
                request == null ? "" : request.requestId(),
                request == null ? "" : request.planId(), "");
        try {
            return executeObserved(request, identity, observation);
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    private MirrorRunSummary executeObserved(
            MirrorExecutionRequest request,
            IntegrationRequestContext identity,
            MirrorOperationObservability.Observation observation) {
        Objects.requireNonNull(request, "request");
        CapabilitySnapshot.Scope scope = MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        MirrorPlan plan = plans.findForExecution(request.planId(), identity);
        if (!plan.planFingerprint().equals(request.expectedPlanFingerprint())) {
            throw conflict(identity, "RG.MIRROR.PLAN_FINGERPRINT_CONFLICT",
                    "The persisted plan differs from the execution generation reviewed by the caller.",
                    Map.of("currentPlanFingerprint", plan.planFingerprint()));
        }
        validateSessionBinding(request, plan, identity);

        GraphContext effectiveContext = effectiveContext(request.context(), scope, identity);
        String contextFingerprint;
        try {
            contextFingerprint = ProtocolFingerprint.ofBounded(mapper, effectiveContext.asMap(),
                    MirrorRunEvidenceProjector.MAXIMUM_PAYLOAD_BYTES);
        } catch (IllegalArgumentException oversized) {
            throw badRequest(identity, "RG.MIRROR.CONTEXT_TOO_LARGE",
                    "Server-bound mirror context exceeds the evidence fingerprint limit.",
                    Map.of("maximumBytes", MirrorRunEvidenceProjector.MAXIMUM_PAYLOAD_BYTES));
        }
        MirrorDeploymentIsolationRunTrust.Admission trustAdmission =
                admitDeploymentTrust(plan, scope, identity);
        String trustDecisionFingerprint = trustAdmission == null
                ? "" : trustAdmission.decisionRef().fingerprint();
        LinkedHashMap<String, Object> requestIdentity = new LinkedHashMap<>(Map.of(
                "schemaVersion", request.schemaVersion(),
                "requestId", request.requestId(),
                "planId", plan.planId(),
                "planFingerprint", plan.planFingerprint(),
                "requestContextFingerprint", contextFingerprint,
                "deploymentTrustDecisionFingerprint", trustDecisionFingerprint,
                "scope", scope,
                "authorizedPurpose", identity.purpose()));
        if (request.sessionBinding() != null) {
            requestIdentity.put("sessionBinding", request.sessionBinding());
        }
        String requestFingerprint = ProtocolFingerprint.of(
                mapper, requestIdentity);
        Instant now = clock.instant();
        Instant retainUntil = later(plan.expiresAt(), now.plus(REQUEST_RETENTION));
        MirrorRunRequestRepository.Registration registration =
                new MirrorRunRequestRepository.Registration(scope, request.requestId(),
                        requestFingerprint, contextFingerprint, plan.planId(),
                        plan.planFingerprint(), retainUntil,
                        trustAdmission == null
                                ? MirrorRunRequestRepository.TrustDecision.exploratory()
                                : MirrorRunRequestRepository.TrustDecision.certification(
                                trustAdmission));
        MirrorRunRequestRepository.Claim claim = claim(
                registration, trustAdmission, plan, identity);
        if (claim.outcome() == MirrorRunRequestRepository.Outcome.IN_PROGRESS) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.MIRROR.RUN_REQUEST_IN_PROGRESS",
                    "An identical mirror execution request is already in progress.",
                    identity.correlationId(), Map.of(
                            "retryAfterSeconds", claim.retryAfterSeconds())));
        }
        if (claim.outcome() == MirrorRunRequestRepository.Outcome.COMPLETED) {
            MirrorRunSummary completed = completedRetry(claim.state(), identity);
            observation.succeeded(completed.runId());
            return completed;
        }

        MirrorRunRequestRepository.Lease lease = claim.lease();
        try {
            if (!clock.instant().isBefore(plan.expiresAt())) {
                throw new IntegrationProblemException(IntegrationProblem.gone(
                        "RG.MIRROR.RUN_EXPIRED", "The mirror plan has expired.",
                        identity.correlationId(), Map.of()));
            }
            try (CompiledMirrorPlan generation = plans.materialize(plan, identity)) {
                MirrorResolver.SessionContext sessionContext =
                        sessionContext(request, plan, identity);
                MirrorRunResult result = runtime.execute(new MirrorRunRequest(request.requestId(),
                        generation, effectiveContext, scope,
                        MirrorPlanIntegrationService.AUTHORIZED_PURPOSE,
                        trustAdmission, sessionContext));
                MirrorRunSummary summary = MirrorRunSummary.from(result.evidenceBundle());
                commits.commit(lease, result.evidenceBundle(), observation);
                return summary;
            }
        } catch (IntegrationProblemException expected) {
            release(lease, expected.problem().code());
            throw expected;
        } catch (MirrorRunRejectedException rejected) {
            release(lease, rejected.code());
            throw runtimeProblem(identity, rejected);
        } catch (MirrorRunLeaseLostException stale) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.MIRROR.RUN_LEASE_LOST",
                    "Mirror execution authority expired before terminal evidence commit.",
                    identity.correlationId(), Map.of("retryAfterSeconds", 1)));
        } catch (MirrorDeploymentIsolationRunTrustAuthority.TrustException denied) {
            release(lease, "RG.MIRROR.DEPLOYMENT_TRUST_CHANGED");
            throw serviceUnavailable(identity, "RG.MIRROR.DEPLOYMENT_TRUST_CHANGED",
                    "Deployment isolation trust changed before terminal evidence commit.");
        } catch (RuntimeException unavailable) {
            release(lease, "RG.MIRROR.RUN_UNAVAILABLE");
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_UNAVAILABLE",
                    "The isolated mirror runtime or evidence store is unavailable.");
        }
    }

    private MirrorResolver.SessionContext sessionContext(
            MirrorExecutionRequest request,
            MirrorPlan plan,
            IntegrationRequestContext identity) {
        if (request.sessionBinding() == null) {
            return null;
        }
        if (sessions == null) {
            throw serviceUnavailable(
                    identity, "RG.MIRROR.STATEFUL_RUNTIME_UNAVAILABLE",
                    "The stateful mirror session runtime is unavailable.");
        }
        MirrorSessionStateStore.SessionSnapshot snapshot =
                sessions.snapshotForRun(
                        request.sessionBinding(),
                        plan.planFingerprint(),
                        identity);
        if (!plan.stateModelRefs().contains(
                snapshot.payload().state().stateModelRef())) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SESSION.STATE_MODEL_NOT_ADMITTED",
                    "The session state model is not admitted by this plan generation.",
                    Map.of());
        }
        LinkedHashMap<String, MirrorArtifactRef> capabilitiesBySite =
                new LinkedHashMap<>();
        boolean writable = false;
        for (MirrorPlan.ExternalBinding binding
                : plan.externalBindings()) {
            StateInteraction interaction = validateStateInteraction(
                    plan, binding, snapshot.payload(), identity);
            writable = writable
                    || interaction == StateInteraction.WRITE;
            if (capabilitiesBySite.put(
                    binding.invocationSiteId(),
                    binding.capabilityRef()) != null) {
                throw serviceUnavailable(
                        identity,
                        "RG.MIRROR.PLAN_BINDING_INCONSISTENT",
                        "The mirror plan contains duplicate invocation-site bindings.");
            }
        }
        MirrorStateRunSession runSession = writable
                ? new MirrorStateRunSession(
                mapper, snapshot.payload(),
                (writeEffectRef, input,
                 expectedStateFingerprint) ->
                        sessions.commandForRun(
                                request.sessionBinding().sessionId(),
                                writeEffectRef, input,
                                expectedStateFingerprint,
                                identity))
                : null;
        return new MirrorResolver.SessionContext(
                snapshot.payload(),
                plan.planFingerprint(),
                capabilitiesBySite,
                runSession);
    }

    private static StateInteraction validateStateInteraction(
            MirrorPlan plan,
            MirrorPlan.ExternalBinding binding,
            MirrorSessionPayload payload,
            IntegrationRequestContext identity) {
        if (!binding.resolverOrder().contains(
                MirrorPlan.MirrorSource.SESSION_STATE)) {
            return StateInteraction.NONE;
        }
        List<StateReadSpec> reads = payload.stateReadSpecs().stream()
                .filter(spec -> spec.targetCapabilityRef().equals(
                        binding.capabilityRef()))
                .toList();
        List<WriteEffectSpec> writes = payload.writeEffects().stream()
                .filter(effect -> effect.targetCapabilityRef().equals(
                        binding.capabilityRef()))
                .toList();
        CapabilitySnapshot capability =
                plan.capabilityClosure().stream()
                        .filter(candidate ->
                                CapabilityClosureIntegrity
                                        .reference(candidate)
                                        .equals(binding.capabilityRef()))
                        .findFirst()
                        .orElseThrow(() -> serviceUnavailable(
                                identity,
                                "RG.MIRROR.PLAN_BINDING_INCONSISTENT",
                                "The stateful plan capability closure is incomplete."));
        boolean virtualWrite =
                capability.contract().effect().mode()
                        == EffectContract.Mode.VIRTUAL_MUTATION;
        if (reads.size() + writes.size() == 0) {
            throw conflict(
                    identity,
                    virtualWrite
                            ? "RG.MIRROR.SESSION.WRITE_EFFECT_MISSING"
                            : "RG.MIRROR.SESSION.READ_SPEC_MISSING",
                    virtualWrite
                            ? "The session has no write effect for an admitted virtual-write site."
                            : "The session has no state read specification for an admitted plan site.",
                    Map.of("invocationSiteId",
                            binding.invocationSiteId()));
        }
        if (reads.size() + writes.size() != 1) {
            throw serviceUnavailable(
                    identity,
                    "RG.MIRROR.SESSION.INTERACTION_SPEC_INCONSISTENT",
                    "The session has ambiguous state interaction specifications.");
        }
        if (!reads.isEmpty()) {
            if (virtualWrite) {
                throw conflict(
                        identity,
                        "RG.MIRROR.SESSION.INTERACTION_SPEC_INCONSISTENT",
                        "A virtual-write capability cannot be lowered through a state read specification.",
                        Map.of("invocationSiteId",
                                binding.invocationSiteId()));
            }
            if (reads.getFirst().lifecycle()
                    != CapabilitySnapshot.Lifecycle.ACTIVE) {
                throw conflict(
                        identity,
                        "RG.MIRROR.SESSION.READ_SPEC_NOT_ACTIVE",
                        "The session state read specification is not active.",
                        Map.of("invocationSiteId",
                                binding.invocationSiteId()));
            }
            return StateInteraction.READ;
        }
        WriteEffectSpec effect = writes.getFirst();
        if (effect.lifecycle()
                != CapabilitySnapshot.Lifecycle.ACTIVE) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SESSION.WRITE_EFFECT_NOT_ACTIVE",
                    "The session write effect is not active.",
                    Map.of("invocationSiteId",
                            binding.invocationSiteId()));
        }
        if (!virtualWrite
                || !effect.stateModelRef().equals(
                capability.contract().stateModelRef())
                || !binding.resolverOrder().equals(
                List.of(
                        MirrorPlan.MirrorSource.SESSION_STATE,
                        MirrorPlan.MirrorSource.ABSTAINED))) {
            throw conflict(
                    identity,
                    "RG.MIRROR.SESSION.WRITE_BINDING_NOT_ADMITTED",
                    "A graph virtual write requires an exact state model and terminal Session-only resolution.",
                    Map.of("invocationSiteId",
                            binding.invocationSiteId()));
        }
        return StateInteraction.WRITE;
    }

    private enum StateInteraction {
        NONE,
        READ,
        WRITE
    }

    private static void validateSessionBinding(
            MirrorExecutionRequest request,
            MirrorPlan plan,
            IntegrationRequestContext identity) {
        boolean statefulPlan = !plan.stateModelRefs().isEmpty();
        if (statefulPlan && request.sessionBinding() == null) {
            throw badRequest(
                    identity,
                    "RG.MIRROR.SESSION.BINDING_REQUIRED",
                    "A state-model-backed plan requires execution request v2 "
                            + "and an exact Session state binding.",
                    Map.of());
        }
        if (!statefulPlan && request.sessionBinding() != null) {
            throw badRequest(
                    identity,
                    "RG.MIRROR.SESSION.BINDING_NOT_ADMITTED",
                    "A stateless plan cannot accept a Session state binding.",
                    Map.of());
        }
    }

    /** Reads one payload-free terminal run summary in the exact authenticated scope. */
    public MirrorRunSummary find(String runId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.RUN_READ, identity, "", "", runId);
        MirrorRunSummary summary;
        try {
            summary = MirrorRunSummary.from(requireEvidence(runId, identity));
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
        observation.succeeded(summary.runId());
        return summary;
    }

    /** Reads one independently verified payload-free evidence bundle in the exact scope. */
    public MirrorEvidenceBundle evidence(String runId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation = observations.start(
                MirrorOperationAuditEvent.Operation.EVIDENCE_READ, identity, "", "", runId);
        MirrorEvidenceBundle bundle;
        try {
            bundle = requireEvidence(runId, identity);
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
        observation.succeeded(bundle.evidence().runId());
        return bundle;
    }

    /**
     * Projects one verified stateful run into a deterministic ANEKE workbook seed.
     *
     * @param runId terminal stateful mirror run identity
     * @param identity authenticated enterprise identity and mirror purpose
     * @return payload-free seed bound to the signed evidence bundle and Session state head
     */
    public MirrorStateWorkbookSeed stateWorkbookSeed(
            String runId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation.EVIDENCE_READ,
                        identity, "", "", runId);
        try {
            MirrorStateWorkbookSeed seed =
                    MirrorStateWorkbookSeed.project(
                            mapper, requireEvidence(runId, identity));
            observation.succeeded(seed.runId());
            return seed;
        } catch (IntegrationProblemException expected) {
            throw observation.failed(expected);
        } catch (IllegalArgumentException invalid) {
            throw observation.failed(conflict(
                    identity,
                    "RG.MIRROR.STATE_WORKBOOK_SEED_UNAVAILABLE",
                    "The run does not contain a complete stateful evidence closure.",
                    Map.of()));
        } catch (RuntimeException unavailable) {
            throw observation.failed(serviceUnavailable(
                    identity,
                    "RG.MIRROR.STATE_WORKBOOK_SEED_UNAVAILABLE",
                    "The state workbook seed could not be projected safely."));
        }
    }

    /**
     * Projects one verified read/write run into a deterministic ANEKE transition-workbook seed.
     *
     * @param runId terminal read/write mirror run identity
     * @param identity authenticated enterprise identity and mirror purpose
     * @return payload-free seed bound to exact state heads, receipts, and event assertions
     */
    public MirrorStateTransitionWorkbookSeed
    stateTransitionWorkbookSeed(
            String runId, IntegrationRequestContext identity) {
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation.EVIDENCE_READ,
                        identity, "", "", runId);
        try {
            MirrorStateTransitionWorkbookSeed seed =
                    MirrorStateTransitionWorkbookSeed.project(
                            mapper, requireEvidence(runId, identity));
            observation.succeeded(seed.runId());
            return seed;
        } catch (IntegrationProblemException expected) {
            throw observation.failed(expected);
        } catch (IllegalArgumentException invalid) {
            throw observation.failed(conflict(
                    identity,
                    "RG.MIRROR.STATE_TRANSITION_WORKBOOK_SEED_UNAVAILABLE",
                    "The run does not contain a complete read/write state evidence closure.",
                    Map.of()));
        } catch (RuntimeException unavailable) {
            throw observation.failed(serviceUnavailable(
                    identity,
                    "RG.MIRROR.STATE_TRANSITION_WORKBOOK_SEED_UNAVAILABLE",
                    "The state transition workbook seed could not be projected safely."));
        }
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            MirrorDeploymentIsolationRunTrust.Admission trustAdmission,
            MirrorPlan plan,
            IntegrationRequestContext identity) {
        try {
            String owner = "mirror-attempt-" + UUID.randomUUID();
            Duration duration = plan.policy().timeout().plus(EVIDENCE_COMMIT_RESERVE);
            return trustAdmission == null
                    ? requests.claim(registration, owner, duration)
                    : requests.claim(registration, owner, duration,
                    MirrorRunRequestRepository.TrustAttempt.from(trustAdmission));
        } catch (MirrorRunRequestConflictException conflict) {
            throw conflict(identity, "RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT",
                    "The request id already identifies different immutable execution inputs.",
                    Map.of());
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_COORDINATION_UNAVAILABLE",
                    "The durable mirror request coordinator is unavailable.");
        }
    }

    private MirrorDeploymentIsolationRunTrust.Admission admitDeploymentTrust(
            MirrorPlan plan,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (!plan.policy().certificationRequired()) {
            return null;
        }
        try {
            return deploymentTrust.admit(scope);
        } catch (MirrorDeploymentIsolationRunTrustAuthority.TrustException denied) {
            throw serviceUnavailable(identity, "RG.MIRROR.DEPLOYMENT_TRUST_UNAVAILABLE",
                    "Certification-required deployment isolation trust is unavailable.");
        }
    }

    private MirrorRunSummary completedRetry(
            MirrorRunRequestRepository.State state,
            IntegrationRequestContext identity) {
        MirrorEvidenceBundle bundle;
        try {
            bundle = evidence.find(state.registration().scope(), state.runId()).orElseThrow();
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_EVIDENCE_INCONSISTENT",
                    "Completed mirror request evidence is absent or failed integrity verification.");
        }
        MirrorRunEvidence run = bundle.evidence();
        if (!state.evidenceBundleFingerprint().equals(bundle.bundleFingerprint())
                || !state.registration().requestId().equals(run.requestId())
                || !state.registration().contextFingerprint()
                .equals(run.requestContextFingerprint())
                || !state.registration().planId().equals(run.planId())
                || !state.registration().planFingerprint().equals(run.planFingerprint())
                || !state.registration().scope().equals(run.scope())
                || !completedTrustMatches(state, run)) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_EVIDENCE_INCONSISTENT",
                    "Completed mirror request evidence differs from its durable coordination state.");
        }
        return MirrorRunSummary.from(bundle);
    }

    private static boolean completedTrustMatches(
            MirrorRunRequestRepository.State state, MirrorRunEvidence evidence) {
        MirrorRunRequestRepository.TrustDecision decision =
                state.registration().trustDecision();
        MirrorDeploymentIsolationRunTrust.Binding binding =
                evidence.isolation().deploymentTrustBinding();
        if (!decision.certificationRequired()) {
            return binding == null && state.trustAttempt() == null;
        }
        return binding != null && state.trustAttempt() != null
                && decision.decisionRef().equals(binding.decisionRef())
                && state.trustAttempt().admittedSnapshotRef().equals(
                binding.admittedSnapshotRef());
    }

    private MirrorEvidenceBundle requireEvidence(
            String runId, IntegrationRequestContext identity) {
        CapabilitySnapshot.Scope scope = MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        String id = runId == null ? "" : runId.trim();
        if (!IDENTIFIER.matcher(id).matches()) {
            throw badRequest(identity, "RG.MIRROR.RUN_ID_INVALID",
                    "Mirror run id is invalid.", Map.of());
        }
        try {
            Optional<MirrorEvidenceBundle> found = evidence.find(scope, id);
            return found.orElseThrow(() -> new IntegrationProblemException(
                    IntegrationProblem.notFound("RG.MIRROR.RUN_NOT_FOUND",
                            "Mirror run was not found in the authorized scope.",
                            identity.correlationId(), Map.of())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_EVIDENCE_UNAVAILABLE",
                    "The isolated mirror evidence store is unavailable.");
        }
    }

    private static GraphContext effectiveContext(
            Map<String, Object> supplied,
            CapabilitySnapshot.Scope scope,
            IntegrationRequestContext identity) {
        if (supplied.containsKey(ReservedKeys.TENANT_ID)
                || supplied.containsKey(ReservedKeys.NAMESPACE)
                || supplied.keySet().stream().anyMatch(key ->
                key.startsWith(INTERNAL_NODE_OUTPUT_PREFIX))) {
            throw badRequest(identity, "RG.MIRROR.CONTEXT_RESERVED_KEY",
                    "Mirror context contains a server-owned BLOGE key.", Map.of());
        }
        LinkedHashMap<String, Object> bound = new LinkedHashMap<>(supplied);
        bound.put(ReservedKeys.TENANT_ID, scope.tenantId());
        bound.put(ReservedKeys.NAMESPACE, scope.projectId());
        return new GraphContext(bound);
    }

    private void release(MirrorRunRequestRepository.Lease lease, String code) {
        try {
            requests.release(lease, code);
        } catch (RuntimeException ignored) {
            // The bounded lease expiry remains the recovery path when the store is unavailable.
        }
    }

    private static IntegrationProblemException runtimeProblem(
            IntegrationRequestContext identity, MirrorRunRejectedException rejected) {
        return switch (rejected.code()) {
            case "RG.MIRROR.RUN_EXPIRED" -> new IntegrationProblemException(
                    IntegrationProblem.gone(rejected.code(), "The mirror plan has expired.",
                            identity.correlationId(), Map.of()));
            case "RG.MIRROR.EVIDENCE_SIGNER_UNAVAILABLE",
                 "RG.MIRROR.EVIDENCE_INTEGRITY_REJECTED",
                 "RG.MIRROR.RUN_EVIDENCE_REJECTED",
                 "RG.MIRROR.RESOLUTION_EVIDENCE_REJECTED",
                 "RG.MIRROR.STATE_EVIDENCE_REJECTED",
                 "RG.MIRROR.DEPLOYMENT_TRUST_REQUIRED",
                 "RG.MIRROR.DEPLOYMENT_TRUST_CHANGED" ->
                    serviceUnavailable(identity, rejected.code(),
                            "Mirror execution evidence could not be finalized safely.");
            default -> conflict(identity, rejected.code(),
                    "The sealed mirror generation was rejected before safe completion.", Map.of());
        };
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

    private static IntegrationProblemException serviceUnavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static Instant later(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }
}
