package com.leanowtech.bloge.gateway.testing.runtime;

/**
 * Payload-free normalized failure facts shared by retryable and non-retryable test outcomes.
 */
interface TestOutcomeFailure {
    /** @return stable normalized error code */
    String code();

    /** @return stable normalized error type */
    String errorType();
}
