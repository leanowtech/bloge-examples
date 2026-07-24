package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free durable fact for one committed Scenario aggregate transition.
 *
 * <p>The event contains only full enterprise scope, immutable artifact coordinates, lease
 * fencing coordinates, cursors, stable failure codes, and content addresses. TestSuite input,
 * fixture values, graph context, node input/output, replay payload, exception text, and stack
 * traces are not representable.</p>
 *
 * @param sequence database-assigned append sequence, or zero before persistence
 * @param occurredAt database-authoritative commit time, or {@code null} before persistence
 * @param scope complete enterprise scope
 * @param requestId aggregate idempotency identity
 * @param compiledPlanRef exact compiler-issued plan
 * @param runId stable aggregate run identity
 * @param transition committed lifecycle transition
 * @param leaseOwner opaque worker-attempt identity
 * @param leaseEpoch monotonic fencing epoch
 * @param totalCases immutable aggregate case closure
 * @param caseIndex checkpointed case index, or {@code -1}
 * @param nextCaseIndex first case not durably checkpointed
 * @param resultFingerprint checkpointed case content address, otherwise blank
 * @param evidenceBundleFingerprint terminal evidence content address, otherwise blank
 * @param reasonCode stable release reason, otherwise blank
 */
public record ScenarioRehearsalLifecycleAuditEvent(
        long sequence,
        Instant occurredAt,
        CapabilitySnapshot.Scope scope,
        String requestId,
        MirrorArtifactRef compiledPlanRef,
        String runId,
        Transition transition,
        String leaseOwner,
        long leaseEpoch,
        int totalCases,
        int caseIndex,
        int nextCaseIndex,
        String resultFingerprint,
        String evidenceBundleFingerprint,
        String reasonCode
) {
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern REASON_CODE =
            Pattern.compile("RG\\.MIRROR\\.[A-Z0-9_.-]{1,224}");

    /** Committed aggregate transitions with stable governance semantics. */
    public enum Transition {
        /** First immutable request registration and epoch-one authority grant. */
        CLAIMED,
        /** Expired or explicitly released authority replaced by the next epoch. */
        TAKEN_OVER,
        /** One content-addressed case appended and the cursor advanced. */
        CHECKPOINTED,
        /** Current authority relinquished for immediate retry. */
        RELEASED,
        /** Complete progress bound to one signed evidence bundle. */
        COMPLETED
    }

    /** Enforces the closed payload-free lifecycle contract. */
    public ScenarioRehearsalLifecycleAuditEvent {
        if (sequence < 0) {
            throw new IllegalArgumentException(
                    "Scenario lifecycle audit sequence must be non-negative");
        }
        if (sequence > 0 && occurredAt == null) {
            throw new IllegalArgumentException(
                    "Persisted Scenario lifecycle audit requires database time");
        }
        scope = Objects.requireNonNull(scope, "scope");
        requestId = bounded(requestId, 256, "requestId");
        if (compiledPlanRef == null
                || !"COMPILED_REHEARSAL_PLAN".equals(
                compiledPlanRef.kind())) {
            throw new IllegalArgumentException(
                    "compiledPlanRef must identify a compiled rehearsal plan");
        }
        runId = bounded(runId, 512, "runId");
        if (!ScenarioRehearsalRunIdentity.hasCanonicalShape(runId)) {
            throw new IllegalArgumentException(
                    "runId must be a canonical Scenario rehearsal identity");
        }
        transition = Objects.requireNonNull(
                transition, "transition");
        leaseOwner = bounded(leaseOwner, 512, "leaseOwner");
        if (leaseEpoch < 1) {
            throw new IllegalArgumentException(
                    "leaseEpoch must be positive");
        }
        if (totalCases < 1
                || totalCases > ScenarioPack.MAXIMUM_CASES) {
            throw new IllegalArgumentException(
                    "totalCases must be Scenario policy bounded");
        }
        if (caseIndex < -1 || caseIndex >= totalCases) {
            throw new IllegalArgumentException(
                    "caseIndex is outside the aggregate closure");
        }
        if (nextCaseIndex < 0 || nextCaseIndex > totalCases) {
            throw new IllegalArgumentException(
                    "nextCaseIndex is outside the aggregate closure");
        }
        resultFingerprint = fingerprint(
                resultFingerprint, "resultFingerprint");
        evidenceBundleFingerprint = fingerprint(
                evidenceBundleFingerprint,
                "evidenceBundleFingerprint");
        reasonCode = normalized(reasonCode);
        if (!reasonCode.isBlank()
                && !REASON_CODE.matcher(reasonCode).matches()) {
            throw new IllegalArgumentException(
                    "reasonCode must be a stable Mirror code");
        }
        requireTransitionShape(
                transition, totalCases, caseIndex, nextCaseIndex,
                resultFingerprint, evidenceBundleFingerprint,
                reasonCode);
    }

    /**
     * Returns this fact with database-assigned persistence coordinates.
     *
     * @param assignedSequence positive append sequence
     * @param databaseTime database-authoritative occurrence time
     * @return immutable persisted event
     */
    public ScenarioRehearsalLifecycleAuditEvent persisted(
            long assignedSequence, Instant databaseTime) {
        if (assignedSequence < 1 || databaseTime == null) {
            throw new IllegalArgumentException(
                    "Scenario lifecycle persistence coordinates are required");
        }
        return new ScenarioRehearsalLifecycleAuditEvent(
                assignedSequence, databaseTime, scope, requestId,
                compiledPlanRef, runId, transition, leaseOwner,
                leaseEpoch, totalCases, caseIndex, nextCaseIndex,
                resultFingerprint, evidenceBundleFingerprint,
                reasonCode);
    }

    private static void requireTransitionShape(
            Transition transition,
            int totalCases,
            int caseIndex,
            int nextCaseIndex,
            String resultFingerprint,
            String evidenceFingerprint,
            String reasonCode) {
        if (transition == Transition.CHECKPOINTED) {
            if (caseIndex < 0
                    || nextCaseIndex != caseIndex + 1
                    || resultFingerprint.isBlank()
                    || !evidenceFingerprint.isBlank()
                    || !reasonCode.isBlank()) {
                throw new IllegalArgumentException(
                        "checkpoint audit coordinates are inconsistent");
            }
            return;
        }
        if (caseIndex != -1 || !resultFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "only checkpoint audit may identify a case result");
        }
        if (transition == Transition.COMPLETED) {
            if (nextCaseIndex != totalCases
                    || evidenceFingerprint.isBlank()
                    || !reasonCode.isBlank()) {
                throw new IllegalArgumentException(
                        "completed audit coordinates are inconsistent");
            }
            return;
        }
        if (!evidenceFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "only completed audit may identify evidence");
        }
        if ((transition == Transition.RELEASED)
                != !reasonCode.isBlank()) {
            throw new IllegalArgumentException(
                    "only released audit requires a reason code");
        }
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = normalized(value);
        if (!normalized.isBlank()
                && !FINGERPRINT.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " must be canonical SHA-256");
        }
        return normalized;
    }

    private static String bounded(
            String value, int maximum, String field) {
        String normalized = normalized(value);
        if (normalized.isBlank() || normalized.length() > maximum) {
            throw new IllegalArgumentException(
                    field + " is blank or exceeds its bound");
        }
        return normalized;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
