package com.leanowtech.bloge.graphengine.ai;

/**
 * Raised when the AI authoring loop cannot complete because the provider or prompt resources
 * failed in a non-validation way.
 */
public class GraphAuthoringException extends RuntimeException {

    /**
     * Creates a new exception with one human-readable message.
     *
     * @param message failure detail
     */
    public GraphAuthoringException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with one human-readable message and cause.
     *
     * @param message failure detail
     * @param cause original provider failure
     */
    public GraphAuthoringException(String message, Throwable cause) {
        super(message, cause);
    }
}
