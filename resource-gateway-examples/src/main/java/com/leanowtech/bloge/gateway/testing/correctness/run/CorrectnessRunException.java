package com.leanowtech.bloge.gateway.testing.correctness.run;

/** Stable payload-free rejection from the correctness preflight and governed-run boundary. */
public final class CorrectnessRunException extends RuntimeException {
    private final int status;
    private final String code;
    private final boolean retryable;

    public CorrectnessRunException(int status, String code, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code.trim();
        this.retryable = retryable;
        if (status < 400 || this.code.isEmpty()) {
            throw new IllegalArgumentException("Run failure status and code are required");
        }
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}
