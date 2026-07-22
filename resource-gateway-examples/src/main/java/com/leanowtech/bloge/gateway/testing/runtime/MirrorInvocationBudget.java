package com.leanowtech.bloge.gateway.testing.runtime;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Run-scoped, thread-safe admission budget for actual mirror operator occurrences.
 *
 * <p>The budget is consumed by BLOGE's run-scoped operator resolver before an operator is returned
 * to the engine. That boundary is inherited by root nodes, nested graphs, foreach and loop
 * re-entry, streaming nodes, and compensation. Retries remain attempts inside one admitted
 * occurrence and therefore do not consume a second occurrence.</p>
 *
 * <p>The class stores counters only. Invocation identities, correlation keys, inputs, outputs, and
 * exception text are deliberately not retained, so its snapshot is safe to attach to internal
 * payload-free execution metadata.</p>
 */
public final class MirrorInvocationBudget {
    /** Shared-kernel evidence metadata key for the payload-free terminal snapshot. */
    public static final String EVIDENCE_METADATA_KEY = "mirrorInvocationBudget";
    /** Stable evidence limitation emitted after at least one occurrence is rejected. */
    public static final String EXHAUSTED_LIMITATION = "INVOCATION_BUDGET_EXHAUSTED";
    /** Stable shared-kernel error code used to stop an occurrence before operator execution. */
    public static final String EXHAUSTED_CODE = "RG.MIRROR.INVOCATION_BUDGET_EXHAUSTED";

    private final int maximumInvocations;
    private final AtomicInteger admittedInvocations = new AtomicInteger();
    private final AtomicInteger rejectedInvocations = new AtomicInteger();

    /**
     * Creates one independent whole-run occurrence budget.
     *
     * @param maximumInvocations positive maximum copied from the sealed MirrorPlan policy
     */
    public MirrorInvocationBudget(int maximumInvocations) {
        if (maximumInvocations < 1) {
            throw new IllegalArgumentException("maximumInvocations must be positive");
        }
        this.maximumInvocations = maximumInvocations;
    }

    /**
     * Admits exactly one actual operator occurrence or fails it before operator execution.
     *
     * <p>The compare-and-set loop prevents parallel foreach branches from oversubscribing the
     * budget. Once the limit is reached, later calls only increase the bounded diagnostic counter
     * and raise a non-retryable control failure.</p>
     *
     * @throws TestControlException when the whole-run occurrence budget is exhausted
     */
    public void admit() {
        while (true) {
            int current = admittedInvocations.get();
            if (current >= maximumInvocations) {
                rejectedInvocations.getAndUpdate(MirrorInvocationBudget::saturatingIncrement);
                throw new TestControlException(EXHAUSTED_CODE, "MIRROR_INVOCATION_BUDGET",
                        "Mirror invocation occurrence budget exhausted.");
            }
            if (admittedInvocations.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    /**
     * Captures the current payload-free budget counters.
     *
     * @return immutable terminal or in-flight counters
     */
    public Snapshot snapshot() {
        return new Snapshot(maximumInvocations, admittedInvocations.get(),
                rejectedInvocations.get());
    }

    private static int saturatingIncrement(int value) {
        return value == Integer.MAX_VALUE ? value : value + 1;
    }

    /**
     * Payload-free occurrence-budget observation.
     *
     * @param maximumInvocations sealed whole-run limit
     * @param admittedInvocations occurrences allowed to resolve an operator
     * @param rejectedInvocations occurrences stopped before operator execution
     */
    public record Snapshot(
            int maximumInvocations,
            int admittedInvocations,
            int rejectedInvocations
    ) {
        /** Rejects impossible or overflowed observations. */
        public Snapshot {
            if (maximumInvocations < 1 || admittedInvocations < 0 || rejectedInvocations < 0
                    || admittedInvocations > maximumInvocations) {
                throw new IllegalArgumentException("invalid mirror invocation budget snapshot");
            }
        }

        /**
         * Reports whether this observation includes a denied occurrence.
         *
         * @return whether at least one occurrence was denied before operator execution
         */
        public boolean exhausted() {
            return rejectedInvocations > 0;
        }
    }
}
