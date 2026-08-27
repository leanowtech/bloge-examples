package com.leanowtech.bloge.gateway.testing.world.mutation;

import com.leanowtech.bloge.gateway.testing.world.BlogeFragmentRef;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceBinding;
import com.leanowtech.bloge.gateway.testing.world.LogicalResourceContract;
import com.leanowtech.bloge.gateway.testing.world.ResponseSemantics;
import com.leanowtech.bloge.gateway.testing.world.StateKeySpec;
import com.leanowtech.bloge.gateway.testing.world.StateSpec;
import com.leanowtech.bloge.gateway.testing.world.StateSpecV2;
import com.leanowtech.bloge.gateway.testing.world.WorldStateSpec;
import com.leanowtech.bloge.gateway.testing.world.WorldSlice;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.resource.ResourceDesignContract;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceDescriptor;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceParameterMapping;
import com.leanowtech.bloge.gateway.visual.resource.VisualResourceResponseProtocol;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class WorldMutationPlannerRealTest {
    @Test
    void plansAndRegeneratesRealBlogeAstMutantsDeterministically() {
        WorldSlice slice = slice();
        WorldMutationPlan.Policy policy = new WorldMutationPlan.Policy(128, true);
        WorldMutationPlanner planner = new WorldMutationPlanner();

        WorldMutationPlan first = planner.plan(slice, policy);
        WorldMutationPlan replay = planner.plan(slice, policy);

        assertThat(first).isEqualTo(replay);
        assertThat(first.mutants()).isNotEmpty();
        assertThat(first.mutants()).allSatisfy(mutant -> {
            assertThat(mutant.mutantSourceFingerprint()).isNotEqualTo(slice.behavior().fingerprint());
            assertThat(mutant.mutantTargetFingerprint()).isEqualTo(WorldMutationPlan.targetFingerprintFor(
                    first.worldFingerprint(), first.sliceFingerprint(), mutant.mutantGraphFingerprint()));
            assertThat(mutant.toString()).doesNotContain("ctx.", "approved", "customer");
            assertThat(planner.regenerate(slice, first, mutant.mutantId()).source())
                    .isNotBlank();
        });
        assertThat(first.gaps()).allSatisfy(gap -> assertThat(gap.toString())
                .doesNotContain("ctx.", "approved", "customer"));
        assertThat(first.mutants()).extracting(WorldMutationPlan.PlannedMutant::kind)
                .contains(WorldMutationPlan.MutationKind.RULE_DELETED,
                        WorldMutationPlan.MutationKind.DECISION_CONDITION_REVERSED,
                        WorldMutationPlan.MutationKind.BOUNDARY_VALUE_REPLACED,
                        WorldMutationPlan.MutationKind.RESULT_CHANGED);
        assertThat(first.gaps()).anyMatch(gap -> gap.kind()
                == WorldMutationPlan.MutationKind.DEFAULT_RULE_PRIORITY_CHANGED
                && gap.code().equals("MUTANT_COMPILATION_REJECTED"));
    }

    @Test
    void resultMutationSwapsAdjacentBusinessResultsInRecompiledAst() {
        WorldSlice slice = slice();
        WorldMutationPlanner planner = new WorldMutationPlanner();
        WorldMutationPlan plan = planner.plan(slice, new WorldMutationPlan.Policy(128, true));
        WorldMutationPlan.PlannedMutant result = plan.mutants().stream()
                .filter(mutant -> mutant.kind() == WorldMutationPlan.MutationKind.RESULT_CHANGED)
                .findFirst().orElseThrow();

        String source = planner.regenerate(slice, plan, result.mutantId()).source();
        assertThat(source.indexOf("\"approved\""))
                .isGreaterThan(source.indexOf("\"review\""));
        assertThat(new com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit()
                .admit(BlogeFragmentRef.frozen("result-mutant.bloge", source))).isNotNull();
    }

    @Test
    void stateWriteMutationDeletesOneExactEntryAndStillAdmitsAndCompiles() {
        WorldSlice slice = statefulSlice();
        WorldMutationPlanner planner = new WorldMutationPlanner();
        WorldMutationPlan plan = planner.plan(slice, new WorldMutationPlan.Policy(32, true));
        WorldMutationPlan.PlannedMutant stateWrite = plan.mutants().stream()
                .filter(mutant -> mutant.kind() == WorldMutationPlan.MutationKind.STATE_WRITE_DROPPED)
                .findFirst().orElseThrow();

        assertThat(stateWrite.site().astPath()).contains("/fields/1/value/fields/0");
        BlogeFragmentRef regenerated = planner.regenerate(slice, plan, stateWrite.mutantId());
        assertThat(regenerated.source()).doesNotContain("balance: ctx.state.balance - ctx.request.amount");
        assertThat(new com.leanowtech.bloge.gateway.testing.world.WorldFragmentTestKit()
                .admit(regenerated)).isNotNull();
    }

    static WorldSlice slice() {
        SchemaEnvelope schema = new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("result", Map.of("type", "string")),
                "required", List.of("result"),
                "additionalProperties", false));
        LogicalResourceContract contract = new LogicalResourceContract("logical.mutation", schema, schema,
                ResponseSemantics.confirmed("http.status in 200..299", Map.of(),
                        ResponseSemantics.Idempotency.IDEMPOTENT,
                        ResponseSemantics.Retryability.CONDITIONAL));
        ResourceDesignContract design = new ResourceDesignContract("logical.mutation", "logical.mutation",
                "Mutation fixture", "", List.of(), schema, schema, Map.of(), "ACTIVE");
        VisualResourceDescriptor descriptor = new VisualResourceDescriptor("logical.mutation",
                "https://example.test/mutation", "GET", Map.of(), null, Duration.ofSeconds(2),
                VisualResourceParameterMapping.empty(), new VisualResourceResponseProtocol.HttpStatus(), "");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider-a", "v1", design,
                descriptor, contract);
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", "provider-a", "v1",
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("mutation-fixture.bloge", 1, dsl(), ""),
                StateSpec.empty());
    }

    private static WorldSlice statefulSlice() {
        SchemaEnvelope schema = new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object", "properties", Map.of("result", Map.of("type", "string")),
                "required", List.of("result"), "additionalProperties", false));
        LogicalResourceContract contract = new LogicalResourceContract("logical.stateful-mutation", schema, schema,
                ResponseSemantics.confirmed("http.status in 200..299", Map.of(),
                        ResponseSemantics.Idempotency.IDEMPOTENT, ResponseSemantics.Retryability.CONDITIONAL));
        ResourceDesignContract design = new ResourceDesignContract("logical.stateful-mutation",
                "logical.stateful-mutation", "Stateful mutation fixture", "", List.of(), schema, schema,
                Map.of(), "ACTIVE");
        VisualResourceDescriptor descriptor = new VisualResourceDescriptor("logical.stateful-mutation",
                "https://example.test/stateful-mutation", "GET", Map.of(), null, Duration.ofSeconds(2),
                VisualResourceParameterMapping.empty(), new VisualResourceResponseProtocol.HttpStatus(), "");
        LogicalResourceBinding binding = LogicalResourceBinding.bind("provider-a", "v1", design,
                descriptor, contract);
        WorldStateSpec state = StateSpecV2.of(List.of(new StateKeySpec("/balance",
                StateKeySpec.Access.READ_WRITE, Map.of("type", "integer"), 100)));
        return WorldSlice.register(new WorldSlice.Registration("tenant-a", "provider-a", "v1",
                        contract.contractId(), contract.contractFingerprint(), binding.descriptorFingerprint(), true),
                contract, binding, BlogeFragmentRef.frozen("stateful-mutation-fixture.bloge", 1,
                        statefulDsl(), ""), state);
    }

    private static String dsl() {
        return """
                graph mutationExample {
                  decision_table policy(score = ctx.score) hit=first -> String {
                    rule (score: score >= 80) -> "approved"
                    rule (score: score >= 60) -> "review"
                    otherwise -> "declined"
                  }
                  transform output {
                    result = policy.output
                  }
                }
                """;
    }

    private static String statefulDsl() {
        return """
                graph statefulWorld {
                  transform result {
                    response = { accepted: ctx.request.amount > 0, before: ctx.state.balance }
                    stateWrites = { balance: ctx.state.balance - ctx.request.amount }
                  }
                }
                """;
    }
}
