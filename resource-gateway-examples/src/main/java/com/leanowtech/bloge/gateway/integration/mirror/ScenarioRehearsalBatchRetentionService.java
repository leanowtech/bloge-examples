package com.leanowtech.bloge.gateway.integration.mirror;

import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Objects;

/**
 * Protected governance facade for Scenario batch retention, multi-hold, and logical deletion.
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalBatchRetentionService {
    private final ScenarioRehearsalBatchRetentionRepository
            retention;
    private final MirrorOperationObservability observations;

    /**
     * @param retention signed batch retention and deletion authority
     * @param observations mandatory protected-operation audit
     */
    public ScenarioRehearsalBatchRetentionService(
            ScenarioRehearsalBatchRetentionRepository retention,
            MirrorOperationObservability observations) {
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.observations = Objects.requireNonNull(
                observations, "observations");
    }

    /** Reads and verifies one exact retention projection and latest signed event. */
    public ScenarioRehearsalBatchRetentionState find(
            String jobId,
            IntegrationRequestContext identity) {
        String id = canonicalJobId(jobId, identity);
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_RETENTION_READ,
                        identity, "", "", id);
        try {
            ScenarioRehearsalBatchRetentionState state =
                    retention.find(
                                    MirrorPlanIntegrationService
                                            .requireMirrorRetentionReadIdentity(
                                                    identity),
                                    id)
                            .orElseThrow(() ->
                                    new IntegrationProblemException(
                                            IntegrationProblem.notFound(
                                                    "RG.MIRROR.REHEARSAL_BATCH.RETENTION_NOT_FOUND",
                                                    "Scenario batch retention state was not found in the authorized scope.",
                                                    identity.correlationId(),
                                                    Map.of())));
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    classify(failure, identity));
        }
    }

    /** Places one independent legal hold under an idempotent command. */
    @Transactional
    public ScenarioRehearsalBatchRetentionState placeHold(
            String jobId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity) {
        return hold(jobId, command, identity, true);
    }

    /** Releases one exact legal hold without changing any other hold. */
    @Transactional
    public ScenarioRehearsalBatchRetentionState releaseHold(
            String jobId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity) {
        return hold(jobId, command, identity, false);
    }

    /** Purges one eligible batch aggregate and returns its signed logical-deletion proof. */
    @Transactional
    public ScenarioRehearsalBatchRetentionState purge(
            String jobId,
            ScenarioRehearsalPurgeCommand command,
            IntegrationRequestContext identity) {
        String id = canonicalJobId(jobId, identity);
        ScenarioRehearsalPurgeCommand exact =
                Objects.requireNonNull(command, "command");
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_EVIDENCE_PURGE,
                        identity, exact.commandId(), "", id);
        try {
            ScenarioRehearsalBatchRetentionState state =
                    retention.purge(
                            MirrorPlanIntegrationService
                                    .requireMirrorRetentionAdminIdentity(
                                            identity),
                            id,
                            exact.commandId(),
                            identity.actorId(),
                            exact.reasonCode());
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    classify(failure, identity));
        }
    }

    private ScenarioRehearsalBatchRetentionState hold(
            String jobId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity,
            boolean place) {
        String id = canonicalJobId(jobId, identity);
        ScenarioRehearsalLegalHoldCommand exact =
                Objects.requireNonNull(command, "command");
        MirrorOperationObservability.Observation observation =
                observations.start(
                        place
                                ? MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_HOLD_PLACE
                                : MirrorOperationAuditEvent.Operation
                                .SCENARIO_REHEARSAL_BATCH_HOLD_RELEASE,
                        identity, exact.commandId(), "", id);
        try {
            CapabilitySnapshot.Scope scope =
                    MirrorPlanIntegrationService
                            .requireMirrorLegalHoldIdentity(identity);
            ScenarioRehearsalBatchRetentionState state =
                    place
                            ? retention.placeHold(
                            scope, id, exact.commandId(),
                            exact.holdId(), identity.actorId(),
                            exact.reasonCode())
                            : retention.releaseHold(
                            scope, id, exact.commandId(),
                            exact.holdId(), identity.actorId(),
                            exact.reasonCode());
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    classify(failure, identity));
        }
    }

    private static RuntimeException classify(
            RuntimeException failure,
            IntegrationRequestContext identity) {
        if (failure instanceof IntegrationProblemException expected) {
            return expected;
        }
        String message = failure.getMessage() == null
                ? "" : failure.getMessage();
        if (failure instanceof IllegalArgumentException) {
            return new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL_BATCH.RETENTION_REQUEST_INVALID",
                            "Scenario batch retention command is invalid.",
                            identity.correlationId(), Map.of()));
        }
        if (message.contains("hold")
                || message.contains("retention")
                || message.contains("elapsed")
                || message.contains("different semantics")
                || message.contains("cannot change")) {
            return new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REHEARSAL_BATCH.RETENTION_CONFLICT",
                            "Scenario batch retention state rejects this transition.",
                            identity.correlationId(), Map.of()));
        }
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.REHEARSAL_BATCH.RETENTION_UNAVAILABLE",
                        "Scenario batch retention authority is unavailable.",
                        identity.correlationId(), Map.of()));
    }

    private static String canonicalJobId(
            String jobId,
            IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        String id = jobId == null ? "" : jobId.trim();
        if (!ScenarioRehearsalBatchIdentity
                .hasCanonicalShape(id)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL_BATCH.JOB_ID_INVALID",
                            "Scenario rehearsal batch job id is invalid.",
                            identity.correlationId(), Map.of()));
        }
        return id;
    }
}
