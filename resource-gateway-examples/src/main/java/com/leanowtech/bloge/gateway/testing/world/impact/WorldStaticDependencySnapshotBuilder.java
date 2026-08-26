package com.leanowtech.bloge.gateway.testing.world.impact;

import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.Scenario;
import com.leanowtech.bloge.gateway.testing.world.WorldDelegateBinding;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioCompilation;
import com.leanowtech.bloge.gateway.testing.world.WorldScenarioSourceMap;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Builds the static dependency chain from server-owned, exact compiled artifacts. */
public final class WorldStaticDependencySnapshotBuilder {
    public WorldStaticDependencySnapshot build(Scenario scenario, ResourceWorldModel world,
                                                WorldScenarioCompilation compilation,
                                                long sourceWatermark, Instant generatedAt) {
        if (scenario == null || world == null || compilation == null || sourceWatermark < 1
                || generatedAt == null) throw fail(WorldImpactException.Code.INVALID_INPUT);
        try {
            compilation.verifyFingerprint();
            WorldImpactSourceMapIntegrity.Verified sourceMap =
                    WorldImpactSourceMapIntegrity.verify(compilation);
            if (!scenario.tenantId().equals(world.tenantId())
                    || !scenario.world().worldModelId().equals(world.worldModelId())
                    || scenario.world().revision() != world.revision()
                    || !scenario.world().fingerprint().equals(world.fingerprint())
                    || !scenario.target().fingerprint().equals(compilation.bundle().targetFingerprint())) {
                throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
            }
            List<WorldStaticDependencySnapshot.Dependency> dependencies = new ArrayList<>();
            for (Scenario.ContractDependency dependency : scenario.contractDependencies()) {
                WorldDelegateBinding binding = exactBinding(compilation.bindings(), dependency.contractId());
                if (!dependency.baselineFingerprint().equals(binding.contractFingerprint())) {
                    throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                }
                WorldSlice slice = exactSlice(world, dependency.contractId(), binding.contractFingerprint());
                if (!binding.fragment().equals(slice.behavior())) throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
                List<String> sites = exactInvocationSites(sourceMap, binding, slice);
                dependencies.add(new WorldStaticDependencySnapshot.Dependency(binding.ruleId(),
                        binding.logicalContractId(), binding.contractFingerprint(), slice.fingerprint(),
                        binding.fragment().fingerprint(), compilation.bundle().targetFingerprint(), sites));
            }
            return WorldStaticDependencySnapshot.create(scenario.tenantId(), scenario.scenarioId(),
                    scenario.revision(), scenario.fingerprint(), world.worldModelId(), world.revision(),
                    world.fingerprint(), compilation.bundle().targetFingerprint(), sourceWatermark,
                    generatedAt, dependencies);
        } catch (WorldImpactException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
    }

    private static WorldDelegateBinding exactBinding(List<WorldDelegateBinding> bindings, String contractId) {
        List<WorldDelegateBinding> matches = bindings.stream()
                .filter(binding -> binding != null && binding.logicalContractId().equals(contractId)).toList();
        if (matches.size() != 1) throw fail(WorldImpactException.Code.MAPPING_MISSING);
        return matches.getFirst();
    }

    private static WorldSlice exactSlice(ResourceWorldModel world, String contractId, String contractFingerprint) {
        List<WorldSlice> matches = world.slices().stream()
                .filter(slice -> slice.logicalContractId().equals(contractId)
                        && slice.contractFingerprint().equals(contractFingerprint)).toList();
        if (matches.size() != 1) throw fail(WorldImpactException.Code.MAPPING_MISSING);
        return matches.getFirst();
    }

    private static List<String> exactInvocationSites(WorldImpactSourceMapIntegrity.Verified sourceMap,
                                                     WorldDelegateBinding binding, WorldSlice slice) {
        String expectedWorldSource = WorldScenarioSourceMap.coordinate("world-slice",
                binding.logicalContractId() + "@" + slice.fingerprint());
        String actualWorldSource = sourceMap.worldSourceByRule().get(binding.ruleId());
        String logical = WorldScenarioSourceMap.coordinate("logical-contract",
                binding.logicalContractId() + "@" + binding.contractFingerprint());
        List<String> sites = sourceMap.sitesForRule(binding.ruleId());
        List<String> mappedSites = sourceMap.siteToLogical().entrySet().stream()
                .filter(entry -> entry.getValue().equals(logical))
                .map(java.util.Map.Entry::getKey).sorted().toList();
        if (!sourceMap.byRule().containsKey(binding.ruleId())
                || !binding.equals(sourceMap.byRule().get(binding.ruleId()))
                || !expectedWorldSource.equals(actualWorldSource)
                || sites.isEmpty() || !mappedSites.equals(sites)) {
            throw fail(WorldImpactException.Code.SOURCE_INTEGRITY);
        }
        return sites;
    }

    private static WorldImpactException fail(WorldImpactException.Code code) {
        return WorldImpactSupport.fail(code);
    }
}
