package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationIdentityResolver;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.domain.DurableTestExecutionCheckpoint;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.GraphExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.OperatorExecutionTargetSnapshot;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.OperatorMicroGraphRunner;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DurableTestRecoveryAuthorizerTest {

    @Test
    void reauthorizesAndRecompilesAnExactGraphClosure() {
        Scenario scenario = Scenario.graph();

        DurableTestRecoveryAuthorizer.AuthorizedRecovery authorized =
                scenario.authorizer().authorize(
                        scenario.checkpoint(), identity("CONFIDENTIAL"));

        assertThat(authorized.graph()).isSameAs(scenario.executionGraph());
        assertThat(authorized.control().effectivePlan().planFingerprint())
                .isEqualTo(scenario.checkpoint().dependencies().plan().planFingerprint());
        assertThat(authorized.authorization()).satisfies(receipt -> {
            assertThat(receipt.sourceCheckpointFingerprint())
                    .isEqualTo(scenario.checkpoint().checkpointFingerprint());
            assertThat(receipt.targetFingerprint())
                    .isEqualTo(scenario.checkpoint().dependencies().target().fingerprint());
            assertThat(receipt.planFingerprint())
                    .isEqualTo(scenario.checkpoint().dependencies().plan().planFingerprint());
            assertThat(receipt.principalFingerprint()).startsWith("sha256:");
            assertThat(receipt.authorizationFingerprint()).startsWith("sha256:");
            receipt.requireValid(scenario.mapper());
        });
    }

    @Test
    void reauthorizesAnOperatorThroughTheCanonicalMicroGraph() {
        Scenario scenario = Scenario.operator();

        scenario.authorizer().authorize(scenario.checkpoint(), identity("CONFIDENTIAL"));
    }

    @Test
    void bindsRegionalAuthorityWithoutBindingTheRetryCorrelationId() {
        Scenario scenario = Scenario.graph();
        IntegrationRequestContext singapore = identity(
                "sg", "correlation-a", "CONFIDENTIAL");
        IntegrationRequestContext virginia = identity(
                "us-east", "correlation-a", "CONFIDENTIAL");
        IntegrationRequestContext retried = identity(
                "sg", "correlation-retry", "CONFIDENTIAL");

        String singaporePrincipal = scenario.authorizer().authorize(
                scenario.checkpoint(), singapore).authorization().principalFingerprint();
        String virginiaPrincipal = scenario.authorizer().authorize(
                scenario.checkpoint(), virginia).authorization().principalFingerprint();
        String retriedPrincipal = scenario.authorizer().authorize(
                scenario.checkpoint(), retried).authorization().principalFingerprint();

        assertThat(singaporePrincipal).isNotEqualTo(virginiaPrincipal);
        assertThat(retriedPrincipal).isEqualTo(singaporePrincipal);
    }

    @Test
    void rejectsTargetFixtureAndAuthorityDriftWithoutExposingValues() {
        Scenario targetDrift = Scenario.graph();
        when(targetDrift.graphService().requireGraph("graph-a"))
                .thenReturn(new GraphBuilder("changed-graph")
                        .node("subject", new ReadOnlyOperator()).build());
        assertUnavailable(() -> targetDrift.authorizer().authorize(
                targetDrift.checkpoint(), identity("CONFIDENTIAL")), "TARGET");

        Scenario fixtureDrift = Scenario.graph();
        StoredFixtureBundle stored = fixtureDrift.storedFixture();
        when(fixtureDrift.fixtures().find("tenant-a", "test", "fixture-a", 1))
                .thenReturn(Optional.of(new StoredFixtureBundle("", stored.tenantId(),
                        stored.environmentId(), stored.fixtureBundleId(), stored.revision(),
                        "sha256:" + "f".repeat(64), stored.bundle(), stored.createdAt(),
                        stored.createdBy())));
        assertUnavailable(() -> fixtureDrift.authorizer().authorize(
                fixtureDrift.checkpoint(), identity("CONFIDENTIAL")), "FIXTURE");

        Scenario authorityDrift = Scenario.graph();
        when(authorityDrift.authenticator().descriptor()).thenReturn(
                authorityDescriptor(60));
        assertUnavailable(() -> authorityDrift.authorizer().authorize(
                authorityDrift.checkpoint(), identity("CONFIDENTIAL")), "AUTHORITY");
    }

    @Test
    void enforcesCurrentFixtureClearanceBeforeReplayOrCompilation() {
        Scenario scenario = Scenario.graph();

        assertThatThrownBy(() -> scenario.authorizer().authorize(
                scenario.checkpoint(), identity("PUBLIC")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_FIXTURE_CLEARANCE_FORBIDDEN");
                });
    }

    private static void assertUnavailable(Runnable action, String kind) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(409);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_CONTROL_PLAN_UNAVAILABLE");
                    assertThat(failure.problem().details()).containsOnly(
                            org.assertj.core.data.MapEntry.entry("dependencyKind", kind));
                });
    }

    private static IntegrationRequestContext identity(String clearance) {
        return identity("sg", "correlation-a", clearance);
    }

    private static IntegrationRequestContext identity(
            String region, String correlationId, String clearance) {
        return new IntegrationRequestContext("tenant-a", "org-a", "project-a", "test",
                region, "WORKLOAD", "recovery-worker", "", "TEST_EXECUTION", correlationId,
                Set.of("test-operators"), clearance, "");
    }

    private static IntegrationIdentityResolver.Descriptor authorityDescriptor(long clockSkew) {
        return new IntegrationIdentityResolver.Descriptor(
                "SIGNED_JWT", "VERIFIED_TOKEN", true, false, true, Map.of(
                "acceptedAlgorithms", List.of("RS256"),
                "clockSkewSeconds", clockSkew,
                "maximumTokenLifetimeSeconds", 900,
                "outageFailClosed", true,
                "staleSnapshotAccepted", false));
    }

    private record Scenario(
            DurableTestRecoveryAuthorizer authorizer,
            DurableTestExecutionCheckpoint checkpoint,
            Graph executionGraph,
            ObjectMapper mapper,
            GatewayGraphService graphService,
            FixtureBundleRepository fixtures,
            StoredFixtureBundle storedFixture,
            IntegrationRequestAuthenticator authenticator
    ) {
        private static Scenario graph() {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
            ResourceRegistry resources = mock(ResourceRegistry.class);
            when(resources.all()).thenReturn(List.of());
            Graph graph = new GraphBuilder("graph-a")
                    .node("subject", new ReadOnlyOperator()).build();
            GatewayGraphService graphService = mock(GatewayGraphService.class);
            when(graphService.requireGraph("graph-a")).thenReturn(graph);
            GraphExecutionTargetSnapshot target = GraphExecutionTargetSnapshot.capture(
                    mapper, graph, resources);
            return scenario(mapper, operators, resources, graphService, graph,
                    "GRAPH", "graph-a", target.fingerprint(), "GRAPH_CONTRACT_TEST");
        }

        private static Scenario operator() {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
            operators.register("operator-a", new ReadOnlyOperator());
            ResourceRegistry resources = mock(ResourceRegistry.class);
            when(resources.all()).thenReturn(List.of());
            GatewayGraphService graphService = mock(GatewayGraphService.class);
            OperatorExecutionTargetSnapshot target = OperatorExecutionTargetSnapshot.capture(
                    mapper, "operator-a", operators, resources);
            Graph graph = OperatorMicroGraphRunner.microGraph(
                    target.operatorRef(), target.synchronousOperator());
            return scenario(mapper, operators, resources, graphService, graph,
                    "OPERATOR", "operator-a", target.fingerprint(), "OPERATOR_UNIT_TEST");
        }

        private static Scenario scenario(
                ObjectMapper mapper,
                DefaultOperatorRegistry operators,
                ResourceRegistry resources,
                GatewayGraphService graphService,
                Graph executionGraph,
                String targetKind,
                String targetId,
                String targetFingerprint,
                String purpose) {
            FixtureRule rule = new FixtureRule(FixtureRule.SCHEMA_VERSION, "subject-real",
                    FixtureRule.Selector.node("subject"), FixtureRule.Behavior.real(),
                    FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
            FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION,
                    "fixture-a", 1, targetFingerprint, "CONFIDENTIAL", null, null,
                    List.of(rule), List.of(), Map.of());
            String fixtureFingerprint = ProtocolFingerprint.of(mapper, fixture);
            FixtureBundleRepository fixtures = mock(FixtureBundleRepository.class);
            StoredFixtureBundle stored = new StoredFixtureBundle("", "tenant-a", "test",
                    "fixture-a", 1, fixtureFingerprint, fixture,
                    Instant.parse("2026-07-16T00:00:00Z"), "author-a");
            when(fixtures.find("tenant-a", "test", "fixture-a", 1))
                    .thenReturn(Optional.of(stored));
            TestReplayPayloadService replay = mock(TestReplayPayloadService.class);
            IntegrationRequestAuthenticator authenticator =
                    mock(IntegrationRequestAuthenticator.class);
            when(authenticator.descriptor()).thenReturn(authorityDescriptor(30));
            DurableTestRecoveryAuthority authority = new DurableTestRecoveryAuthority(
                    authenticator, mapper);
            DurableTestExecutionCheckpoint.AuthoritySnapshot authoritySnapshot =
                    authority.currentSnapshot();
            CompiledExecutionControl compiled = new ExecutionControlCompiler(operators, mapper)
                    .compile(executionGraph, fixture, purpose, targetFingerprint,
                            ResolvedReplayPayloads.empty());
            DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                    new DurableTestExecutionCheckpoint.ControlDependencies(
                            compiled.effectivePlan(),
                            new DurableTestExecutionCheckpoint.ExactFixtureRef(
                                    "fixture-a", 1, fixtureFingerprint),
                            "DENY_REAL", authoritySnapshot,
                            new DurableTestExecutionCheckpoint.ExecutionTargetRef(
                                    targetKind, targetId, targetFingerprint));
            DurableTestExecutionCheckpoint checkpoint =
                    mock(DurableTestExecutionCheckpoint.class);
            when(checkpoint.schemaVersion()).thenReturn(DurableTestExecutionCheckpoint.SCHEMA_VERSION);
            when(checkpoint.dependencies()).thenReturn(dependencies);
            when(checkpoint.executionServiceState()).thenReturn(
                    compiled.executionServices().snapshotState());
            DurableTestRecoveryAuthorizer authorizer = new DurableTestRecoveryAuthorizer(
                    graphService, operators, resources, fixtures, replay, authority, mapper);
            when(checkpoint.runId()).thenReturn("run-a");
            when(checkpoint.checkpointFingerprint()).thenReturn(
                    ProtocolFingerprint.ofText("checkpoint-a"));
            return new Scenario(authorizer, checkpoint, executionGraph, mapper, graphService,
                    fixtures, stored, authenticator);
        }
    }

    private static final class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }
}
