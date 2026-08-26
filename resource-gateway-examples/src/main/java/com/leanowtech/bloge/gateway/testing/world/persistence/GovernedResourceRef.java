package com.leanowtech.bloge.gateway.testing.world.persistence;

import java.util.regex.Pattern;

/** Exact, tenant-scoped address of one immutable catalog revision. */
public record GovernedResourceRef(
        TrustedTenant tenant,
        GovernedCatalogKind kind,
        String id,
        long revision,
        String fingerprint
) {
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    public GovernedResourceRef {
        if (tenant == null || kind == null || id == null || id.isBlank() || id.length() > 512
                || revision <= 0 || fingerprint == null || !FINGERPRINT.matcher(fingerprint).matches()) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_REF");
        }
        id = id.trim();
        fingerprint = fingerprint.trim();
    }

    public GovernedResourceRef(String tenant, GovernedCatalogKind kind, String id,
                               long revision, String fingerprint) {
        this(new TrustedTenant(tenant), kind, id, revision, fingerprint);
    }

    public String tenantId() {
        return tenant.value();
    }
}
