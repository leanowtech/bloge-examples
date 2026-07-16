package com.leanowtech.bloge.gateway.testing.domain;

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
    /** Current composite durable-test checkpoint protocol. */
    public static final String SCHEMA_VERSION = "bloge.durableTestExecutionCheckpoint.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Verifies cross-component identity closure before a checkpoint can be sealed. */
    public DurableTestExecutionCheckpoint {
        schemaVersion = normalized(schemaVersion);
        runId = requiredIdentifier(runId, "runId");
        engineExecutionId = requiredIdentifier(engineExecutionId, "engineExecutionId");
        checkpointFingerprint = normalized(checkpointFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("Unsupported durable test checkpoint version");
        }
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(dependencies, "dependencies");
        Objects.requireNonNull(fixtureConsumptionState, "fixtureConsumptionState");
        Objects.requireNonNull(executionServiceState, "executionServiceState");
        Objects.requireNonNull(engineState, "engineState");
        Objects.requireNonNull(lifecycle, "lifecycle");
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

    /** @return canonical material covered by {@link #checkpointFingerprint()} */
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

    /** @return an immutable copy carrying the supplied composite fingerprint */
    public DurableTestExecutionCheckpoint withCheckpointFingerprint(String fingerprint) {
        return new DurableTestExecutionCheckpoint(schemaVersion, scope, runId, engineExecutionId,
                dependencies, fixtureConsumptionState, executionServiceState, engineState,
                lifecycle, fingerprint);
    }

    /** @return an immutable copy with a sealed fixture-consumption state */
    public DurableTestExecutionCheckpoint withFixtureConsumptionState(
            FixtureConsumptionStateSnapshot state) {
        return new DurableTestExecutionCheckpoint(schemaVersion, scope, runId, engineExecutionId,
                dependencies, state, executionServiceState, engineState, lifecycle,
                checkpointFingerprint);
    }

    /** Tenant and execution authority scope captured at checkpoint creation. */
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

    /** Exact immutable inputs required to reconstruct and re-authorize the control plan. */
    public record ControlDependencies(
            EffectiveExecutionPlan plan,
            ExactFixtureRef fixture,
            String sideEffectPolicy,
            AuthoritySnapshot identitySnapshot
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
        }
    }

    /** Content-addressed fixture revision; there is deliberately no latest alias. */
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

    /** Payload-free identity authority mode and configuration snapshot. */
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

    /** Reference and digest of the engine-state closure written in the same transaction. */
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

    /** Fenced lifecycle facts used by optimistic compare-and-set. */
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
        ACTIVE,
        SUSPENDED,
        RESUMING,
        TERMINAL,
        CONTROL_PLAN_UNAVAILABLE;

        /** @return whether exact provider and fixture state must support a later resume */
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
