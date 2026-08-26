package com.leanowtech.bloge.gateway.testing.world.persistence;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;

/** Resolves the exact World dependency while restoring a Scenario. */
@FunctionalInterface
public interface GovernedCatalogDependencyResolver {
    ResourceWorldModel resolve(GovernedResourceRef exactWorldRef);
}
