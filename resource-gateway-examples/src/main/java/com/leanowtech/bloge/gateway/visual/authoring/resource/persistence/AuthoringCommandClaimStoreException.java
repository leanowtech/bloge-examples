package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

/**
 * Payload-free failure raised by the generic command-claim seam.
 *
 * <p>Projection-specific exceptions must not cross this boundary. Adapters
 * expose only this stable vocabulary so application facades can translate a
 * claim failure without depending on Resource implementation details.</p>
 */
public final class AuthoringCommandClaimStoreException extends RuntimeException {
    /** Stable claim-only failure categories. */
    public enum Code { LEASE_FENCED, LEASE_EXPIRED, INTEGRITY, PERSISTENCE }

    private final Code code;

    /** Creates a safe error whose message is derived only from its category. */
    public AuthoringCommandClaimStoreException(Code code) {
        super(message(java.util.Objects.requireNonNull(code, "code")));
        this.code = code;
    }

    /** @return stable machine-readable category */
    public Code code() { return code; }

    @Override public String toString() {
        return getClass().getSimpleName() + "[code=" + code + "]";
    }

    private static String message(Code code) {
        return switch (code) {
            case LEASE_FENCED -> "authoring command lease is fenced";
            case LEASE_EXPIRED -> "authoring command lease is expired";
            case INTEGRITY -> "authoring command claim integrity check failed";
            case PERSISTENCE -> "authoring command claim persistence failed";
        };
    }
}
