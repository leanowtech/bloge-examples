package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.DurableTestTerminalRecoveryRuntime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated application boundary for one server-owned terminal durable recovery.
 *
 * <p>The public request is an intent, not an engine-state command. This service resolves the
 * previously issued dispatch, proves that its principal and freshly reconstructed authorization
 * still agree, executes one bounded signal in an isolated staged engine, and atomically commits
 * the resulting BLOGE mutation, terminal control checkpoint, promotion-blocking receipt, and
 * semantic audit event. Signal values are never copied into audit or response material.</p>
 */
public final class DurableTestTerminalRecoveryService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final int MAX_SIGNAL_BYTES = 256 * 1024;
    private static final List<String> EVIDENCE_GAPS = List.of(
            "PRE_CHECKPOINT_TRACE_UNAVAILABLE", "RECOVERY_SIGNAL_PAYLOAD_OMITTED");

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final DurableTestRecoveryAuthorizer authorizer;
    private final DurableTestTerminalRecoveryRuntime runtime;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;

    /**
     * Creates a fail-closed terminal recovery boundary.
     *
     * @param checkpoints verified dispatch, live checkpoint, and atomic terminal repository
     * @param authorizer current exact dependency and identity re-authorization boundary
     * @param runtime isolated staged BLOGE recovery runtime
     * @param securityEvents transaction-capable semantic security-event sink
     * @param objectMapper canonical protocol and signal mapper
     */
    public DurableTestTerminalRecoveryService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            DurableTestTerminalRecoveryRuntime runtime,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    /**
     * Executes one signal and commits only a server-derived terminal state.
     *
     * <p>A response-loss retry is resolved before dispatch lookup, dependency reconstruction, or
     * engine execution. A concurrent winner is resolved the same way after an idempotency
     * conflict.</p>
     *
     * @param runId path-bound durable run identity
     * @param request exact source fence, checkpoint identity, idempotency key, and signal
     * @param identity freshly authenticated workload authority
     * @return payload-free committed terminal result or its exact idempotent replay
     */
    public DurableTestTerminalRecoveryResponse recover(
            String runId,
            DurableTestTerminalRecoveryRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(runId, request, identity);
        String normalizedRunId = runId.trim();
        String signalFingerprint = signalFingerprint(request.signal().data(), identity);
        String requestFingerprint = requestFingerprint(
                normalizedRunId, request, signalFingerprint, identity);

        Optional<DurableTestExecutionCheckpointRepository.RecoveryTerminalResult> prior =
                findPrior(request.clientRequestId(), requestFingerprint, identity);
        if (prior.isPresent()) {
            return replay(prior.get(), normalizedRunId, request.clientRequestId(), identity);
        }

        DurableTestRecoveryDispatch dispatch = dispatch(
                normalizedRunId, request, identity);
        requirePrincipal(dispatch, normalizedRunId, identity);
        DurableTestExecutionCheckpoint current = scopedCheckpoint(
                normalizedRunId, dispatch, identity);
        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized =
                authorize(current, normalizedRunId, identity);
        requireAuthorizationContinuity(dispatch, authorized, normalizedRunId, identity);
        TestRuntimeTransactionMutation boundAudit = boundAllowedAudit(
                identity, normalizedRunId, request.clientRequestId());

        Object signal = signalValue(request.signal().data(), identity);
        String checkpointRef = "terminal:" + requestFingerprint.substring("sha256:".length());
        try (DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery prepared =
                     runtime.prepare(current, authorized, request.signal().nodeId(), signal,
                             checkpointRef)) {
            DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command =
                    new DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand(
                            request.clientRequestId(), requestFingerprint, dispatch,
                            prepared.executionOutcome(),
                            prepared.engineStateMutation().engineState(),
                            prepared.fixtureConsumptionState(),
                            prepared.executionServiceState(), EVIDENCE_GAPS);
            return commit(command, prepared, normalizedRunId, request.clientRequestId(),
                    requestFingerprint, boundAudit, identity);
        } catch (DurableTestTerminalRecoveryRuntime.NonTerminalBoundaryException nonTerminal) {
            rejected(identity, normalizedRunId,
                    "RG.TEST.DURABLE_RECOVERY_NOT_TERMINAL");
            throw conflict(identity, "RG.TEST.DURABLE_RECOVERY_NOT_TERMINAL",
                    "The recovery signal reached another suspension instead of a terminal state.",
                    false);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException executionFailure) {
            rejected(identity, normalizedRunId,
                    "RG.TEST.DURABLE_RECOVERY_EXECUTION_FAILED");
            throw conflict(identity, "RG.TEST.DURABLE_RECOVERY_EXECUTION_FAILED",
                    "The isolated durable recovery did not produce a committable terminal state.",
                    true);
        }
    }

    private DurableTestTerminalRecoveryResponse commit(
            DurableTestExecutionCheckpointRepository.RecoveryTerminalCommand command,
            DurableTestTerminalRecoveryRuntime.PreparedTerminalRecovery prepared,
            String runId,
            String clientRequestId,
            String requestFingerprint,
            TestRuntimeTransactionMutation boundAudit,
            IntegrationRequestContext identity) {
        try {
            DurableTestExecutionCheckpointRepository.RecoveryTerminalResult result =
                    checkpoints.terminalizeRecoveryIdempotently(
                            command, prepared.engineStateMutation(), boundAudit);
            requireResultScope(result, runId, identity);
            if (result.idempotentReplay()) {
                appendReplayAudit(identity, runId, clientRequestId);
            }
            return DurableTestTerminalRecoveryResponse.from(result);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            if (conflict.reason()
                    == DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT) {
                Optional<DurableTestExecutionCheckpointRepository.RecoveryTerminalResult> winner =
                        findPrior(clientRequestId, requestFingerprint, identity);
                if (winner.isPresent()) {
                    return replay(winner.get(), runId, clientRequestId, identity);
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

    private DurableTestTerminalRecoveryResponse replay(
            DurableTestExecutionCheckpointRepository.RecoveryTerminalResult result,
            String runId,
            String clientRequestId,
            IntegrationRequestContext identity) {
        requireResultScope(result, runId, identity);
        appendReplayAudit(identity, runId, clientRequestId);
        return DurableTestTerminalRecoveryResponse.from(result);
    }

    private Optional<DurableTestExecutionCheckpointRepository.RecoveryTerminalResult> findPrior(
            String clientRequestId,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.findRecoveryTerminalResult(
                    identity.tenantId(), identity.environmentId(),
                    clientRequestId, requestFingerprint);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw mapConflict(conflict.reason(), identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private DurableTestRecoveryDispatch dispatch(
            String runId,
            DurableTestTerminalRecoveryRequest request,
            IntegrationRequestContext identity) {
        DurableTestTerminalRecoveryRequest.Fence requested = request.expectedFence();
        Optional<DurableTestRecoveryDispatch> resolved;
        try {
            resolved = checkpoints.findRecoveryDispatch(
                    identity.tenantId(), identity.environmentId(), runId,
                    new DurableTestExecutionCheckpointRepository.Fence(
                            requested.ownerId(), requested.leaseEpoch(), requested.revision()),
                    request.expectedCheckpointFingerprint());
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
        DurableTestRecoveryDispatch source = resolved.orElse(null);
        if (!dispatchAgreesWithRequest(source, runId, request, identity)) {
            throw dispatchNotFound(identity);
        }
        return source;
    }

    private static boolean dispatchAgreesWithRequest(
            DurableTestRecoveryDispatch dispatch,
            String runId,
            DurableTestTerminalRecoveryRequest request,
            IntegrationRequestContext identity) {
        if (dispatch == null) {
            return false;
        }
        DurableTestTerminalRecoveryRequest.Fence fence = request.expectedFence();
        DurableTestExecutionCheckpoint.Scope scope = dispatch.scope();
        return identity.tenantId().equals(scope.tenantId())
                && identity.organizationId().equals(scope.organizationId())
                && identity.projectId().equals(scope.projectId())
                && identity.environmentId().equals(scope.environmentId())
                && runId.equals(dispatch.runId())
                && fence.ownerId().equals(dispatch.ownerId())
                && fence.leaseEpoch() == dispatch.leaseEpoch()
                && fence.revision() == dispatch.revision()
                && request.expectedCheckpointFingerprint().equals(
                dispatch.checkpointFingerprint());
    }

    private DurableTestExecutionCheckpoint scopedCheckpoint(
            String runId,
            DurableTestRecoveryDispatch dispatch,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = checkpoints.find(identity.tenantId(), identity.environmentId(), runId)
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
        if (checkpoint == null
                || !identity.organizationId().equals(checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.DURABLE_EXECUTION_NOT_FOUND",
                    "Durable test execution was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
        if (checkpoint.lifecycle().status()
                != DurableTestExecutionCheckpoint.Status.RESUMING
                || !dispatch.agreesWith(checkpoint)) {
            throw conflict(identity, "RG.TEST.DURABLE_STALE_FENCE",
                    "The durable recovery dispatch no longer matches the live checkpoint.", true);
        }
        return checkpoint;
    }

    private DurableTestRecoveryAuthorizer.AuthorizedRecovery authorize(
            DurableTestExecutionCheckpoint current,
            String runId,
            IntegrationRequestContext identity) {
        try {
            return authorizer.authorize(current, identity);
        } catch (IntegrationProblemException rejected) {
            rejected(identity, runId, rejected.problem().code());
            throw rejected;
        } catch (RuntimeException unavailable) {
            rejected(identity, runId, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE");
            throw unavailable(identity, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE",
                    "Durable recovery dependencies cannot currently be authorized.");
        }
    }

    private void requirePrincipal(
            DurableTestRecoveryDispatch dispatch,
            String runId,
            IntegrationRequestContext identity) {
        String currentPrincipal = DurableTestRecoveryPrincipal.fingerprint(
                objectMapper, identity);
        if (!currentPrincipal.equals(
                dispatch.authorization().principalFingerprint())) {
            rejected(identity, runId,
                    "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH",
                    "The authenticated workload does not own this recovery authorization.",
                    identity.correlationId(), Map.of()));
        }
    }

    private void requireAuthorizationContinuity(
            DurableTestRecoveryDispatch dispatch,
            DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized,
            String runId,
            IntegrationRequestContext identity) {
        if (!dispatch.authorization().equals(authorized.authorization())) {
            rejected(identity, runId,
                    "RG.TEST.DURABLE_RECOVERY_AUTHORIZATION_DRIFT");
            throw conflict(identity, "RG.TEST.DURABLE_RECOVERY_AUTHORIZATION_DRIFT",
                    "Current recovery authorization differs from the issued worker dispatch.",
                    false);
        }
    }

    private String signalFingerprint(JsonNode signal, IntegrationRequestContext identity) {
        try {
            return ProtocolFingerprint.ofBounded(
                    objectMapper, signal, MAX_SIGNAL_BYTES);
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_RECOVERY_SIGNAL_TOO_LARGE",
                    "Recovery signal must be canonical JSON no larger than 256 KiB.",
                    identity.correlationId(), Map.of()));
        }
    }

    private Object signalValue(JsonNode signal, IntegrationRequestContext identity) {
        try {
            return signal.isNull() ? null : objectMapper.convertValue(signal, Object.class);
        } catch (IllegalArgumentException invalid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_RECOVERY_SIGNAL_INVALID",
                    "Recovery signal cannot be converted to an isolated engine value.",
                    identity.correlationId(), Map.of()));
        }
    }

    private String requestFingerprint(
            String runId,
            DurableTestTerminalRecoveryRequest request,
            String signalFingerprint,
            IntegrationRequestContext identity) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.durableTerminalRecoveryAuthorizedIntent.v1"),
                Map.entry("runId", runId),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("expectedOwnerId", request.expectedFence().ownerId()),
                Map.entry("expectedLeaseEpoch", request.expectedFence().leaseEpoch()),
                Map.entry("expectedRevision", request.expectedFence().revision()),
                Map.entry("expectedCheckpointFingerprint",
                        request.expectedCheckpointFingerprint()),
                Map.entry("signalNodeId", request.signal().nodeId()),
                Map.entry("signalFingerprint", signalFingerprint),
                Map.entry("principalFingerprint",
                        DurableTestRecoveryPrincipal.fingerprint(objectMapper, identity))));
    }

    private TestRuntimeTransactionMutation boundAllowedAudit(
            IntegrationRequestContext identity,
            String runId,
            String clientRequestId) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(event(
                    identity, "ALLOWED", "RG.TEST.DURABLE_TERMINAL_RECOVERY_COMMITTED",
                    runId, clientRequestId));
            if (mutation == null) {
                throw new IllegalStateException(
                        "Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable terminal recovery is unavailable because its security audit cannot commit.");
        }
    }

    private void appendReplayAudit(
            IntegrationRequestContext identity,
            String runId,
            String clientRequestId) {
        try {
            securityEvents.append(event(identity, "ALLOWED",
                    "RG.TEST.DURABLE_TERMINAL_RECOVERY_IDEMPOTENT_REPLAY",
                    runId, clientRequestId));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable terminal recovery is unavailable because its security audit cannot commit.");
        }
    }

    private void rejected(
            IntegrationRequestContext identity, String runId, String reasonCode) {
        try {
            securityEvents.append(event(identity, "REJECTED", reasonCode, runId, ""));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable terminal recovery is unavailable because its security audit cannot commit.");
        }
    }

    private static TestSecurityEvent event(
            IntegrationRequestContext identity,
            String outcome,
            String reasonCode,
            String runId,
            String clientRequestId) {
        Map<String, Object> facts = clientRequestId.isBlank()
                ? Map.of("runId", runId)
                : Map.of("runId", runId, "clientRequestId", clientRequestId);
        return new TestSecurityEvent(
                0, Instant.now(), identity.correlationId(), identity.tenantId(),
                identity.environmentId(), identity.actorId(),
                "DURABLE_TERMINAL_RECOVERY", outcome, reasonCode, facts);
    }

    private void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        String environment = identity.environmentId().toLowerCase(Locale.ROOT);
        if (!ENABLED_ENVIRONMENTS.contains(environment)) {
            rejected(identity, "", "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN",
                    "Durable test recovery is restricted to test and staging identities.",
                    identity.correlationId(), Map.of()));
        }
        if (identity.projectId().isBlank()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.INTEGRATION.CONTEXT_REQUIRED",
                    "A verified project scope is required for durable test recovery.",
                    identity.correlationId(), Map.of("projectId", "required")));
        }
    }

    private static void validateRequest(
            String runId,
            DurableTestTerminalRecoveryRequest request,
            IntegrationRequestContext identity) {
        boolean valid = request != null
                && DurableTestTerminalRecoveryRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                && IDENTIFIER.matcher(normalized(runId)).matches()
                && IDENTIFIER.matcher(request.clientRequestId()).matches()
                && request.expectedFence() != null
                && IDENTIFIER.matcher(request.expectedFence().ownerId()).matches()
                && request.expectedFence().leaseEpoch() > 0
                && request.expectedFence().revision() >= 0
                && FINGERPRINT.matcher(
                request.expectedCheckpointFingerprint()).matches()
                && request.signal() != null
                && IDENTIFIER.matcher(request.signal().nodeId()).matches();
        if (!valid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_TERMINAL_RECOVERY_REQUEST_INVALID",
                    "Terminal recovery requires a versioned idempotency key, exact fence, checkpoint fingerprint, and signal node.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireResultScope(
            DurableTestExecutionCheckpointRepository.RecoveryTerminalResult result,
            String runId,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint checkpoint = result == null
                ? null : result.checkpoint();
        if (checkpoint == null
                || !runId.equals(checkpoint.runId())
                || !identity.tenantId().equals(checkpoint.scope().tenantId())
                || !identity.organizationId().equals(
                checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())
                || !identity.environmentId().equals(
                checkpoint.scope().environmentId())) {
            throw dispatchNotFound(identity);
        }
    }

    private static IntegrationProblemException mapConflict(
            DurableTestExecutionCheckpointConflictException.Reason reason,
            IntegrationRequestContext identity) {
        return switch (reason) {
            case STALE_FENCE -> conflict(identity, "RG.TEST.DURABLE_STALE_FENCE",
                    "The durable execution fence changed after caller selection.", true);
            case LEASE_EXPIRED -> conflict(identity, "RG.TEST.DURABLE_LEASE_EXPIRED",
                    "The durable execution lease expired before terminal commit.", true);
            case UNRECOGNIZED_DISPATCH -> conflict(identity,
                    "RG.TEST.DURABLE_UNRECOGNIZED_DISPATCH",
                    "The durable recovery dispatch has no committed issuance record.", false);
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.DURABLE_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies different authorized terminal intent.",
                    false);
            case LEASE_ACTIVE, NOT_RESUMABLE, DUPLICATE_IDENTITY, INVALID_TRANSITION ->
                    conflict(identity, "RG.TEST.DURABLE_TERMINAL_RECOVERY_CONFLICT",
                            "The terminal recovery violates the current control state.", false);
        };
    }

    private static IntegrationProblemException dispatchNotFound(
            IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.TEST.DURABLE_RECOVERY_DISPATCH_NOT_FOUND",
                "Durable recovery dispatch was not found in the authorized scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String code,
            String title,
            boolean retryable) {
        IntegrationProblem problem = retryable
                ? IntegrationProblem.retryableConflict(
                code, title, identity.correlationId(), Map.of())
                : IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of());
        return new IntegrationProblemException(problem);
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
