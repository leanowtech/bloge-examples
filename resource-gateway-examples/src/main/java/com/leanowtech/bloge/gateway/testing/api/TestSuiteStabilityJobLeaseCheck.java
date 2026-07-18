package com.leanowtech.bloge.gateway.testing.api;

/**
 * Result of an exact queue-worker heartbeat and cooperative stop check.
 *
 * @param decision whether execution may continue
 * @param lease renewed fence only for {@link Decision#CONTINUE}
 * @param failureCode stable stop diagnostic, blank only for continue
 */
public record TestSuiteStabilityJobLeaseCheck(
        Decision decision,
        TestSuiteStabilityJobLease lease,
        String failureCode) {

    /** Closed worker control decisions. */
    public enum Decision {
        CONTINUE,
        CANCELLED,
        DEADLINE_EXCEEDED,
        /** Signed parent evidence won while a stop was being linearized. */
        PARENT_COMPLETED,
        LEASE_LOST
    }

    /** Enforces one renewed fence or one payload-free stop reason. */
    public TestSuiteStabilityJobLeaseCheck {
        decision = java.util.Objects.requireNonNull(decision, "decision");
        failureCode = failureCode == null ? "" : failureCode.trim();
        if ((decision == Decision.CONTINUE) != (lease != null)
                || (decision == Decision.CONTINUE) != failureCode.isBlank()) {
            throw new IllegalArgumentException("Invalid suite-stability job lease check");
        }
    }

    /** @return successful renewal */
    public static TestSuiteStabilityJobLeaseCheck continuing(
            TestSuiteStabilityJobLease lease) {
        return new TestSuiteStabilityJobLeaseCheck(Decision.CONTINUE, lease, "");
    }

    /** @return terminal or fenced stop decision */
    public static TestSuiteStabilityJobLeaseCheck stopped(
            Decision decision, String failureCode) {
        if (decision == Decision.CONTINUE) {
            throw new IllegalArgumentException("A stop decision cannot continue");
        }
        return new TestSuiteStabilityJobLeaseCheck(decision, null, failureCode);
    }
}
