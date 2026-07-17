package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestRecoveryDispatch;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated application boundary for renewing an issued durable-recovery fence.
 *
 * <p>The public request never carries the internal dispatch. The service resolves the unique
 * historical dispatch from the exact prior fence, verifies that the authenticated principal still
 * matches the authorization receipt, and delegates database-time live-fence rotation to the durable
 * repository. The authorized security decision and heartbeat commit in one local transaction.</p>
 */
public final class DurableTestRecoveryHeartbeatService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final Duration leaseDuration;

    /**
     * Creates a server-policy-owned recovery-heartbeat boundary.
     *
     * @param checkpoints verified dispatch lookup and live-fence command repository
     * @param securityEvents fail-closed semantic security-event sink
     * @param objectMapper canonical authorized-command fingerprint mapper
     * @param leaseDuration server-owned successor lease duration from one second through one hour
     */
    public DurableTestRecoveryHeartbeatService(
            DurableTestExecutionCheckpointRepository checkpoints,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            Duration leaseDuration) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(Duration.ofSeconds(1)) < 0
                || leaseDuration.compareTo(Duration.ofHours(1)) > 0
                || leaseDuration.getNano() != 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be whole seconds between one second and one hour");
        }
    }

    /**
     * Renews one exact issued dispatch under the same authenticated authority.
     *
     * @param runId path-bound durable run identity
     * @param request versioned source fence and caller idempotency key
     * @param identity verified integration workload identity
     * @return payload-free successor fence or exact idempotent replay
     */
    public DurableTestRecoveryHeartbeatResponse heartbeat(
            String runId,
            DurableTestRecoveryHeartbeatRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(runId, request, identity);
        String normalizedRunId = runId.trim();
        DurableTestRecoveryDispatch source = dispatch(
                normalizedRunId, request, identity);
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result =
                renewIssuedDispatch(source, request.clientRequestId(), identity);
        return DurableTestRecoveryHeartbeatResponse.from(result);
    }

    Duration leaseDuration() {
        return leaseDuration;
    }

    /**
     * Renews one already resolved issued dispatch for a server-owned worker session.
     *
     * <p>This package boundary deliberately reuses the public heartbeat's principal continuity,
     * canonical intent, transaction-bound audit, conflict mapping, and immutable replay semantics.
     * It omits only the redundant public fence lookup because the caller already resolved and
     * verified that exact dispatch.</p>
     */
    DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult renewIssuedDispatch(
            DurableTestRecoveryDispatch source,
            String clientRequestId,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        DurableTestRecoveryDispatch requiredSource = Objects.requireNonNull(source, "source");
        String requiredKey = normalized(clientRequestId);
        if (!IDENTIFIER.matcher(requiredKey).matches()) {
            throw new IllegalArgumentException(
                    "clientRequestId must be a bounded stable identifier");
        }
        requireDispatchScope(requiredSource, identity);
        requirePrincipal(requiredSource, requiredSource.runId(), identity);
        String requestFingerprint = requestFingerprint(
                requiredSource.runId(), requiredKey, requiredSource, identity);
        TestRuntimeTransactionMutation boundAudit = boundAllowedAudit(
                identity, requiredSource.runId(), requiredKey);
        DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand command =
                new DurableTestExecutionCheckpointRepository.RecoveryHeartbeatCommand(
                        requiredKey, requestFingerprint, requiredSource, leaseDuration);
        try {
            DurableTestExecutionCheckpointRepository.RecoveryHeartbeatResult result =
                    checkpoints.heartbeatRecoveryLeaseIdempotently(command, boundAudit);
            requireResultScope(result.checkpoint(), requiredSource.runId(), identity);
            if (result.idempotentReplay()) {
                appendReplayAudit(identity, requiredSource.runId(), requiredKey);
            }
            return result;
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw mapConflict(conflict.reason(), identity);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private DurableTestRecoveryDispatch dispatch(
            String runId,
            DurableTestRecoveryHeartbeatRequest request,
            IntegrationRequestContext identity) {
        DurableTestRecoveryHeartbeatRequest.Fence requested = request.expectedFence();
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
            throw notFound(identity);
        }
        return source;
    }

    private static boolean dispatchAgreesWithRequest(
            DurableTestRecoveryDispatch dispatch,
            String runId,
            DurableTestRecoveryHeartbeatRequest request,
            IntegrationRequestContext identity) {
        if (dispatch == null) {
            return false;
        }
        DurableTestRecoveryHeartbeatRequest.Fence fence = request.expectedFence();
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

    private void requirePrincipal(
            DurableTestRecoveryDispatch source,
            String runId,
            IntegrationRequestContext identity) {
        String actual = DurableTestRecoveryPrincipal.fingerprint(objectMapper, identity);
        if (!actual.equals(source.authorization().principalFingerprint())) {
            rejected(identity, runId, "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH");
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_RECOVERY_PRINCIPAL_MISMATCH",
                    "The authenticated workload does not own this recovery authorization.",
                    identity.correlationId(), Map.of()));
        }
    }

    private String requestFingerprint(
            String runId,
            String clientRequestId,
            DurableTestRecoveryDispatch source,
            IntegrationRequestContext identity) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableRecoveryHeartbeatAuthorizedIntent.v1"),
                Map.entry("runId", runId),
                Map.entry("clientRequestId", clientRequestId),
                Map.entry("sourceDispatchFingerprint", source.dispatchFingerprint()),
                Map.entry("principalFingerprint",
                        DurableTestRecoveryPrincipal.fingerprint(objectMapper, identity)),
                Map.entry("leaseDurationSeconds", leaseDuration.toSeconds())));
    }

    private static void requireDispatchScope(
            DurableTestRecoveryDispatch source,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint.Scope scope = source.scope();
        if (!identity.tenantId().equals(scope.tenantId())
                || !identity.organizationId().equals(scope.organizationId())
                || !identity.projectId().equals(scope.projectId())
                || !identity.environmentId().equals(scope.environmentId())) {
            throw notFound(identity);
        }
    }

    private TestRuntimeTransactionMutation boundAllowedAudit(
            IntegrationRequestContext identity, String runId, String clientRequestId) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(event(
                    identity, "ALLOWED", "RG.TEST.DURABLE_HEARTBEAT_AUTHORIZED",
                    runId, clientRequestId));
            if (mutation == null) {
                throw new IllegalStateException("Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable recovery heartbeat is unavailable because its security audit cannot commit.");
        }
    }

    private void appendReplayAudit(
            IntegrationRequestContext identity, String runId, String clientRequestId) {
        try {
            securityEvents.append(event(identity, "ALLOWED",
                    "RG.TEST.DURABLE_HEARTBEAT_IDEMPOTENT_REPLAY", runId, clientRequestId));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable recovery heartbeat is unavailable because its security audit cannot commit.");
        }
    }

    private void rejected(
            IntegrationRequestContext identity, String runId, String reasonCode) {
        try {
            securityEvents.append(event(identity, "REJECTED", reasonCode, runId, ""));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable recovery heartbeat is unavailable because its security audit cannot commit.");
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
                identity.environmentId(), identity.actorId(), "DURABLE_RECOVERY_HEARTBEAT",
                outcome, reasonCode, facts);
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
            DurableTestRecoveryHeartbeatRequest request,
            IntegrationRequestContext identity) {
        boolean valid = request != null
                && DurableTestRecoveryHeartbeatRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                && IDENTIFIER.matcher(normalized(runId)).matches()
                && IDENTIFIER.matcher(request.clientRequestId()).matches()
                && request.expectedFence() != null
                && IDENTIFIER.matcher(request.expectedFence().ownerId()).matches()
                && request.expectedFence().leaseEpoch() > 0
                && request.expectedFence().revision() >= 0
                && FINGERPRINT.matcher(request.expectedCheckpointFingerprint()).matches();
        if (!valid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_HEARTBEAT_REQUEST_INVALID",
                    "Recovery heartbeat requires a versioned idempotency key, exact fence, and checkpoint fingerprint.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireResultScope(
            DurableTestExecutionCheckpoint checkpoint,
            String runId,
            IntegrationRequestContext identity) {
        if (checkpoint == null
                || !runId.equals(checkpoint.runId())
                || !identity.tenantId().equals(checkpoint.scope().tenantId())
                || !identity.organizationId().equals(checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())
                || !identity.environmentId().equals(checkpoint.scope().environmentId())) {
            throw notFound(identity);
        }
    }

    private static IntegrationProblemException mapConflict(
            DurableTestExecutionCheckpointConflictException.Reason reason,
            IntegrationRequestContext identity) {
        return switch (reason) {
            case STALE_FENCE -> conflict(identity, "RG.TEST.DURABLE_STALE_FENCE",
                    "The durable execution fence changed after caller selection.", true);
            case LEASE_EXPIRED -> conflict(identity, "RG.TEST.DURABLE_LEASE_EXPIRED",
                    "The durable execution lease expired before the command completed.", true);
            case UNRECOGNIZED_DISPATCH -> conflict(identity,
                    "RG.TEST.DURABLE_UNRECOGNIZED_DISPATCH",
                    "The durable recovery dispatch has no committed issuance record.", false);
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.DURABLE_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies different authorized heartbeat intent.",
                    false);
            case LEASE_ACTIVE, NOT_RESUMABLE, DUPLICATE_IDENTITY,
                    REPLAY_WINDOW_EXPIRED, INVALID_TRANSITION ->
                    conflict(identity, "RG.TEST.DURABLE_HEARTBEAT_CONFLICT",
                            "The recovery heartbeat violates the current control state.", false);
        };
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
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
