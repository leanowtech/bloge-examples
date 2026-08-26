package com.leanowtech.bloge.gateway.testing.world.persistence;

/** One validated immutable catalog revision. */
public record GovernedCatalogRevision(GovernedResourceRef ref, Object value) {
    public GovernedCatalogRevision {
        if (ref == null || value == null || !ref.kind().accepts(value)) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_REVISION");
        }
    }
}
