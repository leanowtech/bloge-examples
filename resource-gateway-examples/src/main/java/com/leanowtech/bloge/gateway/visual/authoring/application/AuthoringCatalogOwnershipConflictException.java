package com.leanowtech.bloge.gateway.visual.authoring.application;

/**
 * Raised when a canonical library id is already owned by another enterprise scope.
 */
public final class AuthoringCatalogOwnershipConflictException extends RuntimeException {

    public AuthoringCatalogOwnershipConflictException() {
        super("Authoring catalog ownership conflict");
    }

    public AuthoringCatalogOwnershipConflictException(Throwable cause) {
        super("Authoring catalog ownership conflict", cause);
    }
}
