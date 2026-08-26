package com.leanowtech.bloge.gateway.testing.world.persistence;

import java.util.List;
import java.util.Optional;

/** Generic persistence contract for the Stage 1 governed asset catalog. */
public interface GovernedCatalogRepository {
    GovernedResourceRef create(TrustedTenant tenant, GovernedCatalogKind kind, String id, Object value);

    default GovernedResourceRef create(String tenant, GovernedCatalogKind kind, String id, Object value) {
        return create(new TrustedTenant(tenant), kind, id, value);
    }

    GovernedResourceRef update(GovernedResourceRef expected, Object value);

    Optional<GovernedCatalogRevision> findExact(GovernedResourceRef ref);

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
