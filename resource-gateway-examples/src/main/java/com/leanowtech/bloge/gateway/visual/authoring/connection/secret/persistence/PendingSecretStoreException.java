package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

/** Stable, non-sensitive failure from the pending-secret persistence seam. */
public final class PendingSecretStoreException extends RuntimeException {
    /** Failure categories intentionally suitable for durable telemetry and API mapping. */
    public enum Code {
        /** The future JDBC implementation requires an active transaction. */
        TRANSACTION_REQUIRED,
        /** The supplied command attempt is not the stored fencing attempt. */
        LEASE_FENCED,
        /** The supplied command attempt has expired. */
        LEASE_EXPIRED,
        /** The requested exact staged batch does not exist. */
        STAGE_MISSING,
        /** Activation output does not exactly match the staged batch. */
        ACTIVATION_MISMATCH,
        /** The requested recovery transition is not valid for the stored state. */
        RECOVERY_STATE,
        /** Stored or supplied data violates an integrity invariant. */
        INTEGRITY
    }

    private final Code code;

    /** Creates a safe exception whose message is exactly the normalized code. */
    public PendingSecretStoreException(Code code) {
        super(code == null ? Code.INTEGRITY.name() : code.name());
        this.code = code == null ? Code.INTEGRITY : code;
    }

    /** @return stable machine-readable failure category */
    public Code code() { return code; }

    @Override public String toString() {
        return "PendingSecretStoreException[code=" + code + "]";
    }
}
