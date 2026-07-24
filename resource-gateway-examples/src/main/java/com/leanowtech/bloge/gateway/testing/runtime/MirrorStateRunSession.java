package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionDescriptor;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionPayload;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorSessionProtocolIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorStateWriteOutcomeRunEvidence;
import com.leanowtech.bloge.gateway.integration.mirror.SessionStateSpace;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpec;
import com.leanowtech.bloge.gateway.integration.mirror.WriteEffectSpecIntegrity;
import com.leanowtech.bloge.gateway.testing.domain.ProtocolJsonValue;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Run-scoped serializable bridge between BLOGE virtual-write nodes and one durable Session.
 *
 * <p>The bridge starts from the authenticated state head admitted with the graph run. Each new
 * command is serialized under a fair lock and carries the exact current state fingerprint into
 * the protected Session command boundary. The delegate remains the only component allowed to
 * claim a data-plane lease or commit a candidate through CAS. After a verified commit or exact
 * replay, the bridge advances its in-run head so downstream state reads observe the committed
 * world. It never accepts a store, credential, production operator, or caller-selected scope.</p>
 */
public final class MirrorStateRunSession {
    private final ObjectMapper mapper;
    private final MirrorSessionPayload initialPayload;
    private final CommandExecutor executor;
    private final AtomicReference<MirrorSessionPayload> currentPayload;
    private final ReentrantLock commandLock = new ReentrantLock(true);

    /**
     * Creates one run-scoped state head.
     *
     * @param mapper canonical protocol mapper
     * @param initialPayload exact authenticated Session snapshot admitted before graph execution
     * @param executor protected durable command boundary
     */
    public MirrorStateRunSession(
            ObjectMapper mapper,
            MirrorSessionPayload initialPayload,
            CommandExecutor executor) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.initialPayload = Objects.requireNonNull(
                initialPayload, "initialPayload");
        MirrorSessionProtocolIntegrity.verify(mapper, initialPayload);
        this.executor = Objects.requireNonNull(executor, "executor");
        this.currentPayload = new AtomicReference<>(initialPayload);
    }

    /**
     * Executes or exactly replays one state-backed graph invocation.
     *
     * @param writeEffectRef exact effect selected by the invocation capability
     * @param input ephemeral detached invocation input
     * @return verified before/after state heads and committed receipt
     */
    public Execution execute(
            MirrorArtifactRef writeEffectRef,
            Map<String, ?> input) {
        Objects.requireNonNull(writeEffectRef, "writeEffectRef");
        if (!"WRITE_EFFECT".equals(writeEffectRef.kind())) {
            throw new IllegalArgumentException(
                    "graph state command must reference WRITE_EFFECT");
        }
        Map<String, Object> detachedInput =
                ProtocolJsonValue.freezeMap(input);
        commandLock.lock();
        try {
            MirrorSessionPayload before = currentPayload.get();
            WriteEffectSpec effect = before.writeEffects().stream()
                    .filter(candidate -> WriteEffectSpecIntegrity
                            .reference(candidate).equals(writeEffectRef))
                    .findFirst()
                    .orElseThrow(() -> new TestControlException(
                            "MIRROR_SESSION_WRITE_EFFECT_NOT_ADMITTED",
                            "MIRROR_STATE_WRITE",
                            "The Session does not admit the exact graph write effect."));
            WriteEffectSpecIntegrity.verify(
                    mapper, effect, before.stateModel());
            CommandResult result;
            try {
                result = Objects.requireNonNull(
                        executor.execute(
                                writeEffectRef, detachedInput,
                                before.state().fingerprint()),
                        "command result");
            } catch (MirrorStateWriteFailure normalized) {
                throw normalized;
            } catch (RuntimeException failure) {
                throw classify(failure);
            }
            try {
                verifyProgression(
                        before, writeEffectRef, result);
            } catch (RuntimeException invalid) {
                throw unknown(
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteStage.RESULT_VERIFICATION,
                        "MIRROR_SESSION_WRITE_RESULT_UNVERIFIED",
                        false);
            }
            currentPayload.set(result.payload());
            return new Execution(
                    before, result.payload(), result.descriptor(),
                    result.receipt(), result.replayed());
        } finally {
            commandLock.unlock();
        }
    }

    /** @return immutable Session aggregate admitted at graph-run start */
    public MirrorSessionPayload initialPayload() {
        return initialPayload;
    }

    /** @return latest verified Session aggregate visible to downstream graph nodes */
    public MirrorSessionPayload currentPayload() {
        return currentPayload.get();
    }

    private static MirrorStateWriteFailure classify(
            RuntimeException failure) {
        if (failure instanceof IntegrationProblemException
                integration) {
            return classify(integration.problem());
        }
        if (failure instanceof MirrorStateException state) {
            if ("RG.MIRROR.STATE.COMMIT_FAILED".equals(
                    state.code())
                    || state.code().endsWith(
                    "_UNAVAILABLE")) {
                return unknown(
                        MirrorStateWriteOutcomeRunEvidence
                                .WriteStage.COMMIT,
                        state.code(), true);
            }
            return rejected(
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMAND_EVALUATION,
                    state.code(), false);
        }
        if (failure instanceof TestControlException control) {
            return rejected(
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMAND_ADMISSION,
                    control.code(), false);
        }
        return unknown(
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMIT,
                "MIRROR_SESSION_WRITE_OUTCOME_UNKNOWN",
                false);
    }

    private static MirrorStateWriteFailure classify(
            IntegrationProblem problem) {
        String code = stableProblemCode(problem.code());
        if ("RG.MIRROR.SESSION.STORE_UNAVAILABLE".equals(code)
                || "RG.MIRROR.SESSION.STATE_CORRUPT".equals(code)
                || "RG.MIRROR.STATE.COMMIT_FAILED".equals(code)
                || code.endsWith("_UNAVAILABLE")) {
            return unknown(
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMIT,
                    code, problem.retryable());
        }
        if (code.startsWith("RG.MIRROR.STATE.")) {
            return rejected(
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMAND_EVALUATION,
                    code, problem.retryable());
        }
        if (code.startsWith("RG.MIRROR.SESSION.")) {
            return new MirrorStateWriteFailure(
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteOutcome.PRE_COMMIT_FAILED,
                    MirrorStateWriteOutcomeRunEvidence
                            .WriteStage.COMMAND_ADMISSION,
                    code, "MIRROR_STATE_WRITE",
                    problem.retryable());
        }
        return unknown(
                MirrorStateWriteOutcomeRunEvidence
                        .WriteStage.COMMIT,
                "MIRROR_SESSION_WRITE_OUTCOME_UNKNOWN",
                problem.retryable());
    }

    private static String stableProblemCode(String value) {
        String normalized =
                value == null ? "" : value.trim();
        return normalized.matches(
                "[A-Z][A-Z0-9_.-]{0,191}")
                ? normalized
                : "MIRROR_SESSION_WRITE_OUTCOME_UNKNOWN";
    }

    private static MirrorStateWriteFailure rejected(
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            String code,
            boolean retryable) {
        return new MirrorStateWriteFailure(
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.REJECTED,
                stage, code, "MIRROR_STATE_WRITE",
                retryable);
    }

    private static MirrorStateWriteFailure unknown(
            MirrorStateWriteOutcomeRunEvidence.WriteStage stage,
            String code,
            boolean retryable) {
        return new MirrorStateWriteFailure(
                MirrorStateWriteOutcomeRunEvidence
                        .WriteOutcome.COMMIT_OUTCOME_UNKNOWN,
                stage, code, "MIRROR_STATE_WRITE",
                retryable);
    }

    private void verifyProgression(
            MirrorSessionPayload before,
            MirrorArtifactRef writeEffectRef,
            CommandResult result) {
        MirrorSessionPayload after = Objects.requireNonNull(
                result.payload(), "result payload");
        MirrorSessionProtocolIntegrity.verify(mapper, after);
        SessionStateSpace previous = before.state();
        SessionStateSpace current = after.state();
        if (!previous.sessionId().equals(current.sessionId())
                || !previous.scope().equals(current.scope())
                || !previous.planFingerprint().equals(
                current.planFingerprint())
                || !previous.stateModelRef().equals(
                current.stateModelRef())
                || !before.stateModel().equals(after.stateModel())
                || !before.stateReadSpecs().equals(
                after.stateReadSpecs())
                || !before.writeEffects().equals(after.writeEffects())
                || !current.writeEffectRefs().contains(writeEffectRef)
                || !result.descriptor().sessionId().equals(
                current.sessionId())
                || !result.descriptor().stateFingerprint().equals(
                current.fingerprint())
                || result.descriptor().stateRevision()
                != current.stateRevision()) {
            throw new TestControlException(
                    "MIRROR_SESSION_WRITE_RESULT_DRIFT",
                    "MIRROR_STATE_WRITE",
                    "The durable graph write result changed immutable Session coordinates.");
        }
        SessionStateSpace.TransactionReceipt receipt =
                Objects.requireNonNull(result.receipt(), "receipt");
        if (!current.processedCommands().contains(receipt)) {
            throw new TestControlException(
                    "MIRROR_SESSION_WRITE_RECEIPT_MISSING",
                    "MIRROR_STATE_WRITE",
                    "The resulting Session head does not contain the exact receipt.");
        }
        if (result.replayed()) {
            if (!previous.fingerprint().equals(current.fingerprint())
                    || receipt.revisionAfter()
                    > current.stateRevision()) {
                throw new TestControlException(
                        "MIRROR_SESSION_WRITE_REPLAY_DRIFT",
                        "MIRROR_STATE_WRITE",
                        "An exact graph write replay changed the Session head.");
            }
        } else if (current.stateRevision()
                != Math.addExact(previous.stateRevision(), 1)
                || receipt.revisionBefore()
                != previous.stateRevision()
                || receipt.revisionAfter()
                != current.stateRevision()
                || !receipt.resultingWorldFingerprint().equals(
                current.worldFingerprint())) {
            throw new TestControlException(
                    "MIRROR_SESSION_WRITE_REVISION_DRIFT",
                    "MIRROR_STATE_WRITE",
                    "A new graph write did not advance exactly one Session revision.");
        }
    }

    /**
     * Protected command adapter supplied by the authenticated integration boundary.
     *
     * <p>Implementations must return only after the candidate is durably committed or an exact
     * idempotency receipt has been found. A thrown failure must not expose an uncommitted candidate
     * through a later Session read.</p>
     */
    @FunctionalInterface
    public interface CommandExecutor {
        /**
         * Executes one exact write under an optimistic state fence.
         *
         * @param writeEffectRef exact admitted write effect
         * @param input detached business command
         * @param expectedStateFingerprint exact current run head
         * @return durable result including the complete newly visible Session payload
         */
        CommandResult execute(
                MirrorArtifactRef writeEffectRef,
                Map<String, Object> input,
                String expectedStateFingerprint);
    }

    /**
     * Complete durable command result returned by the protected Session boundary.
     *
     * @param descriptor payload-free current Session descriptor
     * @param payload complete encrypted-data-plane aggregate after the command
     * @param receipt original or newly committed transaction receipt
     * @param replayed whether the idempotency journal supplied an existing receipt
     */
    public record CommandResult(
            MirrorSessionDescriptor descriptor,
            MirrorSessionPayload payload,
            SessionStateSpace.TransactionReceipt receipt,
            boolean replayed
    ) {
        /** Requires all durable result components while keeping payloads out of logs. */
        public CommandResult {
            descriptor = Objects.requireNonNull(
                    descriptor, "descriptor");
            payload = Objects.requireNonNull(payload, "payload");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }

        /** Prevents the decrypted Session aggregate from entering generic logs. */
        @Override
        public String toString() {
            return "CommandResult[sessionId="
                    + descriptor.sessionId()
                    + ", stateRevision="
                    + descriptor.stateRevision()
                    + ", replayed=" + replayed + "]";
        }
    }

    /**
     * Verified state progression produced by one graph write invocation.
     *
     * @param before exact state head observed by the command
     * @param after exact state head visible to downstream graph nodes
     * @param descriptor payload-free durable descriptor for {@code after}
     * @param receipt exact original or newly committed transaction receipt
     * @param replayed whether no new state revision was committed
     */
    public record Execution(
            MirrorSessionPayload before,
            MirrorSessionPayload after,
            MirrorSessionDescriptor descriptor,
            SessionStateSpace.TransactionReceipt receipt,
            boolean replayed
    ) {
        /** Requires a complete progression while keeping payloads out of logs. */
        public Execution {
            before = Objects.requireNonNull(before, "before");
            after = Objects.requireNonNull(after, "after");
            descriptor = Objects.requireNonNull(
                    descriptor, "descriptor");
            receipt = Objects.requireNonNull(receipt, "receipt");
        }

        /** Prevents before/after business worlds from entering generic logs. */
        @Override
        public String toString() {
            return "Execution[sessionId="
                    + descriptor.sessionId()
                    + ", revisionBefore="
                    + before.state().stateRevision()
                    + ", revisionAfter="
                    + after.state().stateRevision()
                    + ", replayed=" + replayed + "]";
        }
    }
}
