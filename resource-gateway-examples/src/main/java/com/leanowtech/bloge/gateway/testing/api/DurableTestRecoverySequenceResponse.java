package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/**
 * Payload-free result of one bounded automatic durable recovery sequence.
 *
 * @param schemaVersion recovery-sequence response protocol version
 * @param runId governed durable run identity
 * @param outcome final suspended or terminal BLOGE outcome reached by this call
 * @param status final durable control status
 * @param stopReason whether terminal was reached or the supplied signals were exhausted
 * @param providedSignalCount number of signals bound by the reserved outer intent
 * @param consumedSignalCount number of signals durably consumed before stopping
 * @param steps ordered immutable payload-free child-step results
 * @param idempotentReplay whether the reservation and every executed child command were replayed
 */
public record DurableTestRecoverySequenceResponse(
        String schemaVersion,
        String runId,
        String outcome,
        String status,
        String stopReason,
        int providedSignalCount,
        int consumedSignalCount,
        List<DurableTestRecoveryStepResponse> steps,
        boolean idempotentReplay
) {
    /** Current bounded recovery-sequence response protocol. */
    public static final String SCHEMA_VERSION =
            "bloge.durableTestRecoverySequenceResponse.v1";

    /** Enforces an ordered, gap-free and internally consistent sequence projection. */
    public DurableTestRecoverySequenceResponse {
        schemaVersion = normalized(schemaVersion).isBlank()
                ? SCHEMA_VERSION : normalized(schemaVersion);
        runId = normalized(runId);
        outcome = normalized(outcome);
        status = normalized(status);
        stopReason = normalized(stopReason);
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || runId.isBlank()
                || providedSignalCount < 1
                || providedSignalCount > 16
                || consumedSignalCount != steps.size()
                || consumedSignalCount < 1
                || consumedSignalCount > providedSignalCount) {
            throw new IllegalArgumentException(
                    "A complete bounded recovery-sequence result is required");
        }
        for (int index = 0; index < steps.size(); index++) {
            DurableTestRecoveryStepResponse step = steps.get(index);
            if (step == null || !runId.equals(step.runId())) {
                throw new IllegalArgumentException(
                        "Every recovery-sequence step must bind the same run");
            }
            if (index < steps.size() - 1 && !"SUSPENDED".equals(step.outcome())) {
                throw new IllegalArgumentException(
                        "Only the final recovery-sequence step may be terminal");
            }
        }
        DurableTestRecoveryStepResponse last = steps.getLast();
        if (!outcome.equals(last.outcome()) || !status.equals(last.status())) {
            throw new IllegalArgumentException(
                    "Recovery-sequence outcome must match its final step");
        }
        if ("SUSPENDED".equals(outcome) && consumedSignalCount != providedSignalCount) {
            throw new IllegalArgumentException(
                    "A suspended recovery sequence must consume every supplied signal");
        }
        String expectedStopReason = "TERMINAL".equals(status)
                ? "TERMINAL" : "SIGNALS_EXHAUSTED";
        if (!expectedStopReason.equals(stopReason)) {
            throw new IllegalArgumentException(
                    "Recovery-sequence stop reason must agree with its final control status");
        }
        if (idempotentReplay && steps.stream().anyMatch(step -> !step.idempotentReplay())) {
            throw new IllegalArgumentException(
                    "A fully replayed sequence cannot contain a newly committed step");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
