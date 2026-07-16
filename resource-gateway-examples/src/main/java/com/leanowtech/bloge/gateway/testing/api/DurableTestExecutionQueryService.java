package com.leanowtech.bloge.gateway.testing.api;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Authenticated, non-disclosing read model for durable test execution control state.
 *
 * <p>The repository remains the integrity authority. This service adds organization/project scope
 * checks and projects only payload-free operational facts. Missing runs and runs outside the
 * verified organization or project deliberately produce the same not-found response.</p>
 */
public final class DurableTestExecutionQueryService {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Set<String> ENABLED_ENVIRONMENTS = Set.of("test", "staging");

    private final DurableTestExecutionCheckpointRepository checkpoints;

    /**
     * Creates the durable execution read boundary.
     *
     * @param checkpoints scoped, integrity-verifying checkpoint repository
     */
    public DurableTestExecutionQueryService(
            DurableTestExecutionCheckpointRepository checkpoints) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    }

    /**
     * Resolves one durable run in the verified caller scope.
     *
     * @param runId path-bound durable run identity
     * @param identity verified non-production integration identity
     * @return payload-free lifecycle and content-identity projection
     */
    public DurableTestExecutionQueryResponse find(
            String runId, IntegrationRequestContext identity) {
        requireIdentity(identity);
        String normalizedRunId = normalized(runId);
        if (!IDENTIFIER.matcher(normalizedRunId).matches()) {
            throw new IntegrationProblemException(IntegrationProblem.badRequest(
                    "RG.TEST.DURABLE_RUN_ID_INVALID",
                    "Durable run id must be a bounded stable identifier.",
                    identity.correlationId(), Map.of()));
        }

        DurableTestExecutionCheckpoint checkpoint;
        try {
            checkpoint = checkpoints.find(
                    identity.tenantId(), identity.environmentId(), normalizedRunId)
                    .orElse(null);
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw unavailable(identity);
        }
        if (checkpoint == null || !identity.organizationId().equals(
                checkpoint.scope().organizationId())
                || !identity.projectId().equals(checkpoint.scope().projectId())) {
            throw notFound(identity);
        }
        try {
            return project(checkpoint);
        } catch (RuntimeException corrupt) {
            throw unavailable(identity);
        }
    }

    static DurableTestExecutionQueryResponse project(
            DurableTestExecutionCheckpoint checkpoint) {
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                checkpoint.dependencies();
        DurableTestExecutionCheckpoint.Lifecycle lifecycle = checkpoint.lifecycle();
        DurableTestExecutionCheckpoint.ExecutionTargetRef targetRef = dependencies.target();
        boolean migrationRequired = !DurableTestExecutionCheckpoint.SCHEMA_VERSION.equals(
                checkpoint.schemaVersion()) || targetRef == null;
        boolean recoverable = !migrationRequired
                && lifecycle.status().resumable()
                && checkpoint.executionServiceState().restorable();
        DurableTestExecutionCheckpoint.ExactFixtureRef fixture = dependencies.fixture();
        DurableTestExecutionCheckpoint.EngineState engine = checkpoint.engineState();
        return new DurableTestExecutionQueryResponse(
                "", checkpoint.runId(), checkpoint.engineExecutionId(),
                lifecycle.status().name(),
                new DurableTestExecutionQueryResponse.Fence(
                        lifecycle.ownerId(), lifecycle.leaseEpoch(), lifecycle.revision()),
                lifecycle.leaseExpiresAt(),
                targetRef == null ? null : new DurableTestExecutionQueryResponse.Target(
                        targetRef.kind(), targetRef.id(), targetRef.fingerprint()),
                new DurableTestExecutionQueryResponse.Fixture(
                        fixture.fixtureBundleId(), fixture.revision(), fixture.fingerprint()),
                dependencies.plan().authorizedPurpose(), dependencies.sideEffectPolicy(),
                dependencies.plan().planFingerprint(),
                checkpoint.executionServiceState().snapshotFingerprint(),
                checkpoint.fixtureConsumptionState().stateFingerprint(),
                new DurableTestExecutionQueryResponse.EngineBoundary(
                        engine.checkpointRef(), engine.nodeId(), engine.boundaryType(),
                        engine.boundarySequence(), engine.stateVersion(),
                        engine.closureFingerprint()),
                checkpoint.checkpointFingerprint(), lifecycle.createdAt(), lifecycle.updatedAt(),
                recoverable, migrationRequired);
    }

    private static void requireIdentity(IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity").requireComplete();
        if (!ENABLED_ENVIRONMENTS.contains(identity.environmentId())) {
            throw new IntegrationProblemException(IntegrationProblem.forbidden(
                    "RG.TEST.DURABLE_ENVIRONMENT_FORBIDDEN",
                    "Durable test execution state is unavailable in this environment.",
                    identity.correlationId(), Map.of()));
        }
    }

    private static IntegrationProblemException notFound(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.notFound(
                "RG.TEST.DURABLE_EXECUTION_NOT_FOUND",
                "Durable test execution was not found in the authorized scope.",
                identity.correlationId(), Map.of()));
    }

    private static IntegrationProblemException unavailable(IntegrationRequestContext identity) {
        return new IntegrationProblemException(IntegrationProblem.serviceUnavailable(
                "RG.TEST.DURABLE_STORE_UNAVAILABLE",
                "The isolated durable test control store is unavailable.",
                identity.correlationId(), Map.of()));
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
