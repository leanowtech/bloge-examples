package com.leanowtech.bloge.gateway.testing.api;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Payload-free result of one bounded local stability worker poll.
 *
 * @param outcome bounded worker outcome
 * @param jobId governed job id only when durable work was acquired
 * @param failureCode stable diagnostic for non-success acquired outcomes
 */
public record TestSuiteStabilityJobWorkResult(
        Outcome outcome,
        String jobId,
        String failureCode) {

    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Closed scheduler/telemetry vocabulary without business or fixture payloads. */
    public enum Outcome {
        NO_WORK,
        LOCAL_CAPACITY,
        QUEUE_UNAVAILABLE,
        SUCCEEDED,
        RETRIED,
        FAILED,
        CANCELLED,
        DEADLINE_EXCEEDED,
        PARENT_COMPLETED,
        LEASE_LOST,
        AUTHORIZATION_REVOKED,
        CONTROL_UNAVAILABLE
    }

    /** Validates one bounded no-work or acquired-work result shape. */
    public TestSuiteStabilityJobWorkResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        jobId = jobId == null ? "" : jobId.trim();
        failureCode = failureCode == null ? "" : failureCode.trim();
        boolean noJob = outcome == Outcome.NO_WORK || outcome == Outcome.LOCAL_CAPACITY
                || outcome == Outcome.QUEUE_UNAVAILABLE;
        boolean noFailure = outcome == Outcome.NO_WORK || outcome == Outcome.LOCAL_CAPACITY
                || outcome == Outcome.SUCCEEDED;
        if (noJob != jobId.isBlank()
                || noFailure != failureCode.isBlank()
                || !failureCode.isBlank() && !CODE.matcher(failureCode).matches()) {
            throw new IllegalArgumentException("Invalid suite-stability job work result");
        }
    }

    /** @return queue had no eligible work */
    public static TestSuiteStabilityJobWorkResult noWork() {
        return new TestSuiteStabilityJobWorkResult(Outcome.NO_WORK, "", "");
    }

    /** @return no local execution slot was available, so no claim was attempted */
    public static TestSuiteStabilityJobWorkResult localCapacity() {
        return new TestSuiteStabilityJobWorkResult(Outcome.LOCAL_CAPACITY, "", "");
    }

    /** @return queue claim authority was unavailable before any job identity was acquired */
    public static TestSuiteStabilityJobWorkResult queueUnavailable() {
        return new TestSuiteStabilityJobWorkResult(
                Outcome.QUEUE_UNAVAILABLE, "", "RG.TEST.STABILITY_JOB_QUEUE_UNAVAILABLE");
    }

    /** @return successful acquired job */
    public static TestSuiteStabilityJobWorkResult succeeded(String jobId) {
        return new TestSuiteStabilityJobWorkResult(Outcome.SUCCEEDED, jobId, "");
    }

    /** @return acquired job with a bounded non-success outcome */
    public static TestSuiteStabilityJobWorkResult stopped(
            Outcome outcome,
            String jobId,
            String failureCode) {
        if (outcome == Outcome.NO_WORK || outcome == Outcome.LOCAL_CAPACITY
                || outcome == Outcome.QUEUE_UNAVAILABLE
                || outcome == Outcome.SUCCEEDED) {
            throw new IllegalArgumentException(
                    "Stopped stability work requires an acquired non-success outcome");
        }
        return new TestSuiteStabilityJobWorkResult(outcome, jobId, failureCode);
    }
}
