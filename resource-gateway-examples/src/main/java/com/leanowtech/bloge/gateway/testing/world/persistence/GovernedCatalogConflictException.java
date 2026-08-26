package com.leanowtech.bloge.gateway.testing.world.persistence;

/** Payload-free optimistic-concurrency failure. */
public final class GovernedCatalogConflictException extends IllegalStateException {
    public GovernedCatalogConflictException() {
        super("RG.WORLD.CATALOG.CAS_CONFLICT");
    }
}
