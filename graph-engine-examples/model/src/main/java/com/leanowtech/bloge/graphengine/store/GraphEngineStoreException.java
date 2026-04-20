package com.leanowtech.bloge.graphengine.store;

/**
 * Runtime exception raised by graph-engine metadata stores for deterministic
 * persistence errors such as duplicates, missing records, and optimistic-lock
 * conflicts.
 *
 * <p>Follows the same pattern as
 * {@link com.leanowtech.bloge.durable.DurableStoreException}.</p>
 */
public class GraphEngineStoreException extends RuntimeException {

    private final GraphEngineErrorCode errorCode;

    /**
     * Creates a store exception with the supplied error code and message.
     *
     * @param errorCode stable store error code; must not be {@code null}
     * @param message human-readable error description
     */
    public GraphEngineStoreException(GraphEngineErrorCode errorCode, String message) {
        super(message);
        if (errorCode == null) throw new IllegalArgumentException("errorCode must not be null");
        this.errorCode = errorCode;
    }

    /**
     * Creates a store exception with the supplied error code, message, and cause.
     *
     * @param errorCode stable store error code; must not be {@code null}
     * @param message human-readable error description
     * @param cause underlying cause
     */
    public GraphEngineStoreException(GraphEngineErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null) throw new IllegalArgumentException("errorCode must not be null");
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable store error code.
     *
     * @return error code for this failure; never {@code null}
     */
    public GraphEngineErrorCode errorCode() {
        return errorCode;
    }
}
