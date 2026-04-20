package com.leanowtech.bloge.graphengine.service;

/**
 * Runtime exception raised by the graph-engine product service when a request is
 * invalid for the current product/runtime state.
 */
public class GraphEngineServiceException extends RuntimeException {

    private final GraphEngineServiceErrorCode errorCode;

    /**
     * Creates a service exception with a stable product-layer error code.
     *
     * @param errorCode stable error code
     * @param message human-readable failure message
     */
    public GraphEngineServiceException(GraphEngineServiceErrorCode errorCode, String message) {
        super(message);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = errorCode;
    }

    /**
     * Creates a service exception with a stable product-layer error code.
     *
     * @param errorCode stable error code
     * @param message human-readable failure message
     * @param cause root cause
     */
    public GraphEngineServiceException(GraphEngineServiceErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        if (errorCode == null) {
            throw new IllegalArgumentException("errorCode must not be null");
        }
        this.errorCode = errorCode;
    }

    /**
     * Returns the stable product-layer error code.
     *
     * @return service error code
     */
    public GraphEngineServiceErrorCode errorCode() {
        return errorCode;
    }
}
