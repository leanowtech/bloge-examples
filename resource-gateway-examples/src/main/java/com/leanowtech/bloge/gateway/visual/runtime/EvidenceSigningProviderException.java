package com.leanowtech.bloge.gateway.visual.runtime;

/** Stable failure classification for managed KMS/HSM signing providers. */
public final class EvidenceSigningProviderException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public EvidenceSigningProviderException(String code, String message, boolean retryable) {
        super(message == null ? "Managed evidence signing provider failed" : message);
        this.code = normalize(code);
        this.retryable = retryable;
    }

    public EvidenceSigningProviderException(String code, String message, boolean retryable, Throwable cause) {
        super(message == null ? "Managed evidence signing provider failed" : message, cause);
        this.code = normalize(code);
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "PROVIDER_FAILURE" : value.trim();
    }
}
