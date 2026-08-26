package com.leanowtech.bloge.gateway.testing.world.persistence;

/** One validated immutable catalog revision, including its sealed payload-free metadata. */
public record GovernedCatalogRevision(GovernedResourceRef ref, Object value,
                                      GovernedAssetMetadata metadata) {
    public GovernedCatalogRevision(GovernedResourceRef ref, Object value) {
        this(ref, value, safeMetadata(ref));
    }

    public GovernedCatalogRevision {
        if (ref == null || value == null || metadata == null || !ref.kind().accepts(value)) {
            throw new IllegalArgumentException("RG.WORLD.CATALOG.INVALID_REVISION");
        }
    }

    private static GovernedAssetMetadata safeMetadata(GovernedResourceRef ref) {
        GovernedAssetGovernance governance = GovernedAssetGovernance.safeDefaults();
        return new GovernedAssetMetadata(ref, governance,
                GovernedAssetMetadata.fingerprint(ref, governance));
    }
}
