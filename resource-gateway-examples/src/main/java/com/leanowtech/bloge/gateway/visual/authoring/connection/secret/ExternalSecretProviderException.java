package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Stable, code-only provider failure; no provider cause or arbitrary message is retained. */
public final class ExternalSecretProviderException extends RuntimeException {
    /** Closed set of safe provider failure categories. */
    public enum Code {
        /** Provider could not stage the requested secret. */ PREPARE_FAILED,
        /** Provider could not activate the staged lease. */ ACTIVATE_FAILED,
        /** Provider could not complete idempotent compensation. */ ABORT_FAILED,
        /** Provider could not resolve an active binding. */ RESOLVE_FAILED,
        /** Reference or operation scope was not authorized. */ UNAUTHORIZED,
        /** Provider rejected an expired or otherwise unusable lease. */ EXPIRED,
        /** Operation input did not satisfy the provider contract. */ INVALID_REQUEST
    }
    private final Code code;
    /** Creates a safe exception whose message is exactly the normalized code name. */
    public ExternalSecretProviderException(Code code) {
        super(normalize(code).name());
        this.code = normalize(code);
    }

    private static Code normalize(Code code) { return code == null ? Code.INVALID_REQUEST : code; }

    /** @return safe machine-readable category */
    public Code code() { return code; }
    @Override public String toString() { return "ExternalSecretProviderException[code=" + code + "]"; }
}
