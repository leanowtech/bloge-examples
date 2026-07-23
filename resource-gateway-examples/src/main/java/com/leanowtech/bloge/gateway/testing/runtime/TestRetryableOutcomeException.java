package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.exception.RetryableException;

/**
 * Retryable normalized failure emitted only by a policy-revalidated recorded trajectory step.
 */
final class TestRetryableOutcomeException extends RetryableException
        implements TestOutcomeFailure {
    private final String code;
    private final String errorType;

    TestRetryableOutcomeException(
            String code, String errorType, String message) {
        super(message == null || message.isBlank()
                ? "Recorded retry trajectory failed" : message);
        this.code = code == null || code.isBlank()
                ? "RECORDED_TRAJECTORY_RETRY" : code.trim();
        this.errorType = errorType == null || errorType.isBlank()
                ? "RECORDED_TRAJECTORY" : errorType.trim();
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String errorType() {
        return errorType;
    }
}
