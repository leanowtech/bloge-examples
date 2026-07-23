package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.exception.NonRetryableException;

/** A standardized, non-retryable failure emitted by an approved test-control behavior. */
public class TestControlException extends NonRetryableException
        implements TestOutcomeFailure {

    private final String code;
    private final String errorType;

    /**
     * @param code stable execution-control error code
     * @param errorType normalized error type
     * @param message bounded diagnostic message
     */
    public TestControlException(String code, String errorType, String message) {
        super(message == null || message.isBlank() ? "Test control failed" : message);
        this.code = code == null || code.isBlank() ? "TEST_CONTROL_FAILED" : code.trim();
        this.errorType = errorType == null || errorType.isBlank()
                ? "TEST_CONTROL" : errorType.trim();
    }

    /** @return stable control error code */
    public String code() {
        return code;
    }

    /** @return normalized failure type */
    public String errorType() {
        return errorType;
    }
}
