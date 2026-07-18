package com.leanowtech.bloge.gateway.testing.api;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Parent terminal authority used before a durable queue job becomes terminal.
 *
 * <p>Implementations must durably stop the exact parent or prove that signed parent evidence
 * already won. They must never report stopped before the parent tombstone is committed, and must
 * never authorize queue success from caller-provided references alone.</p>
 */
public interface TestSuiteStabilityJobParentAuthority {

    /** Closed parent-first outcomes. */
    enum Outcome {
        /** A retained stop tombstone now forbids parent execution or resumption. */
        STOPPED,
        /** Signed parent evidence was already committed and is the terminal winner. */
        COMPLETED
    }

    /**
     * Parent terminal resolution returned to the queue transaction.
     *
     * @param outcome stopped or already-completed winner
     * @param stabilityRunId signed parent id only when completed
     * @param evidenceFingerprint signed evidence fingerprint only when completed
     */
    record Resolution(
            Outcome outcome,
            String stabilityRunId,
            String evidenceFingerprint) {

        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /** Enforces one unambiguous payload-free resolution shape. */
        public Resolution {
            outcome = java.util.Objects.requireNonNull(outcome, "outcome");
            stabilityRunId = normalized(stabilityRunId);
            evidenceFingerprint = normalized(evidenceFingerprint);
            boolean complete = !stabilityRunId.isBlank()
                    && FINGERPRINT.matcher(evidenceFingerprint).matches();
            if ((outcome == Outcome.COMPLETED) != complete
                    || outcome == Outcome.STOPPED
                    && (!stabilityRunId.isBlank() || !evidenceFingerprint.isBlank())) {
                throw new IllegalArgumentException(
                        "Invalid suite-stability parent terminal resolution");
            }
        }

        /** @return a committed parent stop result */
        public static Resolution stopped() {
            return new Resolution(Outcome.STOPPED, "", "");
        }

        /**
         * @param stabilityRunId signed parent run id
         * @param evidenceFingerprint signed parent evidence fingerprint
         * @return completed-parent winner
         */
        public static Resolution completed(
                String stabilityRunId,
                String evidenceFingerprint) {
            return new Resolution(Outcome.COMPLETED, stabilityRunId, evidenceFingerprint);
        }

        private static String normalized(String value) {
            return value == null ? "" : value.trim();
        }
    }

    /**
     * Stops an exact parent before the caller commits a queue terminal state.
     *
     * @param job integrity-verified queue job
     * @param reason parent stop reason
     * @param failureCode bounded stable diagnostic
     * @param retention stop tombstone retention
     * @return stopped or already-completed parent winner
     */
    Resolution stop(
            TestSuiteStabilityJobRecord job,
            TestSuiteStabilityExecutionStop.Reason reason,
            String failureCode,
            Duration retention);

    /**
     * Proves that an exact signed parent terminal exists before queue success is committed.
     *
     * <p>Implementations must recompute canonical evidence identity and verify the detached
     * signature. A caller-provided run id or fingerprint is never sufficient proof.</p>
     *
     * @param job integrity-verified queue job
     * @param stabilityRunId expected deterministic parent run id
     * @param evidenceFingerprint expected canonical signed evidence fingerprint
     * @return the exact cryptographically verified completed-parent resolution
     */
    Resolution requireCompleted(
            TestSuiteStabilityJobRecord job,
            String stabilityRunId,
            String evidenceFingerprint);
}
