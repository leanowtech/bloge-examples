package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.api.DurableTestExecutionCheckpointRepository.WorkerAcquisitionScope;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableWorkerQuarantineControlPlane;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated tenant-project maintenance boundary for durable worker quarantines.
 *
 * <p>Unlike the global projection-finding queue, every quarantine has trustworthy scope
 * projections. The service therefore requires both exact identity-derived scope and the dedicated
 * maintenance purpose, deployment-owned operator group, configured clearance, and a test or
 * staging environment. Owner and scope never come from request JSON.</p>
 */
public final class DurableWorkerQuarantineService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> CLEARANCES =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern REASON_CODE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    private final DatabaseDurableWorkerQuarantineControlPlane controlPlane;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final String requiredGroup;
    private final String requiredClearance;

    /**
     * Creates the fail-closed worker quarantine maintenance boundary.
     *
     * @param controlPlane exact-checkpoint queue, claims, resolutions, and history authority
     * @param securityEvents append-only semantic security-event sink
     * @param objectMapper canonical audit intent fingerprint mapper
     * @param requiredGroup deployment-owned maintenance operator group
     * @param requiredClearance minimum supported identity clearance
     */
    public DurableWorkerQuarantineService(
            DatabaseDurableWorkerQuarantineControlPlane controlPlane,
            TestSecurityEventRepository securityEvents,
            ObjectMapper objectMapper,
            String requiredGroup,
            String requiredClearance) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.securityEvents = Objects.requireNonNull(securityEvents, "securityEvents");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.requiredGroup = required(requiredGroup, "requiredGroup", 255);
        this.requiredClearance = required(requiredClearance, "requiredClearance", 32)
                .toUpperCase(Locale.ROOT);
        if (!CLEARANCES.contains(this.requiredClearance)) {
            throw new IllegalArgumentException("requiredClearance is not supported");
        }
    }

    /** Returns one bounded payload-free quarantine page after scoped maintenance authorization. */
    public DurableWorkerQuarantinesResponse quarantines(
            boolean actionableOnly, int limit, IntegrationRequestContext identity) {
        WorkerAcquisitionScope scope = authorize(identity, "READ");
        validateLimit(limit, identity, "READ");
        try {
            var records = controlPlane.quarantines(scope, actionableOnly, limit);
            append(identity, event(identity, "READ", "ALLOWED",
                    "RG.TEST.WORKER_QUARANTINE_READ_ALLOWED", Map.of(
                            "actionableOnly", actionableOnly, "limit", limit,
                            "resultCount", records.size())));
            return DurableWorkerQuarantinesResponse.from(actionableOnly, records);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Worker quarantine storage is unavailable.");
        }
    }

    /** Returns one bounded immutable token-free action-history page. */
    public DurableWorkerQuarantineHistoryResponse history(
            int limit, IntegrationRequestContext identity) {
        WorkerAcquisitionScope scope = authorize(identity, "HISTORY_READ");
        validateLimit(limit, identity, "HISTORY_READ");
        try {
            var records = controlPlane.history(scope, limit);
            append(identity, event(identity, "HISTORY_READ", "ALLOWED",
                    "RG.TEST.WORKER_QUARANTINE_HISTORY_READ_ALLOWED", Map.of(
                            "limit", limit, "resultCount", records.size())));
            return DurableWorkerQuarantineHistoryResponse.from(records);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Worker quarantine history is unavailable.");
        }
    }

    /** Idempotently claims one exact quarantine for the verified workload actor. */
    public DurableWorkerQuarantineClaimResponse claim(
            DurableWorkerQuarantineClaimRequest request,
            IntegrationRequestContext identity) {
        WorkerAcquisitionScope scope = authorize(identity, "CLAIM");
        validateClaim(request, identity);
        var key = key(request.key(), identity, "CLAIM");
        String intentFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", request.schemaVersion()),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("key", request.key()),
                Map.entry("claimDurationSeconds", request.claimDurationSeconds()),
                Map.entry("actorId", identity.actorId())));
        try {
            var result = controlPlane.claim(scope, key, identity.actorId(),
                    request.clientRequestId(), Duration.ofSeconds(request.claimDurationSeconds()),
                    claim -> bound(identity, "CLAIM",
                            "RG.TEST.WORKER_QUARANTINE_CLAIM_COMMITTED", Map.of(
                                    "runId", key.runId(),
                                    "checkpointFingerprint", key.checkpointFingerprint(),
                                    "clientRequestId", request.clientRequestId(),
                                    "intentFingerprint", intentFingerprint,
                                    "version", claim.version(),
                                    "claimDurationSeconds", request.claimDurationSeconds())));
            return switch (result.disposition()) {
                case CLAIMED -> DurableWorkerQuarantineClaimResponse.from(result);
                case IDEMPOTENT_REPLAY -> {
                    append(identity, event(identity, "CLAIM", "ALLOWED",
                            "RG.TEST.WORKER_QUARANTINE_CLAIM_IDEMPOTENT_REPLAY", Map.of(
                                    "runId", key.runId(),
                                    "clientRequestId", request.clientRequestId(),
                                    "version", result.claim().version())));
                    yield DurableWorkerQuarantineClaimResponse.from(result);
                }
                case NOT_ACTIONABLE -> throw conflict(identity, "CLAIM",
                        "RG.TEST.WORKER_QUARANTINE_NOT_ACTIONABLE",
                        "The quarantine is missing or has another live maintenance owner.",
                        key, request.clientRequestId());
                case IDEMPOTENCY_CONFLICT -> throw conflict(identity, "CLAIM",
                        "RG.TEST.WORKER_QUARANTINE_IDEMPOTENCY_CONFLICT",
                        "The client request ID was reused with changed claim intent.",
                        key, request.clientRequestId());
                case STALE_CHECKPOINT -> throw conflict(identity, "CLAIM",
                        "RG.TEST.WORKER_QUARANTINE_STALE_CHECKPOINT",
                        "The run no longer has the exact quarantined checkpoint.",
                        key, request.clientRequestId());
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Worker quarantine claim storage is unavailable.");
        }
    }

    /** Idempotently releases or discards one exact live claim for the verified actor. */
    public DurableWorkerQuarantineResolutionResponse resolve(
            DurableWorkerQuarantineResolutionRequest request,
            IntegrationRequestContext identity) {
        WorkerAcquisitionScope scope = authorize(identity, "RESOLVE");
        validateResolution(request, identity);
        var key = key(request.key(), identity, "RESOLVE");
        DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction action =
                resolutionAction(request.action(), identity);
        var claim = new DatabaseDurableWorkerQuarantineControlPlane.QuarantineClaim(
                key, identity.actorId(), request.claimToken(), request.claimVersion(),
                request.claimUntil());
        String intentFingerprint = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", request.schemaVersion()),
                Map.entry("clientRequestId", request.clientRequestId()),
                Map.entry("key", request.key()), Map.entry("action", action.name()),
                Map.entry("reasonCode", request.reasonCode()),
                Map.entry("claimVersion", request.claimVersion()),
                Map.entry("claimUntil", request.claimUntil()),
                Map.entry("actorId", identity.actorId())));
        try {
            var result = controlPlane.resolve(scope, claim, request.clientRequestId(), action,
                    request.reasonCode(), receipt -> bound(identity, "RESOLVE",
                            "RG.TEST.WORKER_QUARANTINE_RESOLUTION_COMMITTED", Map.ofEntries(
                                    Map.entry("runId", key.runId()),
                                    Map.entry("checkpointFingerprint", key.checkpointFingerprint()),
                                    Map.entry("clientRequestId", request.clientRequestId()),
                                    Map.entry("intentFingerprint", intentFingerprint),
                                    Map.entry("claimVersion", request.claimVersion()),
                                    Map.entry("resultVersion", receipt.version()),
                                    Map.entry("action", receipt.action().name()),
                                    Map.entry("reasonCode", receipt.reasonCode()),
                                    Map.entry("receiptFingerprint",
                                            receipt.receiptFingerprint()))));
            return switch (result.disposition()) {
                case RESOLVED -> DurableWorkerQuarantineResolutionResponse.from(result);
                case IDEMPOTENT_REPLAY -> {
                    append(identity, event(identity, "RESOLVE", "ALLOWED",
                            "RG.TEST.WORKER_QUARANTINE_RESOLUTION_IDEMPOTENT_REPLAY", Map.of(
                                    "runId", key.runId(),
                                    "clientRequestId", request.clientRequestId(),
                                    "action", result.receipt().action().name(),
                                    "receiptFingerprint",
                                    result.receipt().receiptFingerprint())));
                    yield DurableWorkerQuarantineResolutionResponse.from(result);
                }
                case FENCE_REJECTED -> throw conflict(identity, "RESOLVE",
                        "RG.TEST.WORKER_QUARANTINE_FENCE_REJECTED",
                        "The quarantine claim fence is stale, forged, expired, or consumed.",
                        key, request.clientRequestId());
                case IDEMPOTENCY_CONFLICT -> throw conflict(identity, "RESOLVE",
                        "RG.TEST.WORKER_QUARANTINE_IDEMPOTENCY_CONFLICT",
                        "The client request ID was reused with changed resolution intent.",
                        key, request.clientRequestId());
                case STALE_CHECKPOINT -> throw conflict(identity, "RESOLVE",
                        "RG.TEST.WORKER_QUARANTINE_STALE_CHECKPOINT",
                        "The run no longer has the exact quarantined checkpoint.",
                        key, request.clientRequestId());
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Worker quarantine resolution storage is unavailable.");
        }
    }

    private WorkerAcquisitionScope authorize(
            IntegrationRequestContext identity, String action) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            deny(identity, action, "RG.TEST.WORKER_QUARANTINE_ENVIRONMENT_FORBIDDEN",
                    "Worker quarantine maintenance is restricted to test and staging.");
        }
        if (!"TEST_RUNTIME_MAINTENANCE".equals(identity.purpose())) {
            deny(identity, action, "RG.TEST.WORKER_QUARANTINE_PURPOSE_FORBIDDEN",
                    "The dedicated test-runtime maintenance purpose is required.");
        }
        if (!identity.groups().contains(requiredGroup)) {
            deny(identity, action, "RG.TEST.WORKER_QUARANTINE_ROLE_REQUIRED",
                    "The deployment-owned test-runtime operator role is required.");
        }
        if (!identity.hasClearanceAtLeast(requiredClearance)) {
            deny(identity, action, "RG.TEST.WORKER_QUARANTINE_CLEARANCE_REQUIRED",
                    "The configured worker quarantine clearance is required.");
        }
        try {
            return new WorkerAcquisitionScope(identity.tenantId(), identity.organizationId(),
                    identity.projectId(), identity.environmentId());
        } catch (IllegalArgumentException invalid) {
            deny(identity, action, "RG.TEST.WORKER_QUARANTINE_SCOPE_REQUIRED",
                    "A complete stable tenant, organization, project, and environment is required.");
            throw invalid;
        }
    }

    private void validateLimit(int limit, IntegrationRequestContext identity, String action) {
        if (limit < 1 || limit > 1_000) {
            rejected(identity, action, "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Worker quarantine limit must be between 1 and 1000.");
        }
    }

    private void validateClaim(
            DurableWorkerQuarantineClaimRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !DurableWorkerQuarantineClaimRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !REQUEST_ID.matcher(request.clientRequestId()).matches()
                || request.key() == null
                || request.claimDurationSeconds() < 1
                || request.claimDurationSeconds() > 3_600) {
            rejected(identity, "CLAIM", "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Worker quarantine claim request is invalid.");
        }
    }

    private void validateResolution(
            DurableWorkerQuarantineResolutionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !DurableWorkerQuarantineResolutionRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !REQUEST_ID.matcher(request.clientRequestId()).matches()
                || request.key() == null || !bounded(request.claimToken(), 255)
                || request.claimVersion() <= 0 || request.claimUntil() == null
                || !bounded(request.action(), 32) || request.reasonCode() == null
                || !REASON_CODE.matcher(request.reasonCode()).matches()) {
            rejected(identity, "RESOLVE", "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Worker quarantine resolution request is invalid.");
        }
    }

    private DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey key(
            DurableWorkerQuarantineKey supplied,
            IntegrationRequestContext identity,
            String action) {
        try {
            return new DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey(
                    supplied.runId(), supplied.checkpointFingerprint());
        } catch (RuntimeException invalid) {
            rejected(identity, action, "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Worker quarantine key is invalid.");
        }
    }

    private DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction resolutionAction(
            String supplied, IntegrationRequestContext identity) {
        try {
            return DatabaseDurableWorkerQuarantineControlPlane.ResolutionAction.valueOf(
                    supplied.toUpperCase(Locale.ROOT));
        } catch (RuntimeException invalid) {
            rejected(identity, "RESOLVE", "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Resolution action must be RELEASE or DISCARD.");
        }
    }

    private void deny(
            IntegrationRequestContext identity, String action, String code, String title) {
        rejected(identity, action, code, Map.of());
        throw new IntegrationProblemException(IntegrationProblem.forbidden(
                code, title, identity.correlationId(), Map.of()));
    }

    private TestRuntimeTransactionMutation bound(
            IntegrationRequestContext identity,
            String action,
            String reasonCode,
            Map<String, Object> facts) {
        try {
            TestRuntimeTransactionMutation mutation = securityEvents.boundAppend(
                    event(identity, action, "ALLOWED", reasonCode, facts));
            if (mutation == null) {
                throw new IllegalStateException("Security audit did not provide a bound mutation");
            }
            return mutation;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "Worker quarantine operation is unavailable because audit cannot commit.");
        }
    }

    private IntegrationProblemException conflict(
            IntegrationRequestContext identity,
            String action,
            String code,
            String title,
            DatabaseDurableWorkerQuarantineControlPlane.QuarantineKey key,
            String clientRequestId) {
        rejected(identity, action, code, Map.of(
                "runId", key.runId(), "checkpointFingerprint", key.checkpointFingerprint(),
                "clientRequestId", clientRequestId));
        return new IntegrationProblemException(IntegrationProblem.conflict(
                code, title, identity.correlationId(), Map.of()));
    }

    private void rejected(
            IntegrationRequestContext identity,
            String action,
            String code,
            Map<String, Object> facts) {
        append(identity, event(identity, action, "REJECTED", code, facts));
    }

    private void append(IntegrationRequestContext identity, TestSecurityEvent event) {
        try {
            securityEvents.append(event);
        } catch (RuntimeException unavailable) {
            throw unavailable(identity,
                    "Worker quarantine operation is unavailable because audit cannot commit.");
        }
    }

    private static TestSecurityEvent event(
            IntegrationRequestContext identity,
            String action,
            String outcome,
            String reasonCode,
            Map<String, Object> facts) {
        return new TestSecurityEvent(0, Instant.now(), identity.correlationId(),
                identity.tenantId(), identity.environmentId(), identity.actorId(),
                "DURABLE_WORKER_QUARANTINE_" + action, outcome, reasonCode, facts);
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.TEST.WORKER_QUARANTINE_REQUEST_INVALID", title,
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.WORKER_QUARANTINE_CONTROL_UNAVAILABLE", title,
                identity.correlationId(), Map.of()));
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static String required(String value, String field, int maximum) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank() || safe.length() > maximum) {
            throw new IllegalArgumentException(field + " must contain 1.." + maximum
                    + " characters");
        }
        return safe;
    }
}
