package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;

import java.util.Objects;

/** Rebuilds a reviewed mutant only after the exact world and slice have been revalidated. */
public final class WorldMutationMaterializer {
    public record Materialized(BlogeFragmentRef fragment, String sourceFingerprint,
                               String artifactFingerprint, String targetFingerprint) {
        public Materialized {
            fragment = Objects.requireNonNull(fragment, "fragment");
            sourceFingerprint = fingerprint(sourceFingerprint);
            artifactFingerprint = fingerprint(artifactFingerprint);
            targetFingerprint = fingerprint(targetFingerprint);
        }
    }

    private final WorldMutationPlanner planner;

    public WorldMutationMaterializer() {
        this(new WorldMutationPlanner());
    }

    public WorldMutationMaterializer(WorldMutationPlanner planner) {
        this.planner = Objects.requireNonNull(planner, "planner");
    }

    public Materialized materialize(ResourceWorldModel world, WorldSlice slice,
                                    WorldMutationPlan plan, String mutantId) {
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(plan, "plan");
        if (!world.tenantId().equals(plan.tenantId())
                || !world.worldModelId().equals(plan.worldModelId())
                || world.revision() != plan.worldRevision()
                || !world.fingerprint().equals(plan.worldFingerprint())) {
            throw new WorldMutationException(WorldMutationException.Code.WORLD_DRIFT);
        }
        if (!world.slices().contains(slice)) {
            throw new WorldMutationException(WorldMutationException.Code.CROSS_TENANT);
        }
        try {
            plan.verifyAgainst(slice);
        } catch (RuntimeException ex) {
            throw new WorldMutationException(WorldMutationException.Code.SLICE_DRIFT);
        }
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().stream()
                .filter(candidate -> candidate.mutantId().equals(mutantId))
                .findFirst()
                .orElseThrow(() -> new WorldMutationException(WorldMutationException.Code.MUTANT_DRIFT));
        try {
            BlogeFragmentRef fragment = planner.regenerate(slice, plan, mutantId);
            String sourceFingerprint = mutant.mutantSourceFingerprint();
            String targetFingerprint = WorldMutationPlan.targetFingerprintFor(
                    plan.worldFingerprint(), plan.sliceFingerprint(), mutant.mutantGraphFingerprint());
            if (!mutant.mutantTargetFingerprint().equals(targetFingerprint)) {
                throw new WorldMutationException(WorldMutationException.Code.MUTANT_DRIFT);
            }
            return new Materialized(fragment, sourceFingerprint, mutant.mutantGraphFingerprint(),
                    targetFingerprint);
        } catch (WorldMutationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new WorldMutationException(WorldMutationException.Code.COMPILATION_FAILED);
        }
    }

    private static String fingerprint(String value) {
        if (value == null || !value.matches("sha256:[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid fingerprint");
        }
        return value;
    }
}
