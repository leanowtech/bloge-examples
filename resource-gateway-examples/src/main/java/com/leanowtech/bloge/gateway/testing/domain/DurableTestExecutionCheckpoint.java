package com.leanowtech.bloge.gateway.testing.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Content-addressed recovery closure for one governed durable test execution.
 *
 * <p>The record intentionally contains no fixture payload, provider seed, raw correlation key,
 * identity value, secret, or BLOGE checkpoint body. It binds their exact governed identities and
 * state fingerprints to a fenced lifecycle. A trusted repository must commit this control record
 * atomically with the referenced engine-state mutation.</p>
 *
 * @param schemaVersion composite checkpoint protocol version
 * @param scope immutable tenant, organization, project, environment, and actor scope
 * @param runId governed test-run identity
 * @param engineExecutionId exact BLOGE execution identity
 * @param dependencies immutable execution plan, fixture, side-effect, and authority closure
 * @param fixtureConsumptionState rule-use and occurrence cursors
 * @param executionServiceState run-scoped logical time/random/UUID provider state
 * @param engineState payload-free BLOGE checkpoint closure identity
 * @param lifecycle status, owner lease epoch, and optimistic revision
 * @param checkpointFingerprint canonical fingerprint of all preceding fields
 */
public record DurableTestExecutionCheckpoint(
        String schemaVersion,
        Scope scope,
        String runId,
        String engineExecutionId,
        ControlDependencies dependencies,
        FixtureConsumptionStateSnapshot fixtureConsumptionState,
        ExecutionServiceStateSnapshot executionServiceState,
        EngineState engineState,
        Lifecycle lifecycle,
        String checkpointFingerprint
) {
    /** Legacy protocol without an independently resolvable execution-target locator. */
    public static final String SCHEMA_VERSION_V1 = "bloge.durableTestExecutionCheckpoint.v1";
    /** Current protocol adds the exact graph/operator target locator required for reauthorization. */
    public static final String SCHEMA_VERSION = "bloge.durableTestExecutionCheckpoint.v2";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Verifies cross-component identity closure before a checkpoint can be sealed. */
    public DurableTestExecutionCheckpoint {
        schemaVersion = normalized(schemaVersion);
        runId = requiredIdentifier(runId, "runId");
        engineExecutionId = requiredIdentifier(engineExecutionId, "engineExecutionId");
        checkpointFingerprint = normalized(checkpointFingerprint);
        if (!Set.of(SCHEMA_VERSION_V1, SCHEMA_VERSION).contains(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported durable test checkpoint version");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(fixtureConsumptionState, "fixtureConsumptionState");
        Objects.requireNonNull(executionServiceState, "executionServiceState");
        Objects.requireNonNull(engineState, "engineState");
        Objects.requireNonNull(lifecycle, "lifecycle");
        if (SCHEMA_VERSION.equals(schemaVersion) && dependencies.target() == null) {
            throw new IllegalArgumentException(
                    "Current durable test checkpoint requires an exact target locator");
        }
        if (SCHEMA_VERSION_V1.equals(schemaVersion) && dependencies.target() != null) {
            throw new IllegalArgumentException("A v1 checkpoint cannot contain the v2 target locator");
        }
        if (!dependencies.plan().planFingerprint().equals(executionServiceState.planFingerprint())) {
            throw new IllegalArgumentException(
                    "Execution plan and execution-service state must bind the same effective plan");
        }
        if (lifecycle.status().resumable() && !executionServiceState.restorable()) {
            throw new IllegalArgumentException(
                    "A resumable durable checkpoint requires exactly restorable execution services");
        }
        if (!checkpointFingerprint.isEmpty() && !fingerprint(checkpointFingerprint)) {
            throw new IllegalArgumentException(
                    "checkpointFingerprint must be empty or a canonical SHA-256 fingerprint");
        }
    }

    /**
     * Projects the complete canonical material covered by the aggregate fingerprint.
     *
     * @return canonical material covered by {@link #checkpointFingerprint()}
     */
    public Map<String, Object> fingerprintMaterial() {
        return Map.of(
                "schemaVersion", schemaVersion,
                "scope", scope,
                "runId", runId,
                "engineExecutionId", engineExecutionId,
                "dependencies", dependencies,
                "fixtureConsumptionState", fixtureConsumptionState,
                "executionServiceState", executionServiceState,
                "engineState", engineState,
                "lifecycle", lifecycle);
    }

    /**
     * Copies this checkpoint with a supplied composite fingerprint.
     *
     * @param fingerprint canonical aggregate fingerprint, or empty before sealing
     * @return an immutable copy carrying the supplied composite fingerprint
     */
    public DurableTestExecutionCheckpoint withCheckpointFingerprint(String fingerprint) {
        return new DurableTestExecutionCheckpoint(schemaVersion, scope, runId, engineExecutionId,
                dependencies, fixtureConsumptionState, executionServiceState, engineState,
                lifecycle, fingerprint);
    }

    /**
     * Copies this checkpoint with the exact fixture-consumption state being sealed.
     *
     * @param state immutable fixture rule and occurrence cursor state
     * @return an immutable copy with the supplied fixture-consumption state
     */
    public DurableTestExecutionCheckpoint withFixtureConsumptionState(
            FixtureConsumptionStateSnapshot state) {
        return new DurableTestExecutionCheckpoint(schemaVersion, scope, runId, engineExecutionId,
                dependencies, state, executionServiceState, engineState, lifecycle,
                checkpointFingerprint);
    }

    /**
     * Tenant and execution authority scope captured at checkpoint creation.
     *
     * @param tenantId verified tenant authority
     * @param organizationId verified organization authority
     * @param projectId verified project authority
     * @param environmentId non-production environment authority
     * @param actorId verified initiating workload or user identity
     */
    public record Scope(String tenantId, String organizationId, String projectId,
                        String environmentId, String actorId) {
        /** Rejects production and incomplete scopes at the durable control boundary. */
        public Scope {
            tenantId = requiredIdentifier(tenantId, "tenantId");
            organizationId = requiredIdentifier(organizationId, "organizationId");
            projectId = requiredIdentifier(projectId, "projectId");
            environmentId = normalized(environmentId).toLowerCase(Locale.ROOT);
            actorId = requiredIdentifier(actorId, "actorId");
            if (!Set.of("test", "staging").contains(environmentId)) {
                throw new IllegalArgumentException(
                        "Durable test checkpoints require a test or staging environment");
            }
        }
    }

    /**
     * Exact immutable inputs required to reconstruct and re-authorize the control plan.
     *
     * @param plan complete frozen execution-control plan
     * @param fixture content-addressed fixture revision
     * @param sideEffectPolicy fail-closed policy applied during durable execution
     * @param identitySnapshot payload-free identity-authority configuration identity
     * @param target exact graph/operator locator in v2, or null only for legacy v1 data
     */
    public record ControlDependencies(
            EffectiveExecutionPlan plan,
            ExactFixtureRef fixture,
            String sideEffectPolicy,
            AuthoritySnapshot identitySnapshot,
            @JsonInclude(JsonInclude.Include.NON_NULL) ExecutionTargetRef target
    ) {
        /** Enforces exact fixture/plan identity and fail-closed side-effect policy. */
        public ControlDependencies {
            Objects.requireNonNull(plan, "plan");
            Objects.requireNonNull(fixture, "fixture");
            Objects.requireNonNull(identitySnapshot, "identitySnapshot");
            sideEffectPolicy = normalized(sideEffectPolicy).toUpperCase(Locale.ROOT);
            if (!EffectiveExecutionPlan.SCHEMA_VERSION.equals(plan.schemaVersion())) {
                throw new IllegalArgumentException("Durable recovery requires the current effective plan");
            }
            requiredIdentifier(plan.planId(), "plan.planId");
            requiredIdentifier(plan.authorizedPurpose(), "plan.authorizedPurpose");
            if (!Set.of("GRAPH_CONTRACT_TEST", "OPERATOR_UNIT_TEST")
                    .contains(plan.authorizedPurpose())) {
                throw new IllegalArgumentException("Unsupported durable execution purpose");
            }
            requiredFingerprint(plan.planFingerprint(), "plan.planFingerprint");
            requiredFingerprint(plan.targetFingerprint(), "plan.targetFingerprint");
            requiredFingerprint(plan.fixtureBundleFingerprint(), "plan.fixtureBundleFingerprint");
            if (!plan.fixtureBundleFingerprint().equals(fixture.fingerprint())) {
                throw new IllegalArgumentException("Exact fixture reference must match the effective plan");
            }
            if (!Set.of("DENY_REAL", "REPLAY_ONLY").contains(sideEffectPolicy)) {
                throw new IllegalArgumentException("Unsupported durable side-effect policy");
            }
            if (target != null) {
                if (!plan.targetFingerprint().equals(target.fingerprint())) {
                    throw new IllegalArgumentException(
                            "Execution target fingerprint must match the effective plan");
                }
                String expectedKind = switch (plan.authorizedPurpose()) {
                    case "GRAPH_CONTRACT_TEST" -> "GRAPH";
                    case "OPERATOR_UNIT_TEST" -> "OPERATOR";
                    default -> throw new IllegalArgumentException(
                            "Unsupported durable execution purpose");
                };
                if (!expectedKind.equals(target.kind())) {
                    throw new IllegalArgumentException(
                            "Execution target kind does not match the authorized execution purpose");
                }
            }
        }

        /**
         * Reconstructs a legacy v1 dependency closure that predates exact target locators.
         *
         * <p>Only a v1 outer checkpoint may contain this shape. New checkpoint creation must use
         * the five-argument constructor and an exact {@link ExecutionTargetRef}.</p>
         *
         * @param plan complete frozen execution-control plan
         * @param fixture content-addressed fixture revision
         * @param sideEffectPolicy fail-closed policy applied during durable execution
         * @param identitySnapshot payload-free identity-authority configuration identity
         */
        public ControlDependencies(EffectiveExecutionPlan plan, ExactFixtureRef fixture,
                                   String sideEffectPolicy, AuthoritySnapshot identitySnapshot) {
            this(plan, fixture, sideEffectPolicy, identitySnapshot, null);
        }
    }

    /**
     * Exact locator and content identity of the graph or operator reconstructed during recovery.
     *
     * @param kind {@code GRAPH} or {@code OPERATOR}
     * @param id stable graph name or operator registry reference
     * @param fingerprint content identity that must equal the effective plan target fingerprint
     */
    public record ExecutionTargetRef(String kind, String id, String fingerprint) {
        /** Rejects unsupported kinds, ambiguous locators, and non-canonical content identities. */
        public ExecutionTargetRef {
            kind = normalized(kind).toUpperCase(Locale.ROOT);
            id = requiredIdentifier(id, "target id");
            fingerprint = requiredFingerprint(fingerprint, "target fingerprint");
            if (!Set.of("GRAPH", "OPERATOR").contains(kind)) {
                throw new IllegalArgumentException("Execution target kind must be GRAPH or OPERATOR");
            }
        }
    }

    /**
     * Content-addressed fixture revision; there is deliberately no latest alias.
     *
     * @param fixtureBundleId stable fixture bundle identity
     * @param revision positive immutable fixture revision
     * @param fingerprint canonical fixture content fingerprint
     */
    public record ExactFixtureRef(String fixtureBundleId, long revision, String fingerprint) {
        /** Validates immutable fixture identity. */
        public ExactFixtureRef {
            fixtureBundleId = requiredIdentifier(fixtureBundleId, "fixtureBundleId");
            fingerprint = requiredFingerprint(fingerprint, "fixture fingerprint");
            if (revision <= 0) {
                throw new IllegalArgumentException("Fixture revision must be positive");
            }
        }
    }

    /**
     * Payload-free identity authority mode and configuration snapshot.
     *
     * @param mode governed authority mode
     * @param fingerprint canonical non-payload authority configuration fingerprint
     */
    public record AuthoritySnapshot(String mode, String fingerprint) {
        /** Accepts only governed authority modes and their content identity. */
        public AuthoritySnapshot {
            mode = normalized(mode).toUpperCase(Locale.ROOT);
            fingerprint = requiredFingerprint(fingerprint, "identity snapshot fingerprint");
            if (!"FAIL_CLOSED".equals(mode)) {
                throw new IllegalArgumentException("Unsupported durable identity authority mode");
            }
        }
    }

    /**
     * Reference and digest of the engine-state closure written in the same transaction.
     *
     * @param checkpointRef content-addressed BLOGE checkpoint reference
     * @param nodeId node at whose durable boundary the closure was captured
     * @param boundaryType supported durable boundary category
     * @param boundarySequence positive monotonic boundary sequence
     * @param stateVersion non-negative BLOGE state version
     * @param closureFingerprint canonical fingerprint of the complete staged engine closure
     */
    public record EngineState(
            String checkpointRef,
            String nodeId,
            String boundaryType,
            long boundarySequence,
            long stateVersion,
            String closureFingerprint
    ) {
        /** Rejects ambiguous boundaries and non-content-addressed engine state. */
        public EngineState {
            checkpointRef = requiredIdentifier(checkpointRef, "checkpointRef");
            nodeId = requiredIdentifier(nodeId, "nodeId");
            boundaryType = normalized(boundaryType).toUpperCase(Locale.ROOT);
            closureFingerprint = requiredFingerprint(closureFingerprint, "engine-state closure fingerprint");
            if (!Set.of("NODE_BOUNDARY", "SUSPEND", "TIMER", "WORK_ITEM", "STREAM_OFFSET")
                    .contains(boundaryType)) {
                throw new IllegalArgumentException("Unsupported durable checkpoint boundary type");
            }
            if (boundarySequence <= 0 || stateVersion < 0) {
                throw new IllegalArgumentException("Engine boundary sequence/version is invalid");
            }
        }
    }

    /**
     * Fenced lifecycle facts used by optimistic compare-and-set.
     *
     * @param status durable control status
     * @param ownerId current process owner identity
     * @param leaseEpoch positive ownership fencing generation
     * @param revision non-negative checkpoint revision
     * @param createdAt immutable creation time
     * @param updatedAt latest committed transition time
     * @param leaseExpiresAt database-authority lease expiry
     */
    public record Lifecycle(
            Status status,
            String ownerId,
            long leaseEpoch,
            long revision,
            Instant createdAt,
            Instant updatedAt,
            Instant leaseExpiresAt
    ) {
        /** Rejects incomplete fences and temporally inconsistent checkpoints. */
        public Lifecycle {
            Objects.requireNonNull(status, "status");
            ownerId = requiredIdentifier(ownerId, "ownerId");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
            createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
            updatedAt = updatedAt.truncatedTo(ChronoUnit.MICROS);
            leaseExpiresAt = leaseExpiresAt.truncatedTo(ChronoUnit.MICROS);
            if (leaseEpoch <= 0 || revision < 0) {
                throw new IllegalArgumentException("Lease epoch must be positive and revision non-negative");
            }
            if (updatedAt.isBefore(createdAt) || leaseExpiresAt.isBefore(updatedAt)) {
                throw new IllegalArgumentException("Durable checkpoint lifecycle timestamps are inconsistent");
            }
        }
    }

    /** Durable execution lifecycle. Terminal states cannot be resumed. */
    public enum Status {
        /** Execution may advance under its current live owner. */
        ACTIVE,
        /** Execution is durably suspended at a restorable boundary. */
        SUSPENDED,
        /** An expired lease was fenced and a recovery owner is rebuilding control state. */
        RESUMING,
        /** Execution reached an immutable terminal outcome. */
        TERMINAL,
        /** Frozen dependencies or provider state can no longer be reconstructed exactly. */
        CONTROL_PLAN_UNAVAILABLE;

        /**
         * Determines whether an expired owner may enter the recovery protocol.
         *
         * @return whether exact provider and fixture state must support a later resume
         */
        public boolean resumable() {
            return this == ACTIVE || this == SUSPENDED || this == RESUMING;
        }
    }

    static String requiredFingerprint(String value, String field) {
        String result = normalized(value);
        if (!fingerprint(result)) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 fingerprint");
        }
        return result;
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static String requiredIdentifier(String value, String field) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " must be a bounded stable identifier");
        }
        return result;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
