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
 * Protected governance facade for Scenario retention, multi-hold, and deletion-proof operations.
 */
@Service
@Profile("!production & (test | staging)")
@ConditionalOnProperty(
        prefix = "gateway.testing.mirror",
        name = "enabled",
        havingValue = "true")
public class ScenarioRehearsalRetentionService {
    private final ScenarioRehearsalRetentionRepository retention;
    private final MirrorOperationObservability observations;

    /**
     * @param retention signed retention and deletion-proof authority
     * @param observations mandatory protected-operation audit
     */
    public ScenarioRehearsalRetentionService(
            ScenarioRehearsalRetentionRepository retention,
            MirrorOperationObservability observations) {
        this.retention = Objects.requireNonNull(
                retention, "retention");
        this.observations = Objects.requireNonNull(
                observations, "observations");
    }

    /** Reads and verifies one exact retention projection and signed latest event. */
    public ScenarioRehearsalRetentionState find(
            String runId, IntegrationRequestContext identity) {
        String id = canonicalRunId(runId, identity);
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_RETENTION_READ,
                        identity, "", "", id);
        try {
            ScenarioRehearsalRetentionState state =
                    requireState(id, identity);
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(failure);
        }
    }

    /** Places one independent legal hold under an idempotent command. */
    @Transactional
    public ScenarioRehearsalRetentionState placeHold(
            String runId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity) {
        return hold(
                runId, command, identity, true);
    }

    /** Releases one exact legal hold without changing any other hold. */
    @Transactional
    public ScenarioRehearsalRetentionState releaseHold(
            String runId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity) {
        return hold(
                runId, command, identity, false);
    }

    /** Purges an eligible aggregate and returns its signed deletion proof projection. */
    @Transactional
    public ScenarioRehearsalRetentionState purge(
            String runId,
            ScenarioRehearsalPurgeCommand command,
            IntegrationRequestContext identity) {
        String id = canonicalRunId(runId, identity);
        ScenarioRehearsalPurgeCommand exact =
                Objects.requireNonNull(command, "command");
        MirrorOperationObservability.Observation observation =
                observations.start(
                        MirrorOperationAuditEvent.Operation
                                .SCENARIO_EVIDENCE_PURGE,
                        identity, exact.commandId(), "", id);
        try {
            ScenarioRehearsalRetentionState state =
                    retention.purge(
                            scope(identity), id,
                            exact.commandId(), identity.actorId(),
                            exact.reasonCode());
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    classify(failure, identity));
        }
    }

    private ScenarioRehearsalRetentionState hold(
            String runId,
            ScenarioRehearsalLegalHoldCommand command,
            IntegrationRequestContext identity,
            boolean place) {
        String id = canonicalRunId(runId, identity);
        ScenarioRehearsalLegalHoldCommand exact =
                Objects.requireNonNull(command, "command");
        MirrorOperationObservability.Observation observation =
                observations.start(
                        place
                                ? MirrorOperationAuditEvent.Operation
                                .SCENARIO_HOLD_PLACE
                                : MirrorOperationAuditEvent.Operation
                                .SCENARIO_HOLD_RELEASE,
                        identity, exact.commandId(), "", id);
        try {
            ScenarioRehearsalRetentionState state =
                    place
                            ? retention.placeHold(
                            scope(identity), id,
                            exact.commandId(), exact.holdId(),
                            identity.actorId(),
                            exact.reasonCode())
                            : retention.releaseHold(
                            scope(identity), id,
                            exact.commandId(), exact.holdId(),
                            identity.actorId(),
                            exact.reasonCode());
            observation.succeeded(id);
            return state;
        } catch (RuntimeException failure) {
            throw observation.failed(
                    classify(failure, identity));
        }
    }

    private ScenarioRehearsalRetentionState requireState(
            String runId, IntegrationRequestContext identity) {
        try {
            return retention.find(
                            scope(identity), runId)
                    .orElseThrow(() ->
                            new IntegrationProblemException(
                                    IntegrationProblem.notFound(
                                            "RG.MIRROR.REHEARSAL.RETENTION_NOT_FOUND",
                                            "Scenario rehearsal retention state was not found in the authorized scope.",
                                            identity.correlationId(),
                                            Map.of())));
        } catch (IntegrationProblemException expected) {
            throw expected;
        } catch (RuntimeException unavailable) {
            throw classify(unavailable, identity);
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
                            "RG.MIRROR.REHEARSAL.RETENTION_REQUEST_INVALID",
                            "Scenario retention command is invalid.",
                            identity.correlationId(), Map.of()));
        }
        if (message.contains("hold")
                || message.contains("retention")
                || message.contains("elapsed")
                || message.contains("different semantics")
                || message.contains("cannot change")) {
            return new IntegrationProblemException(
                    IntegrationProblem.conflict(
                            "RG.MIRROR.REHEARSAL.RETENTION_CONFLICT",
                            "Scenario retention state rejects this transition.",
                            identity.correlationId(), Map.of()));
        }
        return new IntegrationProblemException(
                IntegrationProblem.serviceUnavailable(
                        "RG.MIRROR.REHEARSAL.RETENTION_UNAVAILABLE",
                        "Scenario retention authority is unavailable.",
                        identity.correlationId(), Map.of()));
    }

    private static String canonicalRunId(
            String runId, IntegrationRequestContext identity) {
        Objects.requireNonNull(identity, "identity");
        String id = runId == null ? "" : runId.trim();
        if (!ScenarioRehearsalRunIdentity
                .hasCanonicalShape(id)) {
            throw new IntegrationProblemException(
                    IntegrationProblem.badRequest(
                            "RG.MIRROR.REHEARSAL.RUN_ID_INVALID",
                            "Scenario rehearsal run id is invalid.",
                            identity.correlationId(), Map.of()));
        }
        return id;
    }

    private static CapabilitySnapshot.Scope scope(
            IntegrationRequestContext identity) {
        return MirrorPlanIntegrationService
                .requireMirrorIdentity(identity);
    }
}
