package com.leanowtech.bloge.gateway.visual.authoring.connection.secret;

/** Stable, code-only provider failure; message and rendering never contain provider payloads. */
public final class ExternalSecretProviderException extends RuntimeException {
    public enum Code { PREPARE_FAILED, ACTIVATE_FAILED, ABORT_FAILED, RESOLVE_FAILED, UNAUTHORIZED, EXPIRED, INVALID_REQUEST }
    private final Code code;
    public ExternalSecretProviderException(Code code) { this(code, null); }
    public ExternalSecretProviderException(Code code, Throwable cause) {
        // Do not retain provider exceptions: their messages may contain payloads.
        super(code == null ? "EXTERNAL_SECRET_PROVIDER_ERROR" : code.name());
        this.code = code == null ? Code.INVALID_REQUEST : code;
    }
    public Code code() { return code; }
    @Override public String toString() { return "ExternalSecretProviderException[code=" + code + "]"; }
}
