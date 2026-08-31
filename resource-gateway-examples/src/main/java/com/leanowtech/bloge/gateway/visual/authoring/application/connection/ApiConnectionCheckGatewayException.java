package com.leanowtech.bloge.gateway.visual.authoring.application.connection;

/** Code-only failure from a governed Connection check provider. */
public final class ApiConnectionCheckGatewayException extends RuntimeException {
    /** Closed provider failure vocabulary. */
    public enum Code { CAPABILITY_UNAVAILABLE, INTEGRITY, PERSISTENCE }

    private final Code code;

    /** Creates a safe provider failure without a provider-supplied message. */
    public ApiConnectionCheckGatewayException(Code code) {
        super(message(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
    }

    /** @return stable provider failure category */
    public Code code() { return code; }

    @Override public String toString() { return getClass().getSimpleName() + "[code=" + code + "]"; }

    private static String message(Code code) {
        return switch (code) {
            case CAPABILITY_UNAVAILABLE -> "connection check capability is unavailable";
            case INTEGRITY -> "connection check evidence is invalid";
            case PERSISTENCE -> "connection check provider is unavailable";
        };
    }
}
