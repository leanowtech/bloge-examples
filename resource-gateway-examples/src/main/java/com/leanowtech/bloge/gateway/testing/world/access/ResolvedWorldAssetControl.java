package com.leanowtech.bloge.gateway.testing.world.access;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedCatalogKind;
import com.leanowtech.bloge.gateway.testing.world.persistence.GovernedResourceRef;

import java.util.Objects;
import java.util.Optional;

/** Exact, immutable result of an authorized World or Scenario reference resolution. */
public record ResolvedWorldAssetControl(
        GovernedResourceRef primaryRef,
        Optional<Scenario> scenario,
        ResourceWorldModel worldModel) {
    public ResolvedWorldAssetControl {
        Objects.requireNonNull(primaryRef, "primaryRef");
        Objects.requireNonNull(scenario, "scenario");
        Objects.requireNonNull(worldModel, "worldModel");
        if (primaryRef.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL) {
            if (scenario.isPresent() || !sameWorld(primaryRef, worldModel)) {
                throw GovernedAssetAccessException.integrity();
            }
        } else if (primaryRef.kind() == GovernedCatalogKind.SCENARIO) {
            Scenario exactScenario = scenario.orElseThrow(GovernedAssetAccessException::integrity);
            if (!primaryRef.tenantId().equals(exactScenario.tenantId())
                    || !primaryRef.tenantId().equals(worldModel.tenantId())
                    || !primaryRef.id().equals(exactScenario.scenarioId())
                    || primaryRef.revision() != exactScenario.revision()
                    || !primaryRef.fingerprint().equals(exactScenario.fingerprint())
                    || !sameWorld(exactScenario.world(), worldModel)) {
                throw GovernedAssetAccessException.integrity();
            }
        } else {
            throw GovernedAssetAccessException.integrity();
        }
    }

    public static ResolvedWorldAssetControl world(GovernedResourceRef ref,
                                                   ResourceWorldModel worldModel) {
        return new ResolvedWorldAssetControl(ref, Optional.empty(), worldModel);
    }

    public static ResolvedWorldAssetControl scenario(GovernedResourceRef ref,
                                                      Scenario scenario,
                                                      ResourceWorldModel worldModel) {
        return new ResolvedWorldAssetControl(ref, Optional.of(scenario), worldModel);
    }

    private static boolean sameWorld(GovernedResourceRef ref, ResourceWorldModel world) {
        return ref.kind() == GovernedCatalogKind.RESOURCE_WORLD_MODEL
                && ref.tenantId().equals(world.tenantId())
                && ref.id().equals(world.worldModelId())
                && ref.revision() == world.revision()
                && ref.fingerprint().equals(world.fingerprint());
    }

    private static boolean sameWorld(Scenario.WorldModelRef ref, ResourceWorldModel world) {
        return ref != null && ref.worldModelId().equals(world.worldModelId())
                && ref.revision() == world.revision()
                && ref.fingerprint().equals(world.fingerprint());
    }
}
