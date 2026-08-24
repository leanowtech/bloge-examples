package com.leanowtech.bloge.gateway.testkit;

/**
 * Package-private protocol exception for Gate A self-test failures.
 *
 * <p>Error codes are stable and machine-readable. No sensitive data is included in messages.</p>
 */
final class CapabilityStudioGateAException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String errorCode;

    CapabilityStudioGateAException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    CapabilityStudioGateAException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable machine-readable error code.
     */
    public String errorCode() {
        return errorCode;
    }
}
