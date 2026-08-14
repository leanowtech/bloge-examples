package com.leanowtech.bloge.gateway.businessmirror.implementation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityImplementationBinding;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.planning.CompiledExecutionControl;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.ExecutionControlCompiler;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityImplementationConformancePlanCompilerTest {
    private static final Instant AT = Instant.parse("2026-08-14T10:00:00Z");
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant", "customer-service", "refund", "test", "sg");
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger targetOriginalCalls = new AtomicInteger();
    private final AtomicInteger otherOriginalCalls = new AtomicInteger();
    private DefaultOperatorRegistry registry;
    private Graph graph;
    private FixtureBundle fixture;
    private CompiledExecutionControl baselineControl;
    private String targetSite;
    private String otherSite;
    private MirrorArtifactRef temporaryCapability;
    private MirrorArtifactRef otherCapability;
    private CapabilityImplementationBinding binding;

    @BeforeEach
    void setUp() {
        Graph raw = graph();
        graph = withoutEmbeddedOperators(raw);
        registry = new DefaultOperatorRegistry();
        graph.nodes().values().forEach(node -> registry.register(node.operatorRef(),
                node.id().equals("lookupRefund")
                        ? new TargetOriginal(targetOriginalCalls)
                        : new OtherOriginal(otherOriginalCalls)));
        String graphFingerprint = fingerprint('1');
        var inventory = new com.leanowtech.bloge.gateway.testing.planning
                .InvocationInventoryBuilder(registry).build(graph, graphFingerprint);
        targetSite = inventory.entries().stream()
                .filter(value -> value.node().id().equals("lookupRefund"))
                .findFirst().orElseThrow().site().invocationSiteId();
        otherSite = inventory.entries().stream()
                .filter(value -> value.node().id().equals("loadPolicy"))
                .findFirst().orElseThrow().site().invocationSiteId();
        fixture = new FixtureBundle("", "refund-fixture", 1, graphFingerprint, "INTERNAL",
                AT, 42L, List.of(
                rule("target-rule", "lookupRefund", Map.of("approved", true)),
                rule("other-rule", "loadPolicy", Map.of("policy", "standard"))),
                List.of(), Map.of());
        baselineControl = new ExecutionControlCompiler(registry, mapper).compileMirror(
                graph, fixture, MirrorPlanIntegrationServicePurpose.VALUE, graphFingerprint,
                ResolvedReplayPayloads.empty(), Set.of(targetSite, otherSite));
        temporaryCapability = ref("CAPABILITY", "proposal-target", '2');
        otherCapability = ref("CAPABILITY", "policy-capability", '3');
        binding = binding();
    }

    @Test
    void reversesOnlyTheTargetAndExecutesItsAttestedRuntimePort() {
        MirrorPlan plan = plan(List.of(
                external(temporaryCapability, targetSite, "target-rule"),
                external(otherCapability, otherSite, "other-rule")));
        CompiledMirrorPlan baseline = baseline(plan);
        CapabilityImplementationConformancePlanCompiler.Result compiled =
                new CapabilityImplementationConformancePlanCompiler(mapper).compile(
                        baseline, temporaryCapability, binding, "conformance-1", "suite:case");
        AtomicInteger runtimeCalls = new AtomicInteger();
        CapabilityImplementationRuntimePort runtime = runtime(runtimeCalls);
        ConformanceOperatorRegistry conformanceRegistry = new ConformanceOperatorRegistry(
                registry, compiled.targetOperatorRefs(), compiled.runtimeCoordinates(), binding,
                runtime, "conformance-1", "suite:case",
                Clock.fixed(AT.plusSeconds(2), ZoneOffset.UTC));
        CompiledExecutionControl executionControl = compiled.bindTargetOperator(
                conformanceRegistry.targetOperator());

        var result = new TestRunService(conformanceRegistry, mapper, null).executeCompiled(
                new TestExecutionRequest(graph, new GraphContext(Map.of()), compiled.fixture(),
                        CapabilityImplementationConformancePlanCompiler.AUTHORIZED_PURPOSE,
                        compiled.fixture().targetFingerprint(),
                        TestExecutionRequest.FixtureSource.STORED, Map.of(), false,
                        executionControl.replayPayloads(), ResolvedTestSecrets.empty()),
                executionControl);

        assertThat(result.evidence().status().name()).isEqualTo("PASSED");
        assertThat(runtimeCalls).hasValue(1);
        assertThat(conformanceRegistry.invokedSiteIds()).containsExactly(targetSite);
        assertThat(targetOriginalCalls).hasValue(0);
        assertThat(otherOriginalCalls).hasValue(0);
        assertThat(compiled.compiled().controls().get(targetSite).rules())
                .allMatch(rule -> rule.behavior().kind() == FixtureRule.BehaviorKind.REAL);
        assertThat(compiled.compiled().controls().get(otherSite).rules())
                .allMatch(rule -> rule.behavior().kind() == FixtureRule.BehaviorKind.RETURN);
    }

    @Test
    void rejectsRulesSharedBetweenTargetAndNonTargetDependencies() {
        MirrorPlan plan = plan(List.of(
                external(temporaryCapability, targetSite, "target-rule"),
                external(otherCapability, otherSite, "target-rule")));

        assertThatThrownBy(() -> new CapabilityImplementationConformancePlanCompiler(mapper)
                .compile(baseline(plan), temporaryCapability, binding,
                        "conformance-1", "suite:case"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONFORMANCE_TARGET_RULE_SHARED");
    }

    @Test
    void rejectsTargetRuleReuseHiddenByMirrorPlanMetadata() {
        Map<String, CompiledExecutionControl.ResolvedControl> controls =
                new LinkedHashMap<>(baselineControl.controls());
        CompiledExecutionControl.ResolvedControl other = controls.get(otherSite);
        controls.put(otherSite, new CompiledExecutionControl.ResolvedControl(other.site(),
                List.of(fixture.rules().stream()
                        .filter(rule -> rule.ruleId().equals("target-rule"))
                        .findFirst().orElseThrow()), false));
        CompiledExecutionControl drifted = new CompiledExecutionControl(
                baselineControl.effectivePlan(), controls, baselineControl.rules(),
                baselineControl.inventory(), baselineControl.replayPayloads(),
                baselineControl.corpusPayloads(), baselineControl.executionServices());
        MirrorPlan plan = plan(List.of(
                external(temporaryCapability, targetSite, "target-rule"),
                external(otherCapability, otherSite, "other-rule")));
        CompiledMirrorPlan baseline = mock(CompiledMirrorPlan.class);
        when(baseline.plan()).thenReturn(plan);
        when(baseline.graph()).thenReturn(graph);
        when(baseline.fixtureBundle()).thenReturn(fixture);
        when(baseline.executionControl()).thenReturn(drifted);

        assertThatThrownBy(() -> new CapabilityImplementationConformancePlanCompiler(mapper)
                .compile(baseline, temporaryCapability, binding,
                        "conformance-1", "suite:case"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("CONFORMANCE_TARGET_RULE_SHARED_BY_CONTROL");
    }

    private CompiledMirrorPlan baseline(MirrorPlan plan) {
        CompiledMirrorPlan baseline = mock(CompiledMirrorPlan.class);
        when(baseline.plan()).thenReturn(plan);
        when(baseline.graph()).thenReturn(graph);
        when(baseline.fixtureBundle()).thenReturn(fixture);
        when(baseline.executionControl()).thenReturn(baselineControl);
        return baseline;
    }

    private MirrorPlan plan(List<MirrorPlan.ExternalBinding> externals) {
        MirrorPlan plan = mock(MirrorPlan.class);
        when(plan.externalBindings()).thenReturn(externals);
        when(plan.planFingerprint()).thenReturn(fingerprint('4'));
        return plan;
    }

    private MirrorPlan.ExternalBinding external(
            MirrorArtifactRef capability, String site, String ruleId) {
        return new MirrorPlan.ExternalBinding(ref("CAPABILITY", "root", '5'),
                "dependency-" + ruleId, capability, site, "/root",
                CapabilitySnapshot.SourceKind.OPERATOR, "source-" + ruleId,
                List.of(MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                        MirrorPlan.MirrorSource.ABSTAINED), List.of(ruleId));
    }

    private CapabilityImplementationRuntimePort runtime(AtomicInteger calls) {
        CapabilityImplementationRuntimePort.Descriptor descriptor = descriptor();
        return new CapabilityImplementationRuntimePort() {
            @Override
            public Optional<Descriptor> describe(CapabilitySnapshot.Scope scope, String port) {
                return Optional.of(descriptor);
            }

            @Override
            public Object invoke(
                    CapabilityImplementationBinding exact, Invocation invocation) {
                calls.incrementAndGet();
                return Map.of("approved", true);
            }
        };
    }

    private CapabilityImplementationBinding binding() {
        CapabilityImplementationRuntimePort.Descriptor descriptor = descriptor();
        return new CapabilityImplementationBinding("", "binding-1", 1, "", SCOPE,
                ref("CAPABILITY_PROPOSAL_DRAFT", "proposal", '6'),
                ref("PROPOSAL_SIMULATION_EVIDENCE", "simulation", '7'),
                ref("CAPABILITY", "target", '8'), fingerprint('9'),
                descriptor.runtimePortRef(), descriptor.runtimePortFingerprint(),
                descriptor.implementationVersion(), descriptor.implementationFingerprint(),
                descriptor.runtimeOwner(), descriptor.allowedRegions(), true, true,
                descriptor.attestedAt(), descriptor.expiresAt(), AT).seal(mapper);
    }

    private CapabilityImplementationRuntimePort.Descriptor descriptor() {
        return new CapabilityImplementationRuntimePort.Descriptor("runtime:refund:v1",
                fingerprint('a'), "1.0.0", fingerprint('b'), fingerprint('9'), "owner",
                List.of("sg"), true, true, AT.minusSeconds(1), AT.plusSeconds(600));
    }

    private static FixtureRule rule(String id, String nodeId, Object value) {
        return new FixtureRule("", id, FixtureRule.Selector.node(nodeId),
                FixtureRule.Behavior.returning(value), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
    }

    private static Graph graph() {
        return new GraphBuilder("refundGraph")
                .node("lookupRefund", new TargetOriginal(new AtomicInteger()))
                .node("loadPolicy", new OtherOriginal(new AtomicInteger()))
                .build();
    }

    private static Graph withoutEmbeddedOperators(Graph value) {
        return new Graph(value.name(), value.nodes(), value.edges(), value.sourceNodes(),
                value.terminalNodes(), value.schemaValidationLevel(), Map.of(),
                value.declaredInputSchema(), value.declaredOutputSchema(), value.sagaConfig(),
                value.definitionSource(), value.streamingOutputNodeId(), value.streamingInputs());
    }

    private static MirrorArtifactRef ref(String kind, String id, char value) {
        return new MirrorArtifactRef(kind, id, 1, fingerprint(value));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record TargetOriginal(AtomicInteger calls) implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            calls.incrementAndGet();
            return Map.of("should", "not-run");
        }
    }

    private record OtherOriginal(AtomicInteger calls) implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, com.leanowtech.bloge.core.operator.OperatorContext ctx) {
            calls.incrementAndGet();
            return Map.of("should", "not-run");
        }
    }

    private static final class MirrorPlanIntegrationServicePurpose {
        private static final String VALUE = "MIRROR_REHEARSAL";
    }
}
