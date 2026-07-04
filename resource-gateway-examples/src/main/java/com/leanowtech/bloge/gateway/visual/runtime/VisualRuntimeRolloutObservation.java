package com.leanowtech.bloge.gateway.visual.runtime;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Persisted runtime-plane rollout execution observation for one adapter activation.
 *
 * <p>A rollout observation is an auditable fact from the runtime plane. It records
 * canary/ramp/rollback execution state without mutating the operator definition
 * and without by itself making an operator executable.</p>
 *
 * @param schemaVersion observation record contract version
 * @param observationId stable observation id
 * @param revision monotonically increasing record revision
 * @param state in-progress, healthy, degraded, failed, rolled-back, or completed
 * @param level UI/control-plane severity derived from state
 * @param activationId adapter activation id being observed
 * @param activationRevision adapter activation revision observed by the runtime rollout system
 * @param bindingId implementation binding id
 * @param bindingRevision implementation binding revision
 * @param operatorRef operator being rolled out
 * @param operatorFingerprint operator fingerprint observed at rollout time
 * @param adapterKind runtime adapter kind
 * @param runtimeEnvironment concrete runtime environment
 * @param rolloutStrategy planned rollout strategy being executed
 * @param trafficPercent observed traffic percentage for this rollout step
 * @param rolloutPhase rollout phase label from the runtime system
 * @param rollbackTriggered whether this observation says rollback was triggered
 * @param rollbackSignal observed metric, alert, SLO, or manual signal that triggered rollback
 * @param rolloutSignals structured rollout guardrail signals observed by the runtime system
 * @param observedBy principal or service that emitted the observation
 * @param changeSource source system or workflow
 * @param reason human-readable observation reason
 * @param evidence external rollout evidence
 * @param observedAt timestamp from the runtime observation source
 * @param createdAt first persistence timestamp
 * @param updatedAt latest persistence timestamp
 */
public record VisualRuntimeRolloutObservation(
        String schemaVersion,
        String observationId,
        long revision,
        String state,
        String level,
        String activationId,
        long activationRevision,
        String bindingId,
        long bindingRevision,
        String operatorRef,
        String operatorFingerprint,
        String adapterKind,
        String runtimeEnvironment,
        String rolloutStrategy,
        int trafficPercent,
        String rolloutPhase,
        boolean rollbackTriggered,
        String rollbackSignal,
        List<RolloutSignal> rolloutSignals,
        String observedBy,
        String changeSource,
        String reason,
        List<Evidence> evidence,
        Instant observedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String SCHEMA_VERSION = "bloge.visualRuntimeRolloutObservation.v1";
    public static final String STATE_IN_PROGRESS = "in-progress";
    public static final String STATE_HEALTHY = "healthy";
    public static final String STATE_DEGRADED = "degraded";
    public static final String STATE_FAILED = "failed";
    public static final String STATE_ROLLED_BACK = "rolled-back";
    public static final String STATE_COMPLETED = "completed";

    /**
     * Creates a normalized rollout observation record.
     */
    public VisualRuntimeRolloutObservation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        observationId = observationId == null ? "" : observationId.trim();
        revision = Math.max(0, revision);
        state = normalizeState(state, STATE_IN_PROGRESS);
        level = level == null || level.isBlank() ? levelForState(state) : level.trim().toLowerCase(Locale.ROOT);
        activationId = activationId == null ? "" : activationId.trim();
        activationRevision = Math.max(0, activationRevision);
        bindingId = bindingId == null ? "" : bindingId.trim();
        bindingRevision = Math.max(0, bindingRevision);
        operatorRef = operatorRef == null ? "" : operatorRef.trim();
        operatorFingerprint = operatorFingerprint == null ? "" : operatorFingerprint.trim();
        adapterKind = adapterKind == null ? "" : adapterKind.trim().toLowerCase(Locale.ROOT);
        runtimeEnvironment = runtimeEnvironment == null ? "" : runtimeEnvironment.trim();
        rolloutStrategy = normalizeState(rolloutStrategy, "");
        trafficPercent = Math.max(0, Math.min(100, trafficPercent));
        rolloutPhase = normalizeState(rolloutPhase, "");
        rollbackSignal = rollbackSignal == null ? "" : rollbackSignal.trim();
        rolloutSignals = rolloutSignals == null ? List.of() : List.copyOf(rolloutSignals);
        observedBy = observedBy == null ? "" : observedBy.trim();
        changeSource = changeSource == null ? "" : changeSource.trim();
        reason = reason == null ? "" : reason.trim();
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        observedAt = observedAt == null ? Instant.EPOCH : observedAt;
        createdAt = createdAt == null ? Instant.EPOCH : createdAt;
        updatedAt = updatedAt == null ? createdAt : updatedAt;
    }

    /**
     * Structured guardrail signal attached to a rollout observation.
     *
     * @param name stable signal name such as p95-latency, error-rate, or golden-regression
     * @param kind signal category such as metric, slo, alert, golden, or manual
     * @param observedValue optional observed numeric value
     * @param threshold optional threshold numeric value
     * @param comparator comparison operator such as <=, >=, lt, gt, or eq
     * @param unit optional measurement unit
     * @param breached true when the signal breached its guardrail
     * @param summary human-readable signal summary
     */
    public record RolloutSignal(String name,
                                String kind,
                                Double observedValue,
                                Double threshold,
                                String comparator,
                                String unit,
                                boolean breached,
                                String summary) {
        public RolloutSignal {
            name = normalizeState(name, "");
            kind = normalizeState(kind, "");
            comparator = comparator == null ? "" : comparator.trim().toLowerCase(Locale.ROOT);
            unit = unit == null ? "" : unit.trim();
            summary = summary == null ? "" : summary.trim();
        }
    }

    /**
     * External proof attached to a rollout observation.
     *
     * @param kind evidence kind such as canary-metric, deployment-event, rollback-event, or approval
     * @param ref external evidence identifier or URL
     * @param summary human-readable evidence summary
     */
    public record Evidence(String kind, String ref, String summary) {
        public Evidence {
            kind = kind == null ? "" : kind.trim().toLowerCase(Locale.ROOT);
            ref = ref == null ? "" : ref.trim();
            summary = summary == null ? "" : summary.trim();
        }
    }

    /**
     * Creates an observation fact from a successful validation result.
     *
     * @param request submitted rollout observation request
     * @param validation validation snapshot
     * @return observation record without repository-assigned timestamps
     */
    public static VisualRuntimeRolloutObservation from(VisualRuntimeRolloutObservationValidation.Request request,
                                                       VisualRuntimeRolloutObservationValidation validation) {
        return new VisualRuntimeRolloutObservation(
                SCHEMA_VERSION,
                request == null ? "" : request.observationId(),
                0,
                validation == null ? STATE_IN_PROGRESS : validation.observationState(),
                validation == null ? "info" : validation.level(),
                validation == null ? "" : validation.activationId(),
                validation == null ? 0 : validation.activationRevision(),
                validation == null ? "" : validation.bindingId(),
                validation == null ? 0 : validation.bindingRevision(),
                validation == null ? "" : validation.operatorRef(),
                validation == null ? "" : validation.operatorFingerprint(),
                validation == null ? "" : validation.adapterKind(),
                validation == null ? "" : validation.runtimeEnvironment(),
                validation == null ? "" : validation.rolloutStrategy(),
                validation == null ? 0 : validation.trafficPercent(),
                validation == null ? "" : validation.rolloutPhase(),
                validation != null && validation.rollbackTriggered(),
                validation == null ? "" : validation.rollbackSignal(),
                validation == null ? List.of() : validation.rolloutSignals(),
                validation == null ? "" : validation.observedBy(),
                request == null ? "" : request.changeSource(),
                validation == null ? "" : validation.reason(),
                request == null ? List.of() : request.evidence(),
                request == null ? Instant.EPOCH : request.observedAt(),
                Instant.EPOCH,
                Instant.EPOCH
        );
    }

    /**
     * Assigns repository identity and timestamps.
     *
     * @param id observation id
     * @param nextRevision record revision
     * @param created timestamp
     * @param updated timestamp
     * @return observation record with identity
     */
    public VisualRuntimeRolloutObservation withIdentity(String id,
                                                        long nextRevision,
                                                        Instant created,
                                                        Instant updated) {
        return new VisualRuntimeRolloutObservation(
                schemaVersion,
                id,
                nextRevision,
                state,
                level,
                activationId,
                activationRevision,
                bindingId,
                bindingRevision,
                operatorRef,
                operatorFingerprint,
                adapterKind,
                runtimeEnvironment,
                rolloutStrategy,
                trafficPercent,
                rolloutPhase,
                rollbackTriggered,
                rollbackSignal,
                rolloutSignals,
                observedBy,
                changeSource,
                reason,
                evidence,
                observedAt,
                created,
                updated
        );
    }

    static String levelForState(String state) {
        return switch (normalizeState(state, STATE_IN_PROGRESS)) {
            case STATE_HEALTHY, STATE_COMPLETED -> "success";
            case STATE_DEGRADED -> "warning";
            case STATE_FAILED, STATE_ROLLED_BACK -> "error";
            default -> "info";
        };
    }

    static String normalizeState(String value, String fallback) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        return normalized.isBlank() ? fallback : normalized;
    }
}
