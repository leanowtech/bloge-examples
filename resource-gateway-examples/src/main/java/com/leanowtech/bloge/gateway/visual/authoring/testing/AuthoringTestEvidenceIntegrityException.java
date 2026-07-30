package com.leanowtech.bloge.gateway.visual.authoring.testing;

/**
 * Raised when persisted authoring test evidence cannot prove immutable-content integrity.
 */
public final class AuthoringTestEvidenceIntegrityException extends RuntimeException {

    public AuthoringTestEvidenceIntegrityException() {
        super("Authoring test evidence integrity verification failed");
    }

    public AuthoringTestEvidenceIntegrityException(Throwable cause) {
        super("Authoring test evidence integrity verification failed", cause);
    }
}
