package com.leanowtech.bloge.gateway.testkit;

import java.util.List;

/**
 * Immutable batch projection with deterministic CI process-exit semantics.
 *
 * @param runs ordered independent run results
 */
public record TestRunBatch(List<TestRun> runs) {
    /** Creates an immutable run list. */
    public TestRunBatch {
        runs = runs == null ? List.of() : List.copyOf(runs);
    }

    /**
     * Counts the successful terminal runs in this batch.
     *
     * @return count of runs whose terminal status is PASSED
     */
    public long passedCount() {
        return runs.stream().filter(TestRun::passed).count();
    }

    /**
     * Counts every run that did not reach the passing terminal state.
     *
     * @return count of all non-passing runs
     */
    public long failedCount() {
        return runs.size() - passedCount();
    }

    /**
     * Projects the batch outcome to a conventional process exit code.
     *
     * @return zero when every run passed, otherwise one
     */
    public int exitCode() {
        return failedCount() == 0 ? 0 : 1;
    }
}
