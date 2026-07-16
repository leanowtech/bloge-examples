package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseDurableStateProjectionControlPlane;
import com.leanowtech.bloge.gateway.testing.persistence.DurableStateProjectionReconciler;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated global operations boundary for durable scheduling-projection findings.
 *
 * <p>Finding rows do not carry a trustworthy tenant scope, so ordinary tenant authorization is
 * insufficient. Every operation requires the dedicated maintenance purpose, a deployment-owned
 * global operator group, a minimum clearance, and a test or staging environment. Claim ownership
 * always comes from verified identity. Fresh state changes and their append-only action events are
 * committed by the control plane in one local test-runtime transaction.</p>
 */
public final class DurableStateProjectionFindingService {

    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");
    private static final Set<String> CLEARANCES =
            Set.of("PUBLIC", "INTERNAL", "CONFIDENTIAL", "RESTRICTED");
    private static final Pattern REQUEST_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    private final DatabaseDurableStateProjectionControlPlane controlPlane;
    private final TestSecurityEventRepository securityEvents;
    private final ObjectMapper objectMapper;
    private final String requiredGroup;
    private final String requiredClearance;

    /**
     * Creates the fail-closed projection finding operations boundary.
     *
     * @param controlPlane durable cursor, queue, claim, and resolution authority
     * @param securityEvents append-only semantic action-event sink
     * @param objectMapper canonical action-intent fingerprint mapper
     * @param requiredGroup deployment-owned global operator group
     * @param requiredClearance minimum supported identity clearance
     */
    public DurableStateProjectionFindingService(
            DatabaseDurableStateProjectionControlPlane controlPlane,
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

    /**
     * Lists a bounded payload-free queue page after global maintenance authorization.
     *
     * @param actionableOnly true to exclude resolved findings and live claims
     * @param limit page size from 1 through 1000
     * @param identity verified integration workload identity
     * @return payload-free finding page
     */
    public DurableStateProjectionFindingsResponse findings(
            boolean actionableOnly,
            int limit,
            IntegrationRequestContext identity) {
        authorize(identity, "READ");
        if (limit < 1 || limit > 1000) {
            rejected(identity, "READ", "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Projection finding limit must be between 1 and 1000.");
        }
        try {
            var records = actionableOnly
                    ? controlPlane.actionableFindings(limit) : controlPlane.findings(limit);
            append(identity, event(identity, "READ", "ALLOWED",
                    "RG.TEST.PROJECTION_FINDING_READ_ALLOWED",
                    Map.of("actionableOnly", actionableOnly, "limit", limit,
                            "resultCount", records.size())));
            return DurableStateProjectionFindingsResponse.from(actionableOnly, records);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Projection finding storage is unavailable.");
        }
    }

    /**
     * Idempotently claims one finding for the verified actor.
     *
     * @param request caller request without any owner field
     * @param identity verified integration workload identity
     * @return exact claim fence, including the one response-only token
     */
    public DurableStateProjectionFindingClaimResponse claim(
            DurableStateProjectionFindingClaimRequest request,
            IntegrationRequestContext identity) {
        authorize(identity, "CLAIM");
        validateClaim(request, identity);
        DurableStateProjectionReconciler.EntityKey key = key(request.key(), identity, "CLAIM");
        String intentFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", request.schemaVersion(),
                "clientRequestId", request.clientRequestId(),
                "key", request.key(),
                "claimDurationSeconds", request.claimDurationSeconds(),
                "actorId", identity.actorId()));
        try {
            DatabaseDurableStateProjectionControlPlane.FindingClaimResult result =
                    controlPlane.claimFinding(key, identity.actorId(), request.clientRequestId(),
                            Duration.ofSeconds(request.claimDurationSeconds()),
                            claim -> bound(identity, "CLAIM",
                                    "RG.TEST.PROJECTION_FINDING_CLAIM_COMMITTED", Map.of(
                                            "entityType", key.entityType().name(),
                                            "rowId", key.rowId(),
                                            "clientRequestId", request.clientRequestId(),
                                            "intentFingerprint", intentFingerprint,
                                            "findingVersion", claim.version(),
                                            "claimDurationSeconds",
                                            request.claimDurationSeconds())));
            return switch (result.disposition()) {
                case CLAIMED -> DurableStateProjectionFindingClaimResponse.from(result);
                case IDEMPOTENT_REPLAY -> {
                    append(identity, event(identity, "CLAIM", "ALLOWED",
                            "RG.TEST.PROJECTION_FINDING_CLAIM_IDEMPOTENT_REPLAY", Map.of(
                                    "entityType", key.entityType().name(),
                                    "rowId", key.rowId(),
                                    "clientRequestId", request.clientRequestId(),
                                    "findingVersion", result.claim().version())));
                    yield DurableStateProjectionFindingClaimResponse.from(result);
                }
                case NOT_ACTIONABLE -> throw rejectedConflict(identity, "CLAIM",
                        "RG.TEST.PROJECTION_FINDING_NOT_ACTIONABLE",
                        "The projection finding is resolved or has another live owner.", key,
                        request.clientRequestId());
                case IDEMPOTENCY_CONFLICT -> throw rejectedConflict(identity, "CLAIM",
                        "RG.TEST.PROJECTION_FINDING_IDEMPOTENCY_CONFLICT",
                        "The client request ID was reused with changed claim facts.", key,
                        request.clientRequestId());
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Projection finding claim storage is unavailable.");
        }
    }

    /**
     * Idempotently resolves one exact live claim for the verified actor.
     *
     * @param request exact server-issued fence and manual disposition
     * @param identity verified integration workload identity
     * @return immutable token-free resolution receipt
     */
    public DurableStateProjectionFindingResolutionResponse resolve(
            DurableStateProjectionFindingResolutionRequest request,
            IntegrationRequestContext identity) {
        authorize(identity, "RESOLVE");
        validateResolution(request, identity);
        DurableStateProjectionReconciler.EntityKey key = key(request.key(), identity, "RESOLVE");
        DatabaseDurableStateProjectionControlPlane.Resolution resolution =
                manualResolution(request.resolution(), identity);
        DatabaseDurableStateProjectionControlPlane.FindingClaim claim =
                new DatabaseDurableStateProjectionControlPlane.FindingClaim(
                        key, identity.actorId(), request.claimToken(), request.claimVersion(),
                        request.claimUntil());
        String intentFingerprint = ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion", request.schemaVersion(),
                "clientRequestId", request.clientRequestId(),
                "key", request.key(),
                "claimVersion", request.claimVersion(),
                "claimUntil", request.claimUntil(),
                "resolution", resolution.name(),
                "actorId", identity.actorId()));
        try {
            DatabaseDurableStateProjectionControlPlane.FindingResolutionResult result =
                    controlPlane.resolveFinding(claim, request.clientRequestId(), resolution,
                            committed -> bound(identity, "RESOLVE",
                                    "RG.TEST.PROJECTION_FINDING_RESOLUTION_COMMITTED", Map.of(
                                            "entityType", key.entityType().name(),
                                            "rowId", key.rowId(),
                                            "clientRequestId", request.clientRequestId(),
                                            "intentFingerprint", intentFingerprint,
                                            "claimVersion", request.claimVersion(),
                                            "findingVersion", committed.version(),
                                            "resolution", committed.resolution().name())));
            return switch (result.disposition()) {
                case RESOLVED -> DurableStateProjectionFindingResolutionResponse.from(result);
                case IDEMPOTENT_REPLAY -> {
                    append(identity, event(identity, "RESOLVE", "ALLOWED",
                            "RG.TEST.PROJECTION_FINDING_RESOLUTION_IDEMPOTENT_REPLAY", Map.of(
                                    "entityType", key.entityType().name(),
                                    "rowId", key.rowId(),
                                    "clientRequestId", request.clientRequestId(),
                                    "findingVersion", result.resolution().version(),
                                    "resolution", result.resolution().resolution().name())));
                    yield DurableStateProjectionFindingResolutionResponse.from(result);
                }
                case FENCE_REJECTED -> throw rejectedConflict(identity, "RESOLVE",
                        "RG.TEST.PROJECTION_FINDING_FENCE_REJECTED",
                        "The projection finding claim fence is stale, forged, or expired.", key,
                        request.clientRequestId());
                case IDEMPOTENCY_CONFLICT -> throw rejectedConflict(identity, "RESOLVE",
                        "RG.TEST.PROJECTION_FINDING_IDEMPOTENCY_CONFLICT",
                        "The client request ID was reused with changed resolution facts.", key,
                        request.clientRequestId());
            };
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity, "Projection finding resolution storage is unavailable.");
        }
    }

    private void authorize(IntegrationRequestContext identity, String action) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId().toLowerCase(Locale.ROOT))) {
            deny(identity, action, "RG.TEST.PROJECTION_FINDING_ENVIRONMENT_FORBIDDEN",
                    "Projection finding operations are restricted to test and staging.");
        }
        if (!"TEST_RUNTIME_MAINTENANCE".equals(identity.purpose())) {
            deny(identity, action, "RG.TEST.PROJECTION_FINDING_PURPOSE_FORBIDDEN",
                    "The dedicated test-runtime maintenance purpose is required.");
        }
        if (!identity.groups().contains(requiredGroup)) {
            deny(identity, action, "RG.TEST.PROJECTION_FINDING_GLOBAL_ROLE_REQUIRED",
                    "The deployment-owned global projection operator role is required.");
        }
        if (!identity.hasClearanceAtLeast(requiredClearance)) {
            deny(identity, action, "RG.TEST.PROJECTION_FINDING_CLEARANCE_REQUIRED",
                    "The configured projection finding clearance is required.");
        }
    }

    private void deny(
            IntegrationRequestContext identity, String action, String code, String title) {
        rejected(identity, action, code, Map.of());
        throw new IntegrationProblemException(IntegrationProblem.forbidden(
                code, title, identity.correlationId(), Map.of()));
    }

    private void validateClaim(
            DurableStateProjectionFindingClaimRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !DurableStateProjectionFindingClaimRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !REQUEST_ID.matcher(request.clientRequestId()).matches()
                || request.key() == null
                || request.claimDurationSeconds() < 1
                || request.claimDurationSeconds() > 3600) {
            rejected(identity, "CLAIM", "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Projection finding claim request is invalid.");
        }
    }

    private void validateResolution(
            DurableStateProjectionFindingResolutionRequest request,
            IntegrationRequestContext identity) {
        if (request == null
                || !DurableStateProjectionFindingResolutionRequest.SCHEMA_VERSION.equals(
                request.schemaVersion())
                || !REQUEST_ID.matcher(request.clientRequestId()).matches()
                || request.key() == null
                || !bounded(request.claimToken(), 255)
                || request.claimVersion() <= 0
                || request.claimUntil() == null) {
            rejected(identity, "RESOLVE", "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Projection finding resolution request is invalid.");
        }
    }

    private DurableStateProjectionReconciler.EntityKey key(
            DurableStateProjectionFindingKey supplied,
            IntegrationRequestContext identity,
            String action) {
        try {
            if (supplied == null || !bounded(supplied.rowId(), 512)
                    || supplied.rowId().chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("invalid row identity");
            }
            return new DurableStateProjectionReconciler.EntityKey(
                    DurableStateProjectionReconciler.EntityType.valueOf(
                            supplied.entityType().toUpperCase(Locale.ROOT)), supplied.rowId());
        } catch (RuntimeException invalid) {
            rejected(identity, action, "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", Map.of());
            throw badRequest(identity, "Projection finding key is invalid.");
        }
    }

    private DatabaseDurableStateProjectionControlPlane.Resolution manualResolution(
            String supplied,
            IntegrationRequestContext identity) {
        try {
            DatabaseDurableStateProjectionControlPlane.Resolution resolution =
                    DatabaseDurableStateProjectionControlPlane.Resolution.valueOf(
                            supplied.toUpperCase(Locale.ROOT));
            if (resolution != DatabaseDurableStateProjectionControlPlane.Resolution.MANUALLY_REPAIRED
                    && resolution
                    != DatabaseDurableStateProjectionControlPlane.Resolution.QUARANTINED) {
                throw new IllegalArgumentException("not manual");
            }
            return resolution;
        } catch (RuntimeException invalid) {
            rejected(identity, "RESOLVE", "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", Map.of());
            throw badRequest(identity,
                    "Resolution must be MANUALLY_REPAIRED or QUARANTINED.");
        }
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
                    "Projection finding operation is unavailable because its audit cannot commit.");
        }
    }

    private IntegrationProblemException rejectedConflict(
            IntegrationRequestContext identity,
            String action,
            String code,
            String title,
            DurableStateProjectionReconciler.EntityKey key,
            String clientRequestId) {
        rejected(identity, action, code, Map.of(
                "entityType", key.entityType().name(), "rowId", key.rowId(),
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
                    "Projection finding operation is unavailable because its audit cannot commit.");
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
                "DURABLE_PROJECTION_FINDING_" + action, outcome, reasonCode, facts);
    }

    private static IntegrationProblemException badRequest(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.badRequest(
                "RG.TEST.PROJECTION_FINDING_REQUEST_INVALID", title,
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(
            IntegrationRequestContext identity, String title) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.PROJECTION_FINDING_CONTROL_UNAVAILABLE", title,
                identity.correlationId(), Map.of()));
    }

    private static boolean bounded(String value, int maximum) {
        return value != null && !value.isBlank() && value.length() <= maximum;
    }

    private static String required(String value, String name, int maximum) {
        String safe = value == null ? "" : value.trim();
        if (safe.isBlank() || safe.length() > maximum) {
            throw new IllegalArgumentException(name + " must contain 1.." + maximum + " characters");
        }
        return safe;
    }
}
