package com.leanowtech.bloge.gateway.testing.world;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Resolves the unique stateless world slice for every dependency of an exact Scenario. */
public final class WorldSliceSelectionResolver {

    /**
     * Derives compiler selections from the scenario's declared logical-contract dependencies.
     * A slice is eligible only when the existing Scenario compatibility validation allows its
     * contract for automatic use.
     */
    public Map<String, WorldSliceSelection> resolve(Scenario scenario, ResourceWorldModel world) {
        requireInputs(scenario, world);
        requireExactWorld(scenario, world);

        Map<String, WorldSliceSelection> selections = new TreeMap<>();
        for (Scenario.ContractDependency dependency : scenario.contractDependencies()) {
            WorldSlice selected = null;
            int eligible = 0;
            for (WorldSlice slice : world.slices()) {
                if (!dependency.contractId().equals(slice.logicalContractId())
                        || !isAutomaticallyCompatible(scenario, dependency, slice)) {
                    continue;
                }
                selected = slice;
                eligible++;
            }
            if (eligible == 0) {
                throw failure(WorldScenarioCompilationException.Code.SELECTION_MISSING);
            }
            if (eligible > 1) {
                throw failure(WorldScenarioCompilationException.Code.SELECTION_NOT_UNIQUE);
            }
            selections.put(dependency.contractId(), new WorldSliceSelection(
                    selected.provider(), selected.apiVersion(), selected.fingerprint()));
        }
        return Collections.unmodifiableMap(selections);
    }

    private static boolean isAutomaticallyCompatible(Scenario scenario,
                                                       Scenario.ContractDependency dependency,
                                                       WorldSlice slice) {
        try {
            return scenario.validateCompatibility(dependency.contractId(), slice.contract()).valid();
        } catch (ScenarioException rejected) {
            return false;
        }
    }

    private static void requireInputs(Scenario scenario, ResourceWorldModel world) {
        if (scenario == null || world == null) {
            throw failure(WorldScenarioCompilationException.Code.INVALID_INPUT);
        }
    }

    private static void requireExactWorld(Scenario scenario, ResourceWorldModel world) {
        Scenario.WorldModelRef reference = scenario.world();
        if (!scenario.tenantId().equals(world.tenantId())
                || !reference.worldModelId().equals(world.worldModelId())
                || reference.revision() != world.revision()
                || !reference.fingerprint().equals(world.fingerprint())) {
            throw failure(WorldScenarioCompilationException.Code.WORLD_DRIFT);
        }
    }

    private static WorldScenarioCompilationException failure(WorldScenarioCompilationException.Code code) {
        return new WorldScenarioCompilationException(code);
    }
}
