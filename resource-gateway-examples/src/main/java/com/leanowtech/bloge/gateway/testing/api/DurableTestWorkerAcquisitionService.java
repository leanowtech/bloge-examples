package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated non-blocking pull scheduler for durable test recovery ownership.
 *
 * <p>The service scans only the verified tenant/organization/project/environment scope, evaluates a
 * server-bounded oldest-first candidate window, and freshly re-authorizes each candidate before an
 * exact database-time lease CAS. It never returns queue listings, fixture values, engine state, or
 * the internal recovery dispatch. A committed {@code NO_WORK} is intentionally immutable under its
 * idempotency key; callers use a new key for a later observation.</p>
 */
public final class DurableTestWorkerAcquisitionService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final DurableTestRecoveryAuthorizer authorizer;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final String ownerId;
    private final Duration leaseDuration;
    private final int candidateLimit;
    private final Duration initialCandidateBackoff;
    private final Duration maximumCandidateBackoff;

    /**
     * Creates a server-owned durable worker pull boundary.
     *
     * @param checkpoints integrity-verifying queue and acquisition authority
     * @param authorizer exact current dependency re-authorization service
     * @param securityEvents fail-closed semantic security-event sink
     * @param objectMapper canonical authenticated-intent mapper
     * @param ownerId server-owned recovery process identity
     * @param leaseDuration whole-second ownership lease between one second and one hour
     * @param candidateLimit oldest-first SQL candidate window between 1 and 1,000
     */
    public DurableTestWorkerAcquisitionService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            String ownerId,
            Duration leaseDuration,
            int candidateLimit) {
        this(checkpoints, authorizer, securityEvents, objectMapper, ownerId, leaseDuration,
                candidateLimit, Duration.ofSeconds(5), Duration.ofMinutes(5));
    }

    /**
     * Creates a worker pull boundary with bounded deterministic-candidate backoff policy.
     *
     * @param checkpoints integrity-verifying queue and acquisition authority
     * @param authorizer exact current dependency re-authorization service
     * @param securityEvents fail-closed semantic security-event sink
     * @param objectMapper canonical authenticated-intent mapper
     * @param ownerId server-owned recovery process identity
     * @param leaseDuration whole-second ownership lease between one second and one hour
     * @param candidateLimit cyclic SQL candidate window between 1 and 1,000
     * @param initialCandidateBackoff first deterministic-failure retry delay
     * @param maximumCandidateBackoff bounded exponential retry delay cap
     */
    public DurableTestWorkerAcquisitionService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            String ownerId,
            Duration leaseDuration,
            int candidateLimit,
            Duration initialCandidateBackoff,
            Duration maximumCandidateBackoff) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.ownerId = requiredIdentifier(ownerId, "ownerId");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0
                || leaseDuration.getNano() != 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be whole seconds between one second and one hour");
        }
        if (candidateLimit < 1 || candidateLimit > 1_000) {
            throw new IllegalArgumentException("candidateLimit must be between 1 and 1000");
        }
        this.candidateLimit = candidateLimit;
        this.initialCandidateBackoff = boundedBackoff(
                initialCandidateBackoff, "initialCandidateBackoff");
        this.maximumCandidateBackoff = boundedBackoff(
                maximumCandidateBackoff, "maximumCandidateBackoff");
        if (this.maximumCandidateBackoff.compareTo(this.initialCandidateBackoff) < 0) {
            throw new IllegalArgumentException(
                    "maximumCandidateBackoff must not be shorter than initialCandidateBackoff");
        }
    }

    /**
     * Returns one exact authorized assignment or an immutable bounded no-work observation.
     *
     * @param request caller-stable pull command
     * @param identity freshly verified non-production workload identity
     * @return payload-free worker acquisition result
     */
    public DurableTestWorkerAcquisitionResponse acquire(
            DurableTestWorkerAcquisitionRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(request, identity);
        var scope = new DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope(
                identity.tenantId(), identity.organizationId(), identity.projectId(),
                identity.environmentId());
        String requestFingerprint = requestFingerprint(request, identity);
        var command = new DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand(
                request.clientRequestId(), requestFingerprint, scope);

        Optional<DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult> prior =
                findPrior(command, identity);
        if (prior.isPresent()) {
            requireResultScope(prior.get(), scope, identity);
            appendReplayAudit(identity, request.clientRequestId(), prior.get());
            return DurableTestWorkerAcquisitionResponse.from(prior.get());
        }

        List<DurableTestExecutionCheckpointRepository.RecoveryCandidate> candidates =
                candidates(scope, identity);
        int examined = 0;
        int ineligible = 0;
        int deferred = 0;
        List<DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral> deferrals =
                new ArrayList<>();
        Optional<DurableTestExecutionCheckpointRepository.WorkerScanProgress> scanProgress =
                Optional.empty();
        for (DurableTestExecutionCheckpointRepository.RecoveryCandidate queued : candidates) {
            DurableTestExecutionCheckpoint candidate = queued.checkpoint();
            examined++;
            if (!scope.contains(candidate)) {
                throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                        "The isolated durable test control store returned an invalid scope.");
            }
            if (queued.progress() == null) {
                throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                        "The isolated durable test control store returned invalid scan progress.");
            }
            if (queued.activeDeferral() == null) {
                throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                        "The isolated durable test control store returned invalid backoff state.");
            }
            scanProgress = Optional.of(queued.progress());
            if (queued.activeDeferral().isPresent()) {
                ineligible++;
                deferred++;
                continue;
            }
            if (!DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(candidate.schemaVersion())
                    || candidate.dependencies().target() == null) {
                ineligible++;
                deferrals.add(deferral(queued,
                        DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason
                                .LEGACY_PROTOCOL));
                continue;
            }
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized;
            try {
                authorized = authorizer.authorize(candidate, identity);
            } catch (IntegrationProblemException unavailable) {
                if (unavailable.problem().status() == 403
                        || unavailable.problem().status() == 409) {
                    ineligible++;
                    deferrals.add(deferral(queued,
                            unavailable.problem().status() == 403
                                    ? DurableTestExecutionCheckpointRepository
                                    .WorkerCandidateDeferralReason.AUTHORIZATION_DENIED
                                    : DurableTestExecutionCheckpointRepository
                                    .WorkerCandidateDeferralReason.AUTHORIZATION_CONFLICT));
                    continue;
                }
                throw unavailable;
            } catch (RuntimeException unavailable) {
                throw unavailable(identity, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE",
                        "Durable recovery dependencies cannot currently be authorized.");
            }

            var selection = selection(candidate, authorized);
            TestRuntimeTransactionMutation audit = boundAudit(
                    identity, request.clientRequestId(), "ACQUIRED", candidate.runId(),
                    examined, ineligible, deferred, false);
            try {
                var result = checkpoints.acquireWorkerCommandIdempotently(
                        command, Optional.of(selection), scanProgress,
                        List.copyOf(deferrals), audit);
                requireResultScope(result, scope, identity);
                if (result.idempotentReplay()) {
                    appendReplayAudit(identity, request.clientRequestId(), result);
                }
                return DurableTestWorkerAcquisitionResponse.from(result);
            } catch (DurableTestExecutionCheckpointConflictException conflict) {
                if (conflict.reason()
                        == DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT) {
                    Optional<DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult>
                            winner = findPrior(command, identity);
                    if (winner.isPresent()) {
                        requireResultScope(winner.get(), scope, identity);
                        appendReplayAudit(identity, request.clientRequestId(), winner.get());
                        return DurableTestWorkerAcquisitionResponse.from(winner.get());
                    }
                    throw mapConflict(conflict.reason(), identity);
                }
                if (Set.of(
                        DurableTestExecutionCheckpointConflictException.Reason.STALE_FENCE,
                        DurableTestExecutionCheckpointConflictException.Reason.LEASE_ACTIVE,
                        DurableTestExecutionCheckpointConflictException.Reason.NOT_RESUMABLE)
                        .contains(conflict.reason())) {
                    ineligible++;
                    continue;
                }
                throw mapConflict(conflict.reason(), identity);
            } catch (IntegrationProblemException expected) {
                throw expected;
            } catch (RuntimeException unavailable) {
                throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                        "The isolated durable test control store is unavailable.");
            }
        }

        TestRuntimeTransactionMutation audit = boundAudit(
                identity, request.clientRequestId(), "NO_WORK", "", examined,
                ineligible, deferred, false);
        try {
            var result = checkpoints.acquireWorkerCommandIdempotently(
                    command, Optional.empty(), scanProgress, List.copyOf(deferrals), audit);
            if (result.idempotentReplay()) {
                appendReplayAudit(identity, request.clientRequestId(), result);
            }
            return DurableTestWorkerAcquisitionResponse.from(result);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            if (conflict.reason()
                    == DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT) {
                Optional<DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult> winner =
                        findPrior(command, identity);
                if (winner.isPresent()) {
                    requireResultScope(winner.get(), scope, identity);
                    appendReplayAudit(identity, request.clientRequestId(), winner.get());
                    return DurableTestWorkerAcquisitionResponse.from(winner.get());
                }
            }
            throw mapConflict(conflict.reason(), identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private List<DurableTestExecutionCheckpointRepository.RecoveryCandidate> candidates(
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope scope,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.findExpiredRecoveryCandidates(
                    new DurableTestExecutionCheckpointRepository.RecoveryCandidateQuery(
                            scope, candidateLimit)).candidates();
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private Optional<DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult> findPrior(
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionCommand command,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.findWorkerAcquisitionResult(
                    command.scope(), command.clientRequestId(), command.requestFingerprint());
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw mapConflict(conflict.reason(), identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private DurableTestExecutionCheckpointRepository.WorkerAcquisitionSelection selection(
            DurableTestExecutionCheckpoint candidate,
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized) {
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = candidate.lifecycle();
        return new DurableTestExecutionCheckpointRepository.WorkerAcquisitionSelection(
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        candidate.scope().tenantId(), candidate.scope().environmentId(),
                        candidate.runId(), new DurableTestExecutionCheckpointRepository.Fence(
                        lifecycle.ownerId(), lifecycle.leaseEpoch(), lifecycle.revision()),
                        candidate.checkpointFingerprint(), ownerId, leaseDuration),
                authorized.authorization());
    }

    private DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral deferral(
            DurableTestExecutionCheckpointRepository.RecoveryCandidate candidate,
            DurableTestExecutionCheckpointRepository.WorkerCandidateDeferralReason reason) {
        return new DurableTestExecutionCheckpointRepository.WorkerCandidateDeferral(
                candidate.progress(), candidate.checkpoint().checkpointFingerprint(), reason,
                initialCandidateBackoff, maximumCandidateBackoff);
    }

    private String requestFingerprint(
            DurableTestWorkerAcquisitionRequest request,
            IntegrationRequestContext identity) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableWorkerAcquisitionAuthorizedIntent.v1"),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("tenantId", identity.tenantId()),
                Map.entry("organizationId", identity.organizationId()),
                Map.entry("projectId", identity.projectId()),
                Map.entry("environmentId", identity.environmentId()),
                Map.entry("region", identity.region()),
                Map.entry("actorType", identity.actorType()),
                Map.entry("actorId", identity.actorId()),
                Map.entry("delegatedBy", identity.delegatedBy()),
                Map.entry("delegationGrantId", identity.delegationGrantId()),
                Map.entry("purpose", identity.purpose()),
                Map.entry("clearance", identity.clearance()),
                Map.entry("groups", identity.groups().stream().sorted().toList())));
    }

    private TestRuntimeTransactionMutation boundAudit(
            IntegrationRequestContext identity,
            String clientRequestId,
            String outcome,
            String runId,
            int examined,
            int ineligible,
            int deferred,
            boolean replay) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(event(
                    identity, clientRequestId, outcome, runId, examined, ineligible,
                    deferred, replay));
            if (mutation == null) {
                throw new IllegalStateException("Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable worker acquisition is unavailable because its audit cannot commit.");
        }
    }

    private void appendReplayAudit(
            IntegrationRequestContext identity,
            String clientRequestId,
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult result) {
        try {
            securityEvents.append(event(identity, clientRequestId, result.outcome().name(),
                    result.checkpoint() == null ? "" : result.checkpoint().runId(),
                    0, 0, 0, true));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable worker acquisition is unavailable because its audit cannot commit.");
        }
    }

    private static TestSecurityEvent event(
            IntegrationRequestContext identity,
            String clientRequestId,
            String outcome,
            String runId,
            int examined,
            int ineligible,
            int deferred,
            boolean replay) {
        Map<String, Object> facts = runId.isBlank()
                ? Map.of("clientRequestId", clientRequestId, "result", outcome,
                "examinedCandidateCount", examined, "ineligibleCandidateCount", ineligible,
                "deferredCandidateCount", deferred, "idempotentReplay", replay)
                : Map.of("clientRequestId", clientRequestId, "result", outcome,
                "runId", runId, "examinedCandidateCount", examined,
                "ineligibleCandidateCount", ineligible, "deferredCandidateCount", deferred,
                "idempotentReplay", replay);
        return new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                identity.tenantId(), identity.environmentId(), identity.actorId(),
                "DURABLE_WORKER_ACQUISITION", "ALLOWED",
                replay ? "RG.TEST.DURABLE_WORKER_ACQUISITION_IDEMPOTENT_REPLAY"
                        : "RG.TEST.DURABLE_WORKER_ACQUISITION_AUTHORIZED",
                facts);
    }

    private static void requireResultScope(
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionResult result,
            DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope scope,
            IntegrationRequestContext identity) {
        if (result.checkpoint() != null && !scope.contains(result.checkpoint())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.DURABLE_EXECUTION_NOT_FOUND",
                    "Durable test execution was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN",
                    "Durable worker acquisition is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
        if (identity.projectId().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.CONTEXT_REQUIRED",
                    "A verified project scope is required for durable worker acquisition.",
                    identity.correlationId(), Map.of("projectId", "required")));
        }
    }

    private static void validateRequest(
            DurableTestWorkerAcquisitionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !DurableTestWorkerAcquisitionRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !IDENTIFIER.matcher(request.clientRequestId()).matches()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_WORKER_ACQUISITION_REQUEST_INVALID",
                    "Worker acquisition requires a versioned caller-stable idempotency key.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException mapConflict(
            DurableTestExecutionCheckpointConflictException.Reason reason,
            IntegrationRequestContext identity) {
        return switch (reason) {
            case IDEMPOTENCY_CONFLICT -> new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.TEST.DURABLE_IDEMPOTENCY_CONFLICT",
                            "clientRequestId already identifies different worker acquisition intent.",
                            identity.correlationId(), Map.of()));
            case STALE_FENCE, LEASE_ACTIVE, LEASE_EXPIRED -> new IntegrationProblemException(
                    IntegrationProblem.retryableConflict(
                            "RG.TEST.DURABLE_WORKER_ACQUISITION_RACE",
                            "The selected worker assignment changed concurrently.",
                            identity.correlationId(), Map.of()));
            case NOT_RESUMABLE, UNRECOGNIZED_DISPATCH, DUPLICATE_IDENTITY, INVALID_TRANSITION ->
                    new IntegrationProblemException(IntegrationProblem.conflict(
                            "RG.TEST.DURABLE_WORKER_ACQUISITION_CONFLICT",
                            "The worker acquisition violates the current durable control state.",
                            identity.correlationId(), Map.of()));
        };
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String requiredIdentifier(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return normalized;
    }

    private static Duration boundedBackoff(Duration value, String field) {
        Duration result = Objects.requireNonNull(value, field);
        if (result.compareTo(Duration.ofSeconds(1)) < 0
                || result.compareTo(Duration.ofHours(24)) > 0
                || result.getNano() != 0) {
            throw new IllegalArgumentException(
                    field + " must be whole seconds between one second and 24 hours");
        }
        return result;
    }
}
