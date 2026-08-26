package com.leanowtech.bloge.gateway.testing.world.persistence;

/** Explicitly validated tenant identity used at the catalog trust boundary. */
public record TrustedTenant(String value) {
    public TrustedTenant {
        value = value == null ? "" : value.trim();
        if (value.isEmpty() || value.length() > 255) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_TENANT");
        }
    }
}
