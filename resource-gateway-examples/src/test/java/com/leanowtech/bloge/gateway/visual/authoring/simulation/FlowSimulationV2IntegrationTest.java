package com.leanowtech.bloge.gateway.visual.authoring.simulation;

import com.leanowtech.bloge.gateway.visual.authoring.fixture.persistence.FixtureSetAuthorityReader;
import com.leanowtech.bloge.gateway.visual.authoring.flow.ReusableFlowDraftStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.ApiResourceCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.StoredApiResource;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowFixturePlanCompilerV2Test.JSON;
import static com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowFixturePlanCompilerV2Test.SCOPE;
import static com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowFixturePlanCompilerV2Test.fixture;
import static com.leanowtech.bloge.gateway.visual.authoring.simulation.FlowFixturePlanCompilerV2Test.publications;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowSimulationV2IntegrationTest {
    @Test
    void exactCompilerAndRuntimeProducePerNodeEvidenceForOneNestedMultiApiDag() {
        FixtureSetAuthorityReader fixtures = mock(FixtureSetAuthorityReader.class);
        SimulationCommandV2.ExactFixtureSetRef profileFixture =
                fixture(fixtures, "profile-fixtures", "profile", "sha256:" + "c".repeat(64));
        SimulationCommandV2.ExactFixtureSetRef creditFixture =
                fixture(fixtures, "credit-fixtures", "credit", "sha256:" + "d".repeat(64));
        FixturePlanCompiler fixturePlans = new FixturePlanCompiler(fixtures);
        FlowFixturePlanCompilerV2 flowPlans = new FlowFixturePlanCompilerV2(
                publications(), mock(ReusableFlowDraftStore.class), fixturePlans);
        ApiResourceCommitStore resources = resources();
        AtomicInteger invocation = new AtomicInteger();
        FlowSimulationModuleV2 flows = new FlowSimulationModuleV2(
                resources, flowPlans, null, null, null, new InMemorySimulationRunV2Store(),
                Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC),
                () -> "sim-nested-integration", () -> "inv-" + invocation.incrementAndGet());
        SimulationCommandV2 command = new SimulationCommandV2(SimulationCommandV2.SCHEMA_VERSION,
                new ExactFixtureSubjectRefV2.FlowVersion(
                        "root", 1, "sha256:" + "a".repeat(64)),
                new SimulationCommandV2.Input.Inline(JSON.createObjectNode().put("customerId", "c-1")),
                new SimulationCommandV2.FixturePlan.Bindings(SimulationCommandV2.Unmatched.BLOCK, List.of(
                        binding(List.of("profile"), profileFixture),
                        binding(List.of("risk", "credit"), creditFixture))),
                SimulationCommandV2.ExecutionPolicy.denyAll());

        SimulationRunV2 run = flows.execute(SCOPE, "nested-integration", command, null).run();

        assertThat(run.status()).isEqualTo(SimulationRunV2.Status.SUCCEEDED);
        assertThat(run.output()).isEqualTo(JSON.createObjectNode().put("risk", "high"));
        assertThat(run.invocations()).hasSize(3);
        assertThat(run.invocations()).extracting(SimulationRunV2.Invocation::invocationKey)
                .containsExactly("inv-1", "inv-2", "inv-3");
        assertThat(run.invocations().get(1).execution()).isEqualTo(SimulationRunV2.Execution.REAL);
        assertThat(run.invocations().get(2).parentInvocationKey()).isEqualTo("inv-2");
        assertThat(run.invocations().get(2).target()).isEqualTo(
                new SimulationCommandV2.FixtureTarget.NodePath(List.of("risk", "credit")));
    }

    private static SimulationCommandV2.FixtureBinding binding(
            List<String> path, SimulationCommandV2.ExactFixtureSetRef fixture) {
        return new SimulationCommandV2.FixtureBinding(
                new SimulationCommandV2.FixtureTarget.NodePath(path),
                new SimulationCommandV2.FixtureSelection.ExactCase(fixture, "low-score"));
    }

    private static ApiResourceCommitStore resources() {
        ApiResourceCommitStore resources = mock(ApiResourceCommitStore.class);
        resource(resources, "profile", "sha256:" + "c".repeat(64));
        resource(resources, "credit", "sha256:" + "d".repeat(64));
        return resources;
    }

    private static void resource(ApiResourceCommitStore resources, String id, String fingerprint) {
        StoredApiResource stored = mock(StoredApiResource.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(stored.resource().resourceId()).thenReturn(id);
        when(stored.resource().revision()).thenReturn(1);
        when(stored.resource().fingerprint()).thenReturn(fingerprint);
        when(stored.resource().contract().input()).thenReturn(schema());
        when(stored.resource().contract().output()).thenReturn(schema());
        when(resources.findRevision(SCOPE, id, 1)).thenReturn(Optional.of(stored));
    }

    private static SchemaEnvelope schema() {
        return new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", Map.of(
                "type", "object", "additionalProperties", true));
    }
}
