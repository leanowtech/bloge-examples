package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
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
 * Authenticated control service for taking ownership of expired durable test executions.
 *
 * <p>This service does not resume BLOGE. It establishes the narrower, auditable precondition for a
 * future recovery worker: exact scope, dependency re-authorization, one server-owned lease claim,
 * and an immutable idempotent result. Fresh lease mutation and the semantic security event commit
 * in one test-runtime transaction. A lost-response retry returns its original result before mutable
 * dependencies are re-evaluated.</p>
 */
public final class DurableTestOwnerClaimService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final DurableTestExecutionCheckpointRepository checkpoints;
    private final DurableTestRecoveryAuthorizer authorizer;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final String ownerId;
    private final Duration leaseDuration;

    /**
     * Creates the durable owner-claim boundary.
     *
     * @param checkpoints integrity-verifying checkpoint and command repository
     * @param authorizer exact current dependency re-authorization service
     * @param securityEvents fail-closed semantic security-event sink
     * @param objectMapper canonical command fingerprint mapper
     * @param ownerId server-owned recovery process identity
     * @param leaseDuration server-owned lease duration between one second and one hour
     */
    public DurableTestOwnerClaimService(
            DurableTestExecutionCheckpointRepository checkpoints,
            DurableTestRecoveryAuthorizer authorizer,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            String ownerId,
            Duration leaseDuration) {
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
    }

    /**
     * Re-authorizes and claims one exact expired checkpoint without starting execution.
     *
     * @param runId path-bound durable run identity
     * @param request versioned caller intent and prior fence
     * @param identity verified integration workload identity
     * @return payload-free immutable claim result
     */
    public DurableTestOwnerClaimResponse claim(
            String runId,
            DurableTestOwnerClaimRequest request,
            IntegrationRequestContext identity) {
        requireIdentity(identity);
        validateRequest(runId, request, identity);
        String normalizedRunId = runId.trim();
        DurableTestExecutionCheckpoint current = scopedCheckpoint(normalizedRunId, identity);
        String requestFingerprint = requestFingerprint(normalizedRunId, request, identity);

        Optional<DurableTestExecutionCheckpointRepository.LeaseClaimResult> prior =
                findPrior(request, requestFingerprint, identity);
        if (prior.isPresent()) {
            requireResultScope(prior.get().checkpoint(), identity);
            appendReplayAudit(identity, normalizedRunId, request.clientRequestId());
            return DurableTestOwnerClaimResponse.from(prior.get());
        }
        if (!DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(current.schemaVersion())
                || current.dependencies().target() == null) {
            rejected(identity, normalizedRunId, "RG.TEST.DURABLE_CHECKPOINT_MIGRATION_REQUIRED");
            throw conflict(identity, "RG.TEST.DURABLE_CHECKPOINT_MIGRATION_REQUIRED",
                    "Legacy durable checkpoints require an explicit v2 migration before owner claim.",
                    false, Map.of());
        }
        requireExpectedState(current, request, identity);
        try {
            authorizer.authorize(current, identity);
        } catch (IntegrationProblemException rejected) {
            rejected(identity, normalizedRunId, rejected.problem().code());
            throw rejected;
        } catch (RuntimeException unavailable) {
            rejected(identity, normalizedRunId, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE");
            throw unavailable(identity, "RG.TEST.DURABLE_AUTHORIZATION_UNAVAILABLE",
                    "Durable recovery dependencies cannot currently be authorized.");
        }

        DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command = command(
                normalizedRunId, request, requestFingerprint, identity);
        TestRuntimeTransactionMutation boundAudit = boundAllowedAudit(
                identity, normalizedRunId, request.clientRequestId(), false);
        try {
            DurableTestExecutionCheckpointRepository.LeaseClaimResult result =
                    checkpoints.claimExpiredLeaseIdempotently(command, boundAudit);
            requireResultScope(result.checkpoint(), identity);
            if (result.idempotentReplay()) {
                appendReplayAudit(identity, normalizedRunId, request.clientRequestId());
            }
            return DurableTestOwnerClaimResponse.from(result);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            if (conflict.reason()
                    == DurableTestExecutionCheckpointConflictException.Reason.IDEMPOTENCY_CONFLICT) {
                Optional<DurableTestExecutionCheckpointRepository.LeaseClaimResult> winner =
                        findPrior(request, requestFingerprint, identity);
                if (winner.isPresent()) {
                    requireResultScope(winner.get().checkpoint(), identity);
                    appendReplayAudit(identity, normalizedRunId, request.clientRequestId());
                    return DurableTestOwnerClaimResponse.from(winner.get());
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

    private DurableTestExecutionCheckpoint scopedCheckpoint(
            String runId, IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = checkpoints.find(identity.tenantId(), identity.environmentId(), runId)
                    .orElse(null);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
        if (checkpoint == null || !identity.organizationId().equals(
                checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.DURABLE_EXECUTION_NOT_FOUND",
                    "Durable test execution was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
        return checkpoint;
    }

    private Optional<DurableTestExecutionCheckpointRepository.LeaseClaimResult> findPrior(
            DurableTestOwnerClaimRequest request,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        try {
            return checkpoints.findLeaseClaimResult(identity.tenantId(), identity.environmentId(),
                    request.clientRequestId(), requestFingerprint);
        } catch (DurableTestExecutionCheckpointConflictException conflict) {
            throw mapConflict(conflict.reason(), identity);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                    "The isolated durable test control store is unavailable.");
        }
    }

    private void requireExpectedState(
            DurableTestExecutionCheckpoint current,
            DurableTestOwnerClaimRequest request,
            IntegrationRequestContext identity) {
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = current.lifecycle();
        DurableTestOwnerClaimRequest.Fence expected = request.expectedFence();
        if (!expected.ownerId().equals(lifecycle.ownerId())
                || expected.leaseEpoch() != lifecycle.leaseEpoch()
                || expected.revision() != lifecycle.revision()
                || !request.expectedCheckpointFingerprint().equals(
                current.checkpointFingerprint())) {
            throw conflict(identity, "RG.TEST.DURABLE_STALE_FENCE",
                    "The durable execution fence changed after caller selection.", true, Map.of());
        }
    }

    private DurableTestExecutionCheckpointRepository.ResumeLeaseCommand command(
            String runId,
            DurableTestOwnerClaimRequest request,
            String requestFingerprint,
            IntegrationRequestContext identity) {
        DurableTestOwnerClaimRequest.Fence fence = request.expectedFence();
        return new DurableTestExecutionCheckpointRepository.ResumeLeaseCommand(
                request.clientRequestId(), requestFingerprint,
                new DurableTestExecutionCheckpointRepository.LeaseClaim(
                        identity.tenantId(), identity.environmentId(), runId,
                        new DurableTestExecutionCheckpointRepository.Fence(
                                fence.ownerId(), fence.leaseEpoch(), fence.revision()),
                        request.expectedCheckpointFingerprint(), ownerId, leaseDuration));
    }

    private String requestFingerprint(
            String runId,
            DurableTestOwnerClaimRequest request,
            IntegrationRequestContext identity) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.durableOwnerClaimAuthorizedIntent.v1"),
                Map.entry("runId", runId),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("expectedFence", request.expectedFence()),
                Map.entry("expectedCheckpointFingerprint",
                        request.expectedCheckpointFingerprint()),
                Map.entry("tenantId", identity.tenantId()),
                Map.entry("organizationId", identity.organizationId()),
                Map.entry("projectId", identity.projectId()),
                Map.entry("environmentId", identity.environmentId()),
                Map.entry("actorType", identity.actorType()),
                Map.entry("actorId", identity.actorId()),
                Map.entry("delegatedBy", identity.delegatedBy()),
                Map.entry("delegationGrantId", identity.delegationGrantId()),
                Map.entry("purpose", identity.purpose()),
                Map.entry("clearance", identity.clearance()),
                Map.entry("groups", identity.groups().stream().sorted().toList())));
    }

    private TestRuntimeTransactionMutation boundAllowedAudit(
            IntegrationRequestContext identity, String runId, String clientRequestId,
            boolean idempotentReplay) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(event(
                    identity, "ALLOWED", "RG.TEST.DURABLE_OWNER_CLAIM_AUTHORIZED",
                    runId, clientRequestId, idempotentReplay));
            if (mutation == null) {
                throw new IllegalStateException("Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable owner claim is unavailable because its security audit cannot commit.");
        }
    }

    private void appendReplayAudit(
            IntegrationRequestContext identity, String runId, String clientRequestId) {
        try {
            securityEvents.append(event(identity, "ALLOWED",
                    "RG.TEST.DURABLE_OWNER_CLAIM_IDEMPOTENT_REPLAY",
                    runId, clientRequestId, true));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable owner claim is unavailable because its security audit cannot commit.");
        }
    }

    private void rejected(IntegrationRequestContext identity, String runId, String reasonCode) {
        try {
            securityEvents.append(event(identity, "REJECTED", reasonCode, runId, "", false));
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE",
                    "Durable owner claim is unavailable because its security audit cannot commit.");
        }
    }

    private static TestSecurityEvent event(
            IntegrationRequestContext identity, String outcome, String reasonCode,
            String runId, String clientRequestId, boolean idempotentReplay) {
        Map<String, Object> facts = clientRequestId.isBlank()
                ? Map.of("runId", runId)
                : Map.of("runId", runId, "clientRequestId", clientRequestId,
                "idempotentReplay", idempotentReplay);
        return new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                identity.tenantId(), identity.environmentId(), identity.actorId(),
                "DURABLE_OWNER_CLAIM", outcome, reasonCode, facts);
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
            DurableTestOwnerClaimRequest request,
            IntegrationRequestContext identity) {
        boolean valid = request != null
                && DurableTestOwnerClaimRequest.SCHEMA_VERSION.equals(request.schemaVersion())
                && IDENTIFIER.matcher(normalized(runId)).matches()
                && IDENTIFIER.matcher(request.clientRequestId()).matches()
                && request.expectedFence() != null
                && IDENTIFIER.matcher(request.expectedFence().ownerId()).matches()
                && request.expectedFence().leaseEpoch() > 0
                && request.expectedFence().revision() >= 0
                && FINGERPRINT.matcher(request.expectedCheckpointFingerprint()).matches();
        if (!valid) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_OWNER_CLAIM_REQUEST_INVALID",
                    "Owner claim requires a versioned idempotency key, exact fence, and checkpoint fingerprint.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static void requireResultScope(
            DurableTestExecutionCheckpoint checkpoint, IntegrationRequestContext identity) {
        if (checkpoint == null || !identity.tenantId().equals(checkpoint.scope().tenantId())
                || !identity.organizationId().equals(checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())
                || !identity.environmentId().equals(checkpoint.scope().environmentId())) {
            throw new IntegrationProblemException(IntegrationProblem.notFound(
                    "RG.TEST.DURABLE_EXECUTION_NOT_FOUND",
                    "Durable test execution was not found in the authorized scope.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException mapConflict(
            DurableTestExecutionCheckpointConflictException.Reason reason,
            IntegrationRequestContext identity) {
        return switch (reason) {
            case STALE_FENCE -> conflict(identity, "RG.TEST.DURABLE_STALE_FENCE",
                    "The durable execution fence changed after caller selection.", true, Map.of());
            case LEASE_ACTIVE -> conflict(identity, "RG.TEST.DURABLE_LEASE_ACTIVE",
                    "The durable execution lease is still active.", true, Map.of());
            case NOT_RESUMABLE -> conflict(identity, "RG.TEST.DURABLE_NOT_RESUMABLE",
                    "The durable execution lifecycle cannot be resumed.", false, Map.of());
            case IDEMPOTENCY_CONFLICT -> conflict(identity,
                    "RG.TEST.DURABLE_IDEMPOTENCY_CONFLICT",
                    "clientRequestId already identifies different authorized owner-claim intent.",
                    false, Map.of());
            case DUPLICATE_IDENTITY, INVALID_TRANSITION -> conflict(identity,
                    "RG.TEST.DURABLE_OWNER_CLAIM_CONFLICT",
                    "The durable owner claim violates the current control state.", false, Map.of());
        };
    }

    private static IntegrationProblemException conflict(
            IntegrationRequestContext identity, String code, String title,
            boolean retryable, Map<String, Object> details) {
        IntegrationProblem problem = retryable
                ? IntegrationProblem.retryableConflict(code, title, identity.correlationId(), details)
                : IntegrationProblem.conflict(code, title, identity.correlationId(), details);
        return new IntegrationProblemException(problem);
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String code, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                code, title, identity.correlationId(), Map.of()));
    }

    private static String requiredIdentifier(String value, String field) {
        String normalized = normalized(value);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
