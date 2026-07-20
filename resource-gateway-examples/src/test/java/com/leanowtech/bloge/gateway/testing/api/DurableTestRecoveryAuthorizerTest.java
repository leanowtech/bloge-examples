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
    void authorizesFreshCreationFromExactGraphFixtureAndAuthorityClosure() {
        Scenario scenario = Scenario.graph();
        DurableTestExecutionCreateRequest request = creationRequest(scenario);

        DurableTestRecoveryAuthorizer.AuthorizedCreation authorized =
                scenario.authorizer().authorizeCreation(
                        request, identity("CONFIDENTIAL"));

        assertThat(authorized.graph()).isSameAs(scenario.executionGraph());
        assertThat(authorized.dependencies()).satisfies(dependencies -> {
            assertThat(dependencies.target())
                    .isEqualTo(scenario.checkpoint().dependencies().target());
            assertThat(dependencies.fixture())
                    .isEqualTo(scenario.checkpoint().dependencies().fixture());
            assertThat(dependencies.plan().planFingerprint())
                    .isEqualTo(scenario.checkpoint().dependencies().plan().planFingerprint());
            assertThat(dependencies.identitySnapshot())
                    .isEqualTo(scenario.checkpoint().dependencies().identitySnapshot());
            assertThat(dependencies.sideEffectPolicy()).isEqualTo("DENY_REAL");
        });
        assertThat(authorized.authorizationFingerprint()).startsWith("sha256:");
        assertThat(authorized.control().executionServices().snapshotState().restorable())
                .isTrue();
    }

    @Test
    void freshCreationFailsClosedOnExactTargetDriftAndFixtureClearance() {
        Scenario targetDrift = Scenario.graph();
        when(targetDrift.graphService().requireGraph("graph-a"))
                .thenReturn(new GraphBuilder("changed-graph")
                        .node("subject", new ReadOnlyOperator()).build());
        assertUnavailable(() -> targetDrift.authorizer().authorizeCreation(
                creationRequest(targetDrift), identity("CONFIDENTIAL")), "TARGET");

        Scenario clearance = Scenario.graph();
        assertThatThrownBy(() -> clearance.authorizer().authorizeCreation(
                creationRequest(clearance), identity("PUBLIC")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(403);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_FIXTURE_CLEARANCE_FORBIDDEN");
                });
    }

    @Test
    void authorizesFreshOperatorCreationThroughCanonicalTypedMicroGraph() {
        Scenario scenario = Scenario.operator();
        DurableOperatorTestExecutionCreateRequest request = operatorCreationRequest(
                scenario, "operator-a", Map.of("value", "Ada"));

        DurableTestRecoveryAuthorizer.AuthorizedOperatorCreation created =
                scenario.authorizer().authorizeOperatorCreation(
                        "operator-a", request, identity("CONFIDENTIAL"));

        assertThat(created.authorization().graph().name())
                .isEqualTo("durable-operator-test:operator-a");
        assertThat(created.authorization().graph().sourceNodes())
                .containsExactly(OperatorMicroGraphRunner.DURABLE_START_NODE_ID);
        assertThat(created.authorization().dependencies()).satisfies(dependencies -> {
            assertThat(dependencies.target().kind()).isEqualTo("OPERATOR");
            assertThat(dependencies.target().id()).isEqualTo("operator-a");
            assertThat(dependencies.plan().authorizedPurpose()).isEqualTo("OPERATOR_UNIT_TEST");
            assertThat(dependencies.fixture())
                    .isEqualTo(scenario.checkpoint().dependencies().fixture());
        });
        assertThat(created.context()).containsOnly(
                org.assertj.core.data.MapEntry.entry("operatorInput", "Ada"));
    }

    @Test
    void freshOperatorCreationRejectsPathDriftBeforeFixtureResolution() {
        Scenario scenario = Scenario.operator();

        assertUnavailable(() -> scenario.authorizer().authorizeOperatorCreation(
                "operator-b", operatorCreationRequest(
                        scenario, "operator-a", Map.of("value", "Ada")),
                identity("CONFIDENTIAL")), "TARGET");
    }

    @Test
    void freshOperatorCreationRejectsInputThatCannotSatisfyTheFrozenType() {
        Scenario scenario = Scenario.operator();

        assertThatThrownBy(() -> scenario.authorizer().authorizeOperatorCreation(
                "operator-a", operatorCreationRequest(
                        scenario, "operator-a", Map.of("left", 1, "right", 2)),
                identity("CONFIDENTIAL")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(400);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_OPERATOR_INPUT_INVALID");
                    assertThat(failure.problem().details()).isEmpty();
                });
    }

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

        DurableTestRecoveryAuthorizer.AuthorizedRecovery recovered =
                scenario.authorizer().authorize(
                        scenario.checkpoint(), identity("CONFIDENTIAL"));

        assertThat(recovered.graph().name()).isEqualTo("durable-operator-test:operator-a");
    }

    @Test
    void preservesLegacyOneNodeOperatorCheckpointReconstruction() {
        Scenario scenario = Scenario.legacyOperator();

        DurableTestRecoveryAuthorizer.AuthorizedRecovery recovered =
                scenario.authorizer().authorize(
                        scenario.checkpoint(), identity("CONFIDENTIAL"));

        assertThat(recovered.graph().name()).isEqualTo("operator-test:operator-a");
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
        FixtureBundle changedFixture = new FixtureBundle(stored.bundle().schemaVersion(),
                stored.bundle().fixtureBundleId(), stored.bundle().revision(),
                stored.bundle().targetFingerprint(), stored.bundle().classification(),
                stored.bundle().logicalClock(), stored.bundle().randomSeed(), stored.bundle().rules(),
                stored.bundle().assertions(), Map.of("revisionMarker", "changed"));
        when(fixtureDrift.fixtures().find("tenant-a", "test", "fixture-a", 1))
                .thenReturn(Optional.of(new StoredFixtureBundle("", stored.tenantId(),
                        stored.environmentId(), stored.fixtureBundleId(), stored.revision(),
                        ProtocolFingerprint.of(fixtureDrift.mapper(), changedFixture), changedFixture,
                        stored.createdAt(), stored.createdBy())));
        assertUnavailable(() -> fixtureDrift.authorizer().authorize(
                fixtureDrift.checkpoint(), identity("CONFIDENTIAL")), "FIXTURE");

        Scenario authorityDrift = Scenario.graph();
        when(authorityDrift.authenticator().descriptor()).thenReturn(
                authorityDescriptor(60));
        assertUnavailable(() -> authorityDrift.authorizer().authorize(
                authorityDrift.checkpoint(), identity("CONFIDENTIAL")), "AUTHORITY");
    }

    @Test
    void corruptFixtureEnvelopeIsAnUnavailableStoreNotAComparableDependencyRevision() {
        Scenario scenario = Scenario.graph();
        StoredFixtureBundle stored = scenario.storedFixture();
        when(scenario.fixtures().find("tenant-a", "test", "fixture-a", 1))
                .thenReturn(Optional.of(new StoredFixtureBundle("", stored.tenantId(),
                        stored.environmentId(), stored.fixtureBundleId(), stored.revision(),
                        "sha256:" + "f".repeat(64), stored.bundle(), stored.createdAt(),
                        stored.createdBy())));

        assertThatThrownBy(() -> scenario.authorizer().authorize(
                scenario.checkpoint(), identity("CONFIDENTIAL")))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    assertThat(failure.problem().status()).isEqualTo(503);
                    assertThat(failure.problem().code())
                            .isEqualTo("RG.TEST.DURABLE_DEPENDENCY_STORE_UNAVAILABLE");
                    assertThat(failure.problem().details()).containsOnly(
                            org.assertj.core.data.MapEntry.entry("dependencyKind", "FIXTURE"));
                });
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

    private static DurableTestExecutionCreateRequest creationRequest(Scenario scenario) {
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                scenario.checkpoint().dependencies();
        return new DurableTestExecutionCreateRequest(
                "", "create-1", new TestExecutionApiRequest.Target(
                dependencies.target().kind(), dependencies.target().id(),
                dependencies.target().fingerprint()), "GRAPH_CONTRACT_TEST", Map.of(),
                new TestExecutionApiRequest.FixtureBundleRef(
                        dependencies.fixture().fixtureBundleId(),
                        dependencies.fixture().revision(),
                        dependencies.fixture().fingerprint()));
    }

    private static DurableOperatorTestExecutionCreateRequest operatorCreationRequest(
            Scenario scenario, String operatorRef, Object input) {
        DurableTestExecutionCheckpoint.ControlDependencies dependencies =
                scenario.checkpoint().dependencies();
        return new DurableOperatorTestExecutionCreateRequest(
                "", "create-operator-1", new TestExecutionApiRequest.Target(
                "OPERATOR", operatorRef, dependencies.target().fingerprint()),
                "OPERATOR_UNIT_TEST", input,
                new TestExecutionApiRequest.FixtureBundleRef(
                        dependencies.fixture().fixtureBundleId(),
                        dependencies.fixture().revision(),
                        dependencies.fixture().fingerprint()));
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
            return operator(true);
        }

        private static Scenario legacyOperator() {
            return operator(false);
        }

        private static Scenario operator(boolean startGated) {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            DefaultOperatorRegistry operators = new DefaultOperatorRegistry();
            operators.register("operator-a", new ReadOnlyOperator());
            ResourceRegistry resources = mock(ResourceRegistry.class);
            when(resources.all()).thenReturn(List.of());
            GatewayGraphService graphService = mock(GatewayGraphService.class);
            OperatorExecutionTargetSnapshot target = OperatorExecutionTargetSnapshot.capture(
                    mapper, "operator-a", operators, resources);
            Graph graph = startGated
                    ? OperatorMicroGraphRunner.durableMicroGraph(
                    target.operatorRef(), target.synchronousOperator())
                    : OperatorMicroGraphRunner.microGraph(
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
            DurableTestExecutionCheckpoint.EngineState engineState =
                    mock(DurableTestExecutionCheckpoint.EngineState.class);
            when(engineState.nodeId()).thenReturn(executionGraph.sourceNodes().contains(
                    OperatorMicroGraphRunner.DURABLE_START_NODE_ID)
                    ? OperatorMicroGraphRunner.DURABLE_START_NODE_ID : "subject");
            when(checkpoint.engineState()).thenReturn(engineState);
            DurableTestRecoveryAuthorizer authorizer = new DurableTestRecoveryAuthorizer(
                    graphService, operators, resources, fixtures, replay, authority, mapper);
            when(checkpoint.runId()).thenReturn("run-a");
            when(checkpoint.checkpointFingerprint()).thenReturn(
                    ProtocolFingerprint.ofText("checkpoint-a"));
            return new Scenario(authorizer, checkpoint, executionGraph, mapper, graphService,
                    fixtures, stored, authenticator);
        }
    }

    private static final class ReadOnlyOperator implements Operator<String, String> {
        @Override
        public String execute(String input, OperatorContext context) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }
}
