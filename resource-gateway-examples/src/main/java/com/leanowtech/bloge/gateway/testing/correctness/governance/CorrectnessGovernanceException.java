package com.leanowtech.bloge.gateway.testing.correctness.governance;

/** Stable payload-free failure for calibration and governance projection commands. */
public final class CorrectnessGovernanceException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    public CorrectnessGovernanceException(
            int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code.trim();
        this.retryable = retryable;
        if (status < 400 || this.code.isEmpty()) {
            throw new IllegalArgumentException("Governance failure status and code are required");
        }
    }

    public int status() { return status; }
    public String code() { return code; }
    public boolean retryable() { return retryable; }
}
