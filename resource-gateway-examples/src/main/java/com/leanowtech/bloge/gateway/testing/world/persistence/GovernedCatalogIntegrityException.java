package com.leanowtech.bloge.gateway.testing.world.persistence;

/** Payload-free failure raised when authoritative catalog data no longer verifies. */
public final class GovernedCatalogIntegrityException extends IllegalStateException {
    public GovernedCatalogIntegrityException() {
        super("RG.WORLD.CATALOG.INTEGRITY");
    }

    public GovernedCatalogIntegrityException(Throwable cause) {
        super("RG.WORLD.CATALOG.INTEGRITY", cause);
    }
}
