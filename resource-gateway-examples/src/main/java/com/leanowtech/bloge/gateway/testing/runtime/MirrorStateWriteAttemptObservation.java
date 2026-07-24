package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateTransitionRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidenceIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;

import java.time.Instant;
import java.util.Objects;

/**
 * Payload-free in-process projection of one terminal Session write delegate attempt.
 *
 * <p>The observation is created while the exact pre-attempt state head is still available. A
 * committed or replayed attempt carries the complete transition projection. A failed attempt
 * carries only stable classification and a canonical failure fingerprint. Unknown commit outcomes
 * deliberately retain the pre-attempt head and never claim that the durable state stayed
 * unchanged.</p>
 *
 * @param writeEffectRef exact write effect selected by the resolver
 * @param observedStateRef exact in-run state head observed before the attempt
 * @param observedStateRevision committed revision observed before the attempt
 * @param observedWorldFingerprint business-world identity observed before the attempt
 * @param observedLogicalClock deterministic Session time observed before the attempt
 * @param outcome conservative terminal write outcome
 * @param stage last trustworthy processing stage
 * @param stateDisposition proven state-head effect
 * @param retryable whether another governed delegate attempt is allowed
 * @param errorCode stable failure code; blank for success
 * @param errorType normalized failure family; blank for success
 * @param failureFingerprint canonical failure identity; blank for success
 * @param transition exact committed or replayed transition closure
 */
public record MirrorStateWriteAttemptObservation(
        MirrorArtifactRef writeEffectRef,
        MirrorArtifactRef observedStateRef,
        long observedStateRevision,
        String observedWorldFingerprint,
        Instant observedLogicalClock,
        MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
        MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
        MirrorStateWriteOutcomeRunEvidence.StateDisposition
                stateDisposition,
        boolean retryable,
        String errorCode,
        String errorType,
        String failureFingerprint,
        MirrorStateTransitionObservation transition
) {
    /** Validates one already projected write-attempt outcome. */
    public MirrorStateWriteAttemptObservation {
        writeEffectRef = kind(
                writeEffectRef, "WRITE_EFFECT",
                "writeEffectRef");
        observedStateRef = kind(
                observedStateRef, "SESSION_STATE",
                "observedStateRef");
        if (observedStateRevision < 0
                || observedStateRef.revision()
                != Math.addExact(
                observedStateRevision, 1)) {
            throw new IllegalArgumentException(
                    "observed state reference revision is inconsistent");
        }
        observedWorldFingerprint = fingerprint(
                observedWorldFingerprint,
                "observedWorldFingerprint");
        observedLogicalClock = Objects.requireNonNull(
                observedLogicalClock,
                "observedLogicalClock");
        outcome = Objects.requireNonNull(
                outcome, "outcome");
        stage = Objects.requireNonNull(
                stage, "stage");
        stateDisposition = Objects.requireNonNull(
                stateDisposition, "stateDisposition");
        errorCode = errorCode == null
                ? "" : errorCode.trim();
        errorType = errorType == null
                ? "" : errorType.trim();
        failureFingerprint = failureFingerprint == null
                ? "" : failureFingerprint.trim();
        boolean successful =
                outcome
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED
                        || outcome
                        == MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REPLAYED;
        if (successful != (transition != null)
                || successful
                && (!errorCode.isBlank()
                || !errorType.isBlank()
                || !failureFingerprint.isBlank())
                || !successful
                && (errorCode.isBlank()
                || errorType.isBlank()
                || !failureFingerprint.matches(
                "sha256:[a-f0-9]{64}"))) {
            throw new IllegalArgumentException(
                    "write-attempt observation success and failure material differ");
        }
        if (transition != null
                && (!writeEffectRef.equals(
                transition.writeEffectRef())
                || !observedStateRef.equals(
                transition.initialStateRef())
                || observedStateRevision
                != transition.revisionBefore()
                || !observedWorldFingerprint.equals(
                transition.initialWorldFingerprint())
                || !observedLogicalClock.equals(
                transition.initialLogicalClock()))) {
            throw new IllegalArgumentException(
                    "write-attempt observation differs from its transition");
        }
    }

    /**
     * Projects one committed or exactly replayed durable execution.
     *
     * @param mapper canonical protocol mapper
     * @param effect exact write effect
     * @param execution verified durable progression
     * @param responseFingerprint canonical node output identity
     * @return successful payload-free write-attempt observation
     */
    public static MirrorStateWriteAttemptObservation succeeded(
            ObjectMapper mapper,
            WriteEffectSpec effect,
            MirrorStateRunSession.Execution execution,
            String responseFingerprint) {
        MirrorStateTransitionObservation projected =
                MirrorStateTransitionObservation.project(
                        mapper, effect, execution,
                        responseFingerprint);
        return succeeded(projected);
    }

    /**
     * Wraps an already projected v2 transition as one complete successful attempt.
     *
     * @param projected exact committed or replayed transition
     * @return successful write-attempt observation
     */
    public static MirrorStateWriteAttemptObservation succeeded(
            MirrorStateTransitionObservation projected) {
        Objects.requireNonNull(projected, "projected");
        boolean replayed = projected.replayed();
        return new MirrorStateWriteAttemptObservation(
                projected.writeEffectRef(),
                projected.initialStateRef(),
                projected.revisionBefore(),
                projected.initialWorldFingerprint(),
                projected.initialLogicalClock(),
                replayed
                        ? MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REPLAYED
                        : MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMITTED,
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMPLETED,
                replayed
                        ? MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.UNCHANGED
                        : MirrorStateWriteOutcomeRunEvidence
                        .StateDisposition.ADVANCED,
                false, "", "", "", projected);
    }

    /**
     * Projects one normalized failed attempt without retaining the command input.
     *
     * @param mapper canonical protocol mapper
     * @param effect exact write effect
     * @param observed exact in-run Session head before the attempt
     * @param requestFingerprint canonical invocation input identity
     * @param failure normalized payload-free failure
     * @return failed payload-free write-attempt observation
     */
    public static MirrorStateWriteAttemptObservation failed(
            ObjectMapper mapper,
            WriteEffectSpec effect,
            SessionStateSpace observed,
            String requestFingerprint,
            MirrorStateWriteFailure failure) {
        Objects.requireNonNull(mapper, "mapper");
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(observed, "observed");
        Objects.requireNonNull(failure, "failure");
        MirrorArtifactRef effectRef =
                WriteEffectSpecIntegrity.reference(effect);
        MirrorArtifactRef stateRef = stateRef(observed);
        MirrorStateWriteOutcomeRunEvidence.StateDisposition
                disposition = failure.outcome()
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.COMMIT_OUTCOME_UNKNOWN
                ? MirrorStateWriteOutcomeRunEvidence
                .StateDisposition.UNKNOWN
                : MirrorStateWriteOutcomeRunEvidence
                .StateDisposition.UNCHANGED;
        String fingerprint =
                MirrorStateWriteOutcomeRunEvidenceIntegrity
                        .failureFingerprint(
                                mapper, effectRef, stateRef,
                                observed.stateRevision(),
                                observed.worldFingerprint(),
                                observed.logicalClock(),
                                requestFingerprint,
                                failure.outcome(),
                                failure.stage(),
                                disposition,
                                failure.retryable(),
                                failure.code(),
                                failure.errorType());
        return new MirrorStateWriteAttemptObservation(
                effectRef, stateRef,
                observed.stateRevision(),
                observed.worldFingerprint(),
                observed.logicalClock(),
                failure.outcome(), failure.stage(),
                disposition, failure.retryable(),
                failure.code(), failure.errorType(),
                fingerprint, null);
    }

    /**
     * Materializes the protocol transition nested under a successful attempt.
     *
     * @param invocationSiteId exact invocation site
     * @param graphPath exact owning graph
     * @param correlationKey execution correlation coordinate
     * @param occurrence one-based occurrence
     * @param attempt one-based delegate attempt
     * @param capabilityRef exact virtual-write capability
     * @param requestFingerprint canonical invocation input identity
     * @return protocol transition, or {@code null} for a failed attempt
     */
    public MirrorStateTransitionRunEvidence.StateTransition
            protocolTransition(
            String invocationSiteId,
            String graphPath,
            String correlationKey,
            int occurrence,
            int attempt,
            MirrorArtifactRef capabilityRef,
            String requestFingerprint) {
        if (transition == null) {
            return null;
        }
        return new MirrorStateTransitionRunEvidence
                .StateTransition(
                invocationSiteId, graphPath,
                correlationKey, occurrence, attempt,
                capabilityRef, writeEffectRef,
                transition.initialStateRef(),
                transition.finalStateRef(),
                transition.revisionBefore(),
                transition.revisionAfter(),
                transition.initialWorldFingerprint(),
                transition.finalWorldFingerprint(),
                transition.initialLogicalClock(),
                transition.finalLogicalClock(),
                requestFingerprint,
                transition.idempotencyKeyFingerprint(),
                transition.commandFingerprint(),
                transition.receiptFingerprint(),
                transition.responseFingerprint(),
                transition.resultingWorldFingerprint(),
                transition.committedAt(),
                transition.replayed(),
                transition.events().stream()
                        .map(event ->
                                new MirrorStateTransitionRunEvidence
                                        .TransitionEvent(
                                        event.eventIdFingerprint(),
                                        event.stateRevision(),
                                        event.mutationId(),
                                        event.operation(),
                                        event.entityType(),
                                        event.entityIdentityFingerprint(),
                                        event.beforeFingerprint(),
                                        event.afterFingerprint(),
                                        event.occurredAt(),
                                        event.eventFingerprint()))
                        .toList());
    }

    private static MirrorArtifactRef stateRef(
            SessionStateSpace state) {
        return new MirrorArtifactRef(
                "SESSION_STATE", state.sessionId(),
                Math.addExact(state.stateRevision(), 1),
                state.fingerprint());
    }

    private static MirrorArtifactRef kind(
            MirrorArtifactRef value,
            String expected,
            String field) {
        MirrorArtifactRef ref =
                Objects.requireNonNull(value, field);
        if (!expected.equals(ref.kind())) {
            throw new IllegalArgumentException(
                    field + " must reference " + expected);
        }
        return ref;
    }

    private static String fingerprint(
            String value, String field) {
        String normalized = value == null
                ? "" : value.trim();
        if (!normalized.matches(
                "sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                    field + " must be a canonical SHA-256 value");
        }
        return normalized;
    }
}
