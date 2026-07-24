package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Durable payload-free outcome journal for one Session write execution attempt.
 *
 * <p>The record is an operational recovery protocol, not a command log. It binds one execution
 * lease and one graph delegate coordinate to the command fingerprint computed by the state
 * transaction engine. It deliberately excludes command input, response, entity identity, raw
 * idempotency key, provider diagnostics, lease owner, and encryption material.</p>
 *
 * <p>An {@link Status#IN_PROGRESS} record is an intent written before mutation evaluation. A
 * terminal record is immutable and can be produced only by exact replay confirmation, the same
 * database transaction that advances the Session head, a proven pre-commit failure, or
 * post-lease reconciliation against the append-only receipt journal.</p>
 *
 * @param schemaVersion write-attempt journal protocol version
 * @param scope exact authenticated enterprise namespace
 * @param sessionId exact Session identity
 * @param attemptId deterministic execution-attempt identity
 * @param coordinate exact run and delegate-attempt coordinate
 * @param storeGeneration immutable durable Session data-plane generation
 * @param planFingerprint exact admitted mirror plan generation
 * @param writeEffectRef exact admitted write effect
 * @param requestFingerprint canonical invocation input identity
 * @param commandFingerprint canonical state-command identity
 * @param initialStateRevision durable revision observed before execution
 * @param initialWorldFingerprint business-world identity observed before execution
 * @param initialStateFingerprint state-and-receipt-journal identity observed before execution
 * @param status current durable journal status
 * @param outcome terminal outcome, absent while in progress
 * @param stage last trustworthy processing stage
 * @param stateDisposition proven state-head effect
 * @param resultingStateRevision resulting revision, or {@code -1} when unknown
 * @param resultingWorldFingerprint resulting world identity, blank when unknown
 * @param resultingStateFingerprint resulting state identity, blank when unknown
 * @param receiptFingerprint exact receipt identity for committed or replayed outcomes
 * @param retryable whether a new governed execution attempt may be admitted
 * @param errorCode stable failure code, blank for non-failures
 * @param errorType normalized failure family, blank for non-failures
 * @param failureFingerprint canonical failure identity, blank for non-failures
 * @param resolutionSource authority that established the current status
 * @param startedAt database-authoritative intent time
 * @param terminalAt database-authoritative terminal time, absent while in progress
 * @param reconciledAt reconciliation time, present only for reconciler terminalization
 * @param fingerprint canonical record fingerprint with this field blanked
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MirrorStateWriteAttempt(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        String sessionId,
        String attemptId,
        Coordinate coordinate,
        MirrorSessionStoreGeneration storeGeneration,
        String planFingerprint,
        MirrorArtifactRef writeEffectRef,
        String requestFingerprint,
        String commandFingerprint,
        long initialStateRevision,
        String initialWorldFingerprint,
        String initialStateFingerprint,
        Status status,
        MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
        MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
        MirrorStateWriteOutcomeRunEvidence.StateDisposition stateDisposition,
        long resultingStateRevision,
        String resultingWorldFingerprint,
        String resultingStateFingerprint,
        String receiptFingerprint,
        boolean retryable,
        String errorCode,
        String errorType,
        String failureFingerprint,
        ResolutionSource resolutionSource,
        Instant startedAt,
        Instant terminalAt,
        Instant reconciledAt,
        String fingerprint
) {
    /** Current durable write-attempt journal protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.mirrorStateWriteAttempt.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:-]{0,511}");
    private static final Pattern ERROR_TYPE =
            Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Durable lifecycle of one immutable execution attempt. */
    public enum Status {
        IN_PROGRESS,
        TERMINAL
    }

    /** Authority that established the currently recorded outcome. */
    public enum ResolutionSource {
        EXECUTION,
        RECONCILER
    }

    /** Execution surface that created the write attempt. */
    public enum ExecutionKind {
        GRAPH_RUN,
        SESSION_COMMAND
    }

    /** Validates the complete payload-free state machine closure. */
    public MirrorStateWriteAttempt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException(
                    "unsupported mirror state write-attempt schemaVersion");
        }
        scope = Objects.requireNonNull(scope, "scope");
        sessionId = identifier(sessionId, "sessionId");
        attemptId = identifier(attemptId, "attemptId");
        coordinate = Objects.requireNonNull(coordinate, "coordinate");
        storeGeneration = Objects.requireNonNull(
                storeGeneration, "storeGeneration");
        planFingerprint = MirrorStateProtocolSupport.fingerprint(
                planFingerprint, "planFingerprint");
        writeEffectRef = Objects.requireNonNull(
                writeEffectRef, "writeEffectRef");
        if (!"WRITE_EFFECT".equals(writeEffectRef.kind())) {
            throw new IllegalArgumentException(
                    "writeEffectRef must reference WRITE_EFFECT");
        }
        requestFingerprint = MirrorStateProtocolSupport.fingerprint(
                requestFingerprint, "requestFingerprint");
        commandFingerprint = MirrorStateProtocolSupport.fingerprint(
                commandFingerprint, "commandFingerprint");
        if (initialStateRevision < 0) {
            throw new IllegalArgumentException(
                    "initialStateRevision must not be negative");
        }
        initialWorldFingerprint = MirrorStateProtocolSupport.fingerprint(
                initialWorldFingerprint, "initialWorldFingerprint");
        initialStateFingerprint = MirrorStateProtocolSupport.fingerprint(
                initialStateFingerprint, "initialStateFingerprint");
        status = Objects.requireNonNull(status, "status");
        stage = Objects.requireNonNull(stage, "stage");
        stateDisposition = Objects.requireNonNull(
                stateDisposition, "stateDisposition");
        resultingWorldFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        resultingWorldFingerprint,
                        "resultingWorldFingerprint");
        resultingStateFingerprint =
                MirrorStateProtocolSupport.optionalFingerprint(
                        resultingStateFingerprint,
                        "resultingStateFingerprint");
        receiptFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                receiptFingerprint, "receiptFingerprint");
        errorCode = optionalErrorCode(errorCode);
        errorType = optionalErrorType(errorType);
        failureFingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                failureFingerprint, "failureFingerprint");
        resolutionSource = Objects.requireNonNull(
                resolutionSource, "resolutionSource");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
        fingerprint = MirrorStateProtocolSupport.optionalFingerprint(
                fingerprint, "fingerprint");
        validateLifecycle(
                status, outcome, stage, stateDisposition,
                initialStateRevision, initialWorldFingerprint,
                initialStateFingerprint, resultingStateRevision,
                resultingWorldFingerprint, resultingStateFingerprint,
                receiptFingerprint, retryable, errorCode, errorType,
                failureFingerprint, resolutionSource, startedAt,
                terminalAt, reconciledAt);
    }

    /**
     * Returns a copy carrying a replacement canonical fingerprint.
     *
     * @param value canonical fingerprint or blank while sealing
     * @return immutable replacement
     */
    public MirrorStateWriteAttempt withFingerprint(String value) {
        return new MirrorStateWriteAttempt(
                schemaVersion, scope, sessionId, attemptId, coordinate,
                storeGeneration, planFingerprint, writeEffectRef, requestFingerprint,
                commandFingerprint, initialStateRevision,
                initialWorldFingerprint, initialStateFingerprint,
                status, outcome, stage, stateDisposition,
                resultingStateRevision, resultingWorldFingerprint,
                resultingStateFingerprint, receiptFingerprint,
                retryable, errorCode, errorType, failureFingerprint,
                resolutionSource, startedAt, terminalAt, reconciledAt,
                value);
    }

    /** Prevents customer coordinates and command material from entering ordinary logs. */
    @Override
    public String toString() {
        return "MirrorStateWriteAttempt[attemptId=" + attemptId
                + ", status=" + status
                + ", outcome=" + outcome
                + ", initialStateRevision=" + initialStateRevision
                + ", resultingStateRevision=" + resultingStateRevision
                + "]";
    }

    private static void validateLifecycle(
            Status status,
            MirrorStateWriteOutcomeRunEvidence.WriteOutcome outcome,
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            MirrorStateWriteOutcomeRunEvidence.StateDisposition disposition,
            long initialRevision,
            String initialWorldFingerprint,
            String initialStateFingerprint,
            long resultingRevision,
            String resultingWorldFingerprint,
            String resultingStateFingerprint,
            String receiptFingerprint,
            boolean retryable,
            String errorCode,
            String errorType,
            String failureFingerprint,
            ResolutionSource source,
            Instant startedAt,
            Instant terminalAt,
            Instant reconciledAt) {
        if (status == Status.IN_PROGRESS) {
            if (outcome != null
                    || stage != MirrorStateWriteOutcomeRunEvidence
                    .WriteStage.COMMAND_ADMISSION
                    || disposition != MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.UNKNOWN
                    || resultingRevision != -1
                    || !resultingWorldFingerprint.isBlank()
                    || !resultingStateFingerprint.isBlank()
                    || !receiptFingerprint.isBlank()
                    || retryMaterialPresent(
                    errorCode, errorType, failureFingerprint)
                    || source != ResolutionSource.EXECUTION
                    || terminalAt != null
                    || reconciledAt != null) {
                throw new IllegalArgumentException(
                        "in-progress write attempt contains terminal material");
            }
            return;
        }
        if (outcome == null || terminalAt == null
                || terminalAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "terminal write attempt lacks an ordered outcome");
        }
        if ((source == ResolutionSource.RECONCILER)
                != (reconciledAt != null)
                || reconciledAt != null
                && (!reconciledAt.equals(terminalAt)
                || reconciledAt.isBefore(startedAt))) {
            throw new IllegalArgumentException(
                    "write-attempt reconciliation coordinates differ");
        }
        boolean success = outcome
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.COMMITTED
                || outcome
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.REPLAYED;
        if (success) {
            boolean committed = outcome
                    == MirrorStateWriteOutcomeRunEvidence
                    .WriteOutcome.COMMITTED;
            if (stage != MirrorStateWriteOutcomeRunEvidence
                    .WriteStage.COMPLETED
                    || disposition != (committed
                    ? MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.ADVANCED
                    : MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.UNCHANGED)
                    || resultingRevision != (committed
                    ? Math.addExact(initialRevision, 1)
                    : initialRevision)
                    || resultingWorldFingerprint.isBlank()
                    || (committed
                    && resultingStateFingerprint.isBlank()
                    && source != ResolutionSource.RECONCILER)
                    || !committed && (!resultingWorldFingerprint.equals(
                    initialWorldFingerprint)
                    || !resultingStateFingerprint.equals(
                    initialStateFingerprint))
                    || receiptFingerprint.isBlank()
                    || retryMaterialPresent(
                    errorCode, errorType, failureFingerprint)) {
                throw new IllegalArgumentException(
                        "successful write attempt has inconsistent closure");
            }
            return;
        }
        if (errorCode.isBlank()
                || errorType.isBlank()
                || failureFingerprint.isBlank()
                || !receiptFingerprint.isBlank()) {
            throw new IllegalArgumentException(
                    "failed write attempt lacks exact failure material");
        }
        if (outcome
                == MirrorStateWriteOutcomeRunEvidence
                .WriteOutcome.COMMIT_OUTCOME_UNKNOWN) {
            if (disposition != MirrorStateWriteOutcomeRunEvidence
                    .StateDisposition.UNKNOWN
                    || resultingRevision != -1
                    || !resultingWorldFingerprint.isBlank()
                    || !resultingStateFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "unknown write outcome must not claim a resulting state");
            }
        } else if (disposition
                != MirrorStateWriteOutcomeRunEvidence
                .StateDisposition.UNCHANGED
                || resultingRevision != initialRevision
                || !resultingWorldFingerprint.equals(
                initialWorldFingerprint)
                || !resultingStateFingerprint.equals(
                initialStateFingerprint)) {
            throw new IllegalArgumentException(
                    "known failed write attempt must preserve its initial state");
        }
    }

    private static boolean retryMaterialPresent(
            String errorCode,
            String errorType,
            String failureFingerprint) {
        return !errorCode.isBlank()
                || !errorType.isBlank()
                || !failureFingerprint.isBlank();
    }

    private static String identifier(String value, String field) {
        String normalized = MirrorStateProtocolSupport.required(
                value, field);
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                    field + " contains unsupported characters");
        }
        return normalized;
    }

    private static String optionalErrorCode(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()) {
            MirrorStateProtocolSupport.errorCode(normalized);
        }
        return normalized;
    }

    private static String optionalErrorType(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.isBlank()
                && !ERROR_TYPE.matcher(normalized).matches()) {
            throw new IllegalArgumentException("errorType is invalid");
        }
        return normalized;
    }

    /**
     * Stable run-attempt and invocation coordinate used to derive {@link #attemptId()}.
     *
     * @param executionKind graph execution or direct Session command
     * @param executionRequestId durable request identity
     * @param executionLeaseEpoch monotonic execution-claim generation
     * @param invocationSiteId exact graph invocation site or direct-command marker
     * @param graphPath exact graph path or direct-command marker
     * @param correlationFingerprint optional domain-separated fingerprint of the execution
     *                               correlation coordinate; the business value is never retained
     * @param occurrence one-based occurrence
     * @param delegateAttempt one-based delegate attempt
     */
    public record Coordinate(
            ExecutionKind executionKind,
            String executionRequestId,
            long executionLeaseEpoch,
            String invocationSiteId,
            String graphPath,
            String correlationFingerprint,
            int occurrence,
            int delegateAttempt
    ) {
        /** Validates bounded payload-free execution coordinates. */
        public Coordinate {
            executionKind = Objects.requireNonNull(
                    executionKind, "executionKind");
            executionRequestId = identifier(
                    executionRequestId, "executionRequestId");
            if (executionLeaseEpoch < 1) {
                throw new IllegalArgumentException(
                        "executionLeaseEpoch must be positive");
            }
            invocationSiteId = bounded(
                    invocationSiteId, "invocationSiteId", 512);
            graphPath = bounded(graphPath, "graphPath", 2_048);
            correlationFingerprint =
                    MirrorStateProtocolSupport.optionalFingerprint(
                            correlationFingerprint,
                            "correlationFingerprint");
            if (occurrence < 1 || delegateAttempt < 1) {
                throw new IllegalArgumentException(
                        "occurrence and delegateAttempt must be positive");
            }
        }

        private static String bounded(
                String value, String field, int maximum) {
            String normalized = MirrorStateProtocolSupport.required(
                    value, field);
            if (normalized.length() > maximum) {
                throw new IllegalArgumentException(
                        field + " exceeds its maximum length");
            }
            return normalized;
        }

    }
}
