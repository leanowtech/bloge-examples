package com.leanowtech.bloge.gateway.testing.world.persistence;

import java.util.List;
import java.util.Optional;

/** Generic persistence contract for the Stage 1 governed asset catalog. */
public interface GovernedCatalogRepository {
    GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id, Object value);

    default GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                                       Object value, GovernedAssetGovernance governance) {
        return create(tenant, kind, id, value, new GovernedAssetMetadata(governance));
    }

    default GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id,
                                       Object value, GovernedAssetMetadata metadata) {
        throw new UnsupportedOperationException("RG.WORLD.CATALOG.METADATA_CREATE_UNSUPPORTED");
    }

    default GovernedResourceRef create(String tenant, GovernedCatalogKind kind, String id, Object value) {
        return create(new TrustedTenant(tenant), kind, id, value);
    }

    default GovernedResourceRef create(String tenant, GovernedCatalogKind kind, String id,
                                       Object value, GovernedAssetMetadata metadata) {
        return create(new TrustedTenant(tenant), kind, id, value, metadata);
    }

    default GovernedResourceRef create(String tenant, GovernedCatalogKind kind, String id,
                                       Object value, GovernedAssetGovernance governance) {
        return create(new TrustedTenant(tenant), kind, id, value, governance);
    }

    GovernedResourceRef update(GovernedResourceRef expected, Object value);

    default GovernedResourceRef update(GovernedResourceRef expected, Object value,
                                       GovernedAssetGovernance governance) {
        return update(expected, value, new GovernedAssetMetadata(governance));
    }

    default GovernedResourceRef update(GovernedResourceRef expected, Object value,
                                       GovernedAssetMetadata metadata) {
        throw new UnsupportedOperationException("RG.WORLD.CATALOG.METADATA_UPDATE_UNSUPPORTED");
    }

    Optional<GovernedCatalogRevision> findExact(GovernedResourceRef ref);

    /** Reads only identity and governance columns; canonical_json is never selected. */
    default Optional<GovernedAssetMetadata> findMetadata(GovernedResourceRef ref) {
        return Optional.empty();
    }

    /** Reads an exact asset with caller-controlled Scenario dependency resolution. */
    Optional<GovernedCatalogRevision> findExact(GovernedResourceRef ref,
                                                GovernedCatalogDependencyResolver dependencyResolver);

    default <T> Optional<T> findExact(GovernedResourceRef ref, Class<T> valueType) {
        if (valueType == null) {
            return Optional.empty();
        }
        return findExact(ref).filter(entry -> valueType.isInstance(entry.value()))
                .map(entry -> valueType.cast(entry.value()));
    }

    List<GovernedCatalogRevision> history(TrustedTenant tenant, GovernedCatalogKind kind, String id);

    default List<GovernedCatalogRevision> history(String tenant, GovernedCatalogKind kind, String id) {
        return history(new TrustedTenant(tenant), kind, id);
    }

    default Optional<GovernedCatalogRevision> latest(TrustedTenant tenant,
                                                     GovernedCatalogKind kind,
                                                     String id) {
        return history(tenant, kind, id).stream().findFirst();
    }
}
