package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Closed result of the final queue cancellation/deadline check.
 *
 * @param decision prepared publication or exact stop winner
 * @param lease renewed {@code COMMITTING} fence only when prepared
 * @param failureCode stable payload-free diagnostic only when stopped
 */
public record TestSuiteStabilityJobCompletionPreparation(
        Decision decision,
        TestSuiteStabilityJobLease lease,
        String failureCode) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Exhaustive terminal-preparation decisions consumed by the worker guard. */
    public enum Decision {
        /** Cancellation/deadline checks passed and publication is now irrevocable. */
        PREPARED,
        /** A retained cancellation won before publication. */
        CANCELLED,
        /** The database deadline won before publication. */
        DEADLINE_EXCEEDED,
        /** Valid signed parent evidence already won and queue success was committed. */
        PARENT_COMPLETED,
        /** The caller no longer owns the exact live queue fence. */
        LEASE_LOST
    }

    /** Enforces one exact lease or one bounded stop diagnostic. */
    public TestSuiteStabilityJobCompletionPreparation {
        decision = Objects.requireNonNull(decision, "decision");
        failureCode = failureCode == null ? "" : failureCode.trim();
        boolean prepared = decision == Decision.PREPARED;
        if (prepared != (lease != null)
                || prepared != failureCode.isBlank()
                || !failureCode.isBlank() && !CODE.matcher(failureCode).matches()) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability completion preparation");
        }
    }

    /** @return successful irrevocable publication preparation */
    public static TestSuiteStabilityJobCompletionPreparation prepared(
            TestSuiteStabilityJobLease lease) {
        return new TestSuiteStabilityJobCompletionPreparation(
                Decision.PREPARED, Objects.requireNonNull(lease, "lease"), "");
    }

    /**
     * @param decision exact non-prepared winner
     * @param failureCode stable payload-free diagnostic
     * @return terminal or fenced preparation result
     */
    public static TestSuiteStabilityJobCompletionPreparation stopped(
            Decision decision,
            String failureCode) {
        if (decision == Decision.PREPARED) {
            throw new IllegalArgumentException("Prepared completion requires a lease");
        }
        return new TestSuiteStabilityJobCompletionPreparation(
                decision, null, failureCode);
    }
}
