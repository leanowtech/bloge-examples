package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;

/**
 * Closed result envelope for one cross-replica stability-job retention attempt.
 *
 * @param status committed page or normal live-lease contention
 * @param result aggregate result present exactly when the page committed
 */
public record TestSuiteStabilityJobRetentionAttempt(
        Status status,
        TestSuiteStabilityJobRetentionResult result) {

    /** Normal maintenance outcomes; storage failures remain exceptions. */
    public enum Status {
        COMPLETED,
        LEASE_BUSY
    }

    /** Enforces exact status/result correspondence. */
    public TestSuiteStabilityJobRetentionAttempt {
        status = Objects.requireNonNull(status, "status");
        if ((status == Status.COMPLETED) != (result != null)) {
            throw new IllegalArgumentException(
                    "Completed stability-job retention requires exactly one result");
        }
    }

    /** @return a committed attempt around one immutable aggregate */
    public static TestSuiteStabilityJobRetentionAttempt completed(
            TestSuiteStabilityJobRetentionResult result) {
        return new TestSuiteStabilityJobRetentionAttempt(
                Status.COMPLETED, Objects.requireNonNull(result, "result"));
    }

    /** @return normal contention while another replica owns a live database lease */
    public static TestSuiteStabilityJobRetentionAttempt leaseBusy() {
        return new TestSuiteStabilityJobRetentionAttempt(Status.LEASE_BUSY, null);
    }
}
