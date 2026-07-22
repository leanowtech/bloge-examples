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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
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
    private final Clock clock;

    /** Creates the protected execution boundary using the server UTC clock. */
    @Autowired
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper) {
        this(plans, runtime, requests, evidence, commits, mapper, Clock.systemUTC());
    }

    /** Full constructor for deterministic admission and lease tests. */
    public MirrorRunIntegrationService(
            MirrorPlanIntegrationService plans,
            MirrorRunService runtime,
            MirrorRunRequestRepository requests,
            MirrorEvidenceRepository evidence,
            MirrorRunCommitService commits,
            ObjectMapper mapper,
            Clock clock) {
        this.plans = Objects.requireNonNull(plans, "plans");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.requests = Objects.requireNonNull(requests, "requests");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
        this.commits = Objects.requireNonNull(commits, "commits");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
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
        Objects.requireNonNull(request, "request");
        CapabilitySnapshot.Scope scope = MirrorPlanIntegrationService.requireMirrorIdentity(identity);
        MirrorPlan plan = plans.find(request.planId(), identity);
        if (!plan.planFingerprint().equals(request.expectedPlanFingerprint())) {
            throw conflict(identity, "RG.MIRROR.PLAN_FINGERPRINT_CONFLICT",
                    "The persisted plan differs from the execution generation reviewed by the caller.",
                    Map.of("currentPlanFingerprint", plan.planFingerprint()));
        }

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
        String requestFingerprint = ProtocolFingerprint.of(mapper, Map.of(
                "schemaVersion", request.schemaVersion(),
                "requestId", request.requestId(),
                "planId", plan.planId(),
                "planFingerprint", plan.planFingerprint(),
                "requestContextFingerprint", contextFingerprint,
                "scope", scope,
                "authorizedPurpose", identity.purpose()));
        Instant now = clock.instant();
        Instant retainUntil = later(plan.expiresAt(), now.plus(REQUEST_RETENTION));
        MirrorRunRequestRepository.Registration registration =
                new MirrorRunRequestRepository.Registration(scope, request.requestId(),
                        requestFingerprint, contextFingerprint, plan.planId(),
                        plan.planFingerprint(), retainUntil);
        MirrorRunRequestRepository.Claim claim = claim(registration, plan, identity);
        if (claim.outcome() == MirrorRunRequestRepository.Outcome.IN_PROGRESS) {
            throw new IntegrationProblemException(IntegrationProblem.retryableConflict(
                    "RG.MIRROR.RUN_REQUEST_IN_PROGRESS",
                    "An identical mirror execution request is already in progress.",
                    identity.correlationId(), Map.of(
                            "retryAfterSeconds", claim.retryAfterSeconds())));
        }
        if (claim.outcome() == MirrorRunRequestRepository.Outcome.COMPLETED) {
            return completedRetry(claim.state(), identity);
        }

        MirrorRunRequestRepository.Lease lease = claim.lease();
        try {
            if (!clock.instant().isBefore(plan.expiresAt())) {
                throw new IntegrationProblemException(IntegrationProblem.gone(
                        "RG.MIRROR.RUN_EXPIRED", "The mirror plan has expired.",
                        identity.correlationId(), Map.of()));
            }
            CompiledMirrorPlan generation = plans.materialize(plan, identity);
            MirrorRunResult result = runtime.execute(new MirrorRunRequest(request.requestId(),
                    generation, effectiveContext, scope,
                    MirrorPlanIntegrationService.AUTHORIZED_PURPOSE));
            MirrorEvidenceBundle persisted = commits.commit(lease, result.evidenceBundle());
            return MirrorRunSummary.from(persisted);
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
        } catch (RuntimeException unavailable) {
            release(lease, "RG.MIRROR.RUN_UNAVAILABLE");
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_UNAVAILABLE",
                    "The isolated mirror runtime or evidence store is unavailable.");
        }
    }

    /** Reads one payload-free terminal run summary in the exact authenticated scope. */
    public MirrorRunSummary find(String runId, IntegrationRequestContext identity) {
        return MirrorRunSummary.from(requireEvidence(runId, identity));
    }

    /** Reads one independently verified payload-free evidence bundle in the exact scope. */
    public MirrorEvidenceBundle evidence(String runId, IntegrationRequestContext identity) {
        return requireEvidence(runId, identity);
    }

    private MirrorRunRequestRepository.Claim claim(
            MirrorRunRequestRepository.Registration registration,
            MirrorPlan plan,
            IntegrationRequestContext identity) {
        try {
            return requests.claim(registration, "mirror-attempt-" + UUID.randomUUID(),
                    plan.policy().timeout().plus(EVIDENCE_COMMIT_RESERVE));
        } catch (MirrorRunRequestConflictException conflict) {
            throw conflict(identity, "RG.MIRROR.RUN_IDEMPOTENCY_CONFLICT",
                    "The request id already identifies different immutable execution inputs.",
                    Map.of());
        } catch (RuntimeException unavailable) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_COORDINATION_UNAVAILABLE",
                    "The durable mirror request coordinator is unavailable.");
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
                || !state.registration().scope().equals(run.scope())) {
            throw serviceUnavailable(identity, "RG.MIRROR.RUN_EVIDENCE_INCONSISTENT",
                    "Completed mirror request evidence differs from its durable coordination state.");
        }
        return MirrorRunSummary.from(bundle);
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
                 "RG.MIRROR.RESOLUTION_EVIDENCE_REJECTED" ->
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
