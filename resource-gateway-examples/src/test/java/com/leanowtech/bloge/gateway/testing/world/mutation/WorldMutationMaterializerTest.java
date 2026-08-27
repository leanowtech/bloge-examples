package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.ResourceWorldModel;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorldMutationMaterializerTest {
    @Test
    void materializesAnExactReviewedMutantAfterWorldAndSliceRevalidation() {
        WorldSlice slice = WorldMutationPlannerRealTest.slice();
        ResourceWorldModel world = new ResourceWorldModel("materializer-world", "tenant-a", 1,
                List.of(slice));
        WorldMutationPlan plan = new WorldMutationPlanner().plan(world, slice,
                new WorldMutationPlan.Policy(128, false));
        WorldMutationPlan.PlannedMutant mutant = plan.mutants().getFirst();

        WorldMutationMaterializer.Materialized materialized = new WorldMutationMaterializer()
                .materialize(world, slice, plan, mutant.mutantId());

        assertThat(materialized.sourceFingerprint()).isEqualTo(mutant.mutantSourceFingerprint());
        assertThat(materialized.artifactFingerprint()).isEqualTo(mutant.mutantGraphFingerprint());
        assertThat(materialized.targetFingerprint()).isEqualTo(mutant.mutantTargetFingerprint());
        assertThat(materialized.fragment().fingerprint()).startsWith("sha256:");
    }

    @Test
    void failsClosedForCrossWorldSliceDriftAndUnknownMutant() {
        WorldSlice slice = WorldMutationPlannerRealTest.slice();
        ResourceWorldModel world = new ResourceWorldModel("materializer-world", "tenant-a", 1,
                List.of(slice));
        WorldMutationPlan plan = new WorldMutationPlanner().plan(world, slice,
                new WorldMutationPlan.Policy(128, false));
        WorldMutationMaterializer materializer = new WorldMutationMaterializer();

        ResourceWorldModel otherWorld = new ResourceWorldModel("other-world", "tenant-a", 1,
                List.of(slice));
        assertThatThrownBy(() -> materializer.materialize(otherWorld, slice, plan,
                plan.mutants().getFirst().mutantId()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.WORLD_DRIFT);

        WorldSlice driftedSlice = WorldSlice.register(new WorldSlice.Registration("tenant-a", "provider-a", "v1",
                        slice.contract().contractId(), slice.contract().contractFingerprint(),
                        slice.binding().descriptorFingerprint(), true), slice.contract(), slice.binding(),
                BlogeFragmentRef.frozen("drifted.bloge", 2,
                        "graph drifted { transform result { value = \"drifted\" } }", ""),
                StateSpec.empty());
        assertThatThrownBy(() -> materializer.materialize(world, driftedSlice, plan,
                plan.mutants().getFirst().mutantId()))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.CROSS_TENANT);

        assertThatThrownBy(() -> materializer.materialize(world, slice, plan, "unknown-mutant"))
                .isInstanceOf(WorldMutationException.class)
                .extracting("code").isEqualTo(WorldMutationException.Code.MUTANT_DRIFT);
    }
}
