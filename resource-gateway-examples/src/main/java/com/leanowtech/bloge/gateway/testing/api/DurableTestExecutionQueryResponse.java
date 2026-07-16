package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Payload-free public projection of one integrity-verified durable test checkpoint.
 *
 * <p>The projection intentionally exposes content identities and operational fencing facts while
 * omitting business context, fixture values, replay payloads, deterministic provider cursors,
 * credentials, authority values, and BLOGE checkpoint bodies. A content fingerprint proves which
 * hidden closure is referenced; it does not grant access to that closure.</p>
 *
 * @param schemaVersion public query response version
 * @param runId governed durable run identity
 * @param engineExecutionId exact BLOGE execution identity
 * @param status durable control lifecycle status
 * @param fence current owner, lease epoch, and optimistic revision
 * @param leaseExpiresAt database-authority lease deadline
 * @param target exact graph/operator locator, absent only for legacy v1 checkpoints
 * @param fixture immutable governed fixture reference
 * @param authorizedPurpose server-authorized graph or operator test purpose
 * @param sideEffectPolicy frozen real-side-effect policy
 * @param planFingerprint complete effective-plan content identity
 * @param executionServiceStateFingerprint deterministic-provider state content identity
 * @param fixtureConsumptionStateFingerprint fixture-ledger state content identity
 * @param engineBoundary payload-free BLOGE boundary and closure identity
 * @param checkpointFingerprint complete control-checkpoint content identity
 * @param createdAt immutable durable-run creation time
 * @param updatedAt latest committed control transition time
 * @param recoverable whether the current closure is eligible for exact recovery
 * @param migrationRequired whether a legacy checkpoint lacks an exact target locator
 */
public record DurableTestExecutionQueryResponse(
        String schemaVersion,
        String runId,
        String engineExecutionId,
        String status,
        Fence fence,
        Instant leaseExpiresAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Target target,
        Fixture fixture,
        String authorizedPurpose,
        String sideEffectPolicy,
        String planFingerprint,
        String executionServiceStateFingerprint,
        String fixtureConsumptionStateFingerprint,
        EngineBoundary engineBoundary,
        String checkpointFingerprint,
        Instant createdAt,
        Instant updatedAt,
        boolean recoverable,
        boolean migrationRequired
) {
    /** Current public durable execution query response version. */
    public static final String SCHEMA_VERSION = "bloge.durableTestExecutionView.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Set<String> STATUSES = Set.of(
            "ACTIVE", "SUSPENDED", "RESUMING", "TERMINAL", "CONTROL_PLAN_UNAVAILABLE");

    /** Enforces a complete payload-free projection before it crosses the HTTP boundary. */
    public DurableTestExecutionQueryResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported durable execution view version");
        }
        runId = identifier(runId, "runId");
        engineExecutionId = identifier(engineExecutionId, "engineExecutionId");
        status = normalized(status).toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("Unsupported durable execution status");
        }
        fence = Objects.requireNonNull(fence, "fence");
        leaseExpiresAt = Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        fixture = Objects.requireNonNull(fixture, "fixture");
        authorizedPurpose = identifier(authorizedPurpose, "authorizedPurpose");
        sideEffectPolicy = identifier(sideEffectPolicy, "sideEffectPolicy");
        planFingerprint = fingerprint(planFingerprint, "planFingerprint");
        executionServiceStateFingerprint = fingerprint(
                executionServiceStateFingerprint, "executionServiceStateFingerprint");
        fixtureConsumptionStateFingerprint = fingerprint(
                fixtureConsumptionStateFingerprint, "fixtureConsumptionStateFingerprint");
        engineBoundary = Objects.requireNonNull(engineBoundary, "engineBoundary");
        checkpointFingerprint = fingerprint(checkpointFingerprint, "checkpointFingerprint");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt) || leaseExpiresAt.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Durable execution view timestamps are inconsistent");
        }
        if (migrationRequired == (target != null)) {
            throw new IllegalArgumentException(
                    "Migration status must agree with exact target availability");
        }
        if (recoverable && migrationRequired) {
            throw new IllegalArgumentException("A migration-required execution is not recoverable");
        }
    }

    /**
     * Current compare-and-set authority projected without hidden dispatch material.
     *
     * @param ownerId current process owner identity
     * @param leaseEpoch positive fencing generation
     * @param revision non-negative checkpoint revision
     */
    public record Fence(String ownerId, long leaseEpoch, long revision) {
        /** Rejects incomplete or impossible owner fences. */
        public Fence {
            ownerId = identifier(ownerId, "ownerId");
            if (leaseEpoch <= 0 || revision < 0) {
                throw new IllegalArgumentException("Durable execution fence is invalid");
            }
        }
    }

    /**
     * Exact executable target locator and content identity.
     *
     * @param kind {@code GRAPH} or {@code OPERATOR}
     * @param id registered graph name or operator reference
     * @param fingerprint complete target content identity
     */
    public record Target(String kind, String id, String fingerprint) {
        /** Validates target kind, locator, and content identity. */
        public Target {
            kind = normalized(kind).toUpperCase(Locale.ROOT);
            if (!Set.of("GRAPH", "OPERATOR").contains(kind)) {
                throw new IllegalArgumentException("Durable execution target kind is invalid");
            }
            id = identifier(id, "target.id");
            fingerprint = DurableTestExecutionQueryResponse.fingerprint(
                    fingerprint, "target.fingerprint");
        }
    }

    /**
     * Immutable fixture registry reference without any fixture value.
     *
     * @param fixtureBundleId governed fixture identity
     * @param revision positive immutable revision
     * @param fingerprint fixture content identity
     */
    public record Fixture(String fixtureBundleId, long revision, String fingerprint) {
        /** Rejects mutable aliases and incomplete fixture references. */
        public Fixture {
            fixtureBundleId = identifier(fixtureBundleId, "fixtureBundleId");
            if (revision <= 0) {
                throw new IllegalArgumentException("Fixture revision must be positive");
            }
            fingerprint = DurableTestExecutionQueryResponse.fingerprint(
                    fingerprint, "fixture.fingerprint");
        }
    }

    /**
     * Payload-free identity of the atomic BLOGE durable-state closure.
     *
     * @param checkpointRef stable checkpoint reference
     * @param nodeId boundary node identifier
     * @param boundaryType durable boundary category
     * @param boundarySequence positive monotonic boundary sequence
     * @param stateVersion non-negative BLOGE execution version
     * @param closureFingerprint complete aggregate state identity
     */
    public record EngineBoundary(
            String checkpointRef,
            String nodeId,
            String boundaryType,
            long boundarySequence,
            long stateVersion,
            String closureFingerprint
    ) {
        /** Enforces a complete monotonic boundary identity. */
        public EngineBoundary {
            checkpointRef = identifier(checkpointRef, "checkpointRef");
            nodeId = identifier(nodeId, "nodeId");
            boundaryType = identifier(boundaryType, "boundaryType");
            if (boundarySequence <= 0 || stateVersion < 0) {
                throw new IllegalArgumentException("Engine boundary sequence or version is invalid");
            }
            closureFingerprint = fingerprint(closureFingerprint, "closureFingerprint");
        }
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return result;
    }

    private static String fingerprint(String value, String field) {
        String result = normalized(value);
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
