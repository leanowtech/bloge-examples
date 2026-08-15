package com.leanowtech.bloge.gateway.testing.correctness.compilation;

/** Stable, payload-free publication Saga failure. */
public final class CorrectnessPublicationException extends RuntimeException {

    private final int status;
    private final String code;
    private final boolean retryable;

    public CorrectnessPublicationException(
            int status,
            String code,
            String message,
            boolean retryable
    ) {
        super(message);
        this.status = status;
        this.code = code == null ? "" : code.trim();
        this.retryable = retryable;
        if (status < 400 || this.code.isEmpty()) {
            throw new IllegalArgumentException("Publication failure status and code are required");
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
