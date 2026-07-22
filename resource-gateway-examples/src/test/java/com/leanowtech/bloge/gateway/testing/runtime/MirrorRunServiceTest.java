package com.leanowtech.bloge.gateway.testing.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.model.ResilienceConfig;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.OpaqueSchema;
import com.leanowtech.bloge.core.schema.SchemaValidationLevel;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosure;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityClosureIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityContract;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshotIntegrity;
import com.leanowtech.bloge.gateway.integration.mirror.EffectContract;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.planning.CompiledMirrorPlan;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompilationRequest;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanCompiler;
import com.leanowtech.bloge.gateway.testing.planning.MirrorPlanRejectedException;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MirrorRunServiceTest {
    private static final String TARGET = fingerprint('a');
    private static final Instant COMPILED_AT = Instant.parse("2026-07-22T08:00:00Z");
    private static final String PURPOSE = "MIRROR_REHEARSAL";
    private static final CapabilitySnapshot.Scope SCOPE = new CapabilitySnapshot.Scope(
            "tenant-a", "org-a", "support", "test", "sg");

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
    private final AtomicInteger externalCalls = new AtomicInteger();
    private final AtomicInteger internalCalls = new AtomicInteger();
    private final AtomicReference<Instant> observedDeadline = new AtomicReference<>();
    private Graph graph;
    private CapabilityClosure closure;
    private MirrorPlanCompiler compiler;
    private MirrorRunService runtime;

    @BeforeEach
    void setUp() {
        registry.register("customer.lookup", new ExternalReadOperator(externalCalls));
        registry.register("customer.format", new InternalOperator(internalCalls, observedDeadline));
        graph = graph();
        closure = closure();
        compiler = new MirrorPlanCompiler(registry, mapper);
        runtime = new MirrorRunService(registry, mapper, null,
                Clock.fixed(COMPILED_AT.plusSeconds(1), ZoneOffset.UTC));
    }

    @Test
    void executesTheExactCompiledGenerationAndNeverCallsTheExternalLeaf() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning(Map.of("customerId", "C-1")))));

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isTrue();
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(1);
        assertThat(observedDeadline).hasValue(COMPILED_AT.plus(Duration.ofMinutes(5)));
        assertThat(result.execution().plan().planFingerprint())
                .isEqualTo(compiled.plan().executionControlFingerprint());
        assertThat(result.execution().evidence().metadata())
                .containsEntry("mirrorPlanFingerprint", compiled.plan().planFingerprint())
                .containsEntry("capabilityClosureFingerprint",
                        compiled.plan().capabilityClosureFingerprint());
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .singleElement().satisfies(trace -> {
                    assertThat(trace.status()).isEqualTo("MOCKED");
                    assertThat(trace.output()).isEqualTo(Map.of("customerId", "C-1"));
                });
        assertThat(runtime.engineConfiguration().interceptorTypes()).isEmpty();
        assertThat(runtime.engineConfiguration().durableStores()).isFalse();
        assertThat(runtime.engineConfiguration().productionContextCarriers()).isFalse();
    }

    @Test
    void turnsAnUnmatchedExternalLeafIntoARecordedFailureWithoutRealFallback() {
        CompiledMirrorPlan compiled = compile(fixture());

        MirrorRunResult result = runtime.execute(request(compiled, SCOPE, PURPOSE));

        assertThat(result.passed()).isFalse();
        assertThat(externalCalls).hasValue(0);
        assertThat(result.execution().evidence().status())
                .isEqualTo(com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence.Status
                        .FIXTURE_UNMATCHED);
        assertThat(result.execution().evidence().nodeTrace())
                .filteredOn(trace -> trace.invocationSiteId().equals(
                        "/root/loadCustomer#PRIMARY"))
                .singleElement().satisfies(trace ->
                        assertThat(trace.errorCode()).isEqualTo("FIXTURE_UNMATCHED"));
    }

    @Test
    void rejectsScopePurposeAndTimeBeforeSchedulingAnyNode() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("value"))));

        assertRunRejected(() -> runtime.execute(request(compiled,
                        new CapabilitySnapshot.Scope("tenant-b", "org-a", "support", "test", "sg"),
                        PURPOSE)),
                "RG.MIRROR.RUN_SCOPE_MISMATCH");
        assertRunRejected(() -> runtime.execute(request(compiled, SCOPE, "CHANGE_SYNC")),
                "RG.MIRROR.RUN_PURPOSE_MISMATCH");

        MirrorRunService expired = new MirrorRunService(registry, mapper, null,
                Clock.fixed(compiled.plan().expiresAt(), ZoneOffset.UTC));
        assertRunRejected(() -> expired.execute(request(compiled, SCOPE, PURPOSE)),
                "RG.MIRROR.RUN_EXPIRED");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(0);
    }

    @Test
    void rejectsACompiledCompanionWhoseFixturePayloadWasReplaced() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("original"))));
        FixtureBundle replacement = fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("replacement")));
        CompiledMirrorPlan drifted = new CompiledMirrorPlan(compiled.plan(), compiled.graph(),
                replacement, compiled.executionControl());

        assertRunRejected(() -> runtime.execute(request(drifted, SCOPE, PURPOSE)),
                "RG.MIRROR.FIXTURE_GENERATION_DRIFT");
        assertThat(externalCalls).hasValue(0);
        assertThat(internalCalls).hasValue(0);
    }

    @Test
    void compilerRejectsFixtureControlsThatReplaceInternalBusinessNodes() {
        FixtureBundle fixture = fixture(
                rule("external", "loadCustomer", FixtureRule.Behavior.returning("customer")),
                rule("internal", "formatCustomer", FixtureRule.Behavior.returning("formatted")));

        assertThatThrownBy(() -> compile(fixture))
                .isInstanceOfSatisfying(MirrorPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "RG.MIRROR.CONTROL_PLAN_MIRROR_INTERNAL_CONTROL"));
    }

    @Test
    void sharedPrecompiledKernelRejectsPurposeDriftEvenWithoutTheMirrorAdmissionLayer() {
        CompiledMirrorPlan compiled = compile(fixture(rule("customer-response", "loadCustomer",
                FixtureRule.Behavior.returning("value"))));
        TestRunService shared = new TestRunService(registry, mapper, null);
        TestExecutionRequest drifted = new TestExecutionRequest(compiled.graph(), new GraphContext(),
                compiled.fixtureBundle(), "CHANGE_SYNC",
                compiled.executionControl().effectivePlan().targetFingerprint(),
                TestExecutionRequest.FixtureSource.STORED, Map.of(), true,
                compiled.executionControl().replayPayloads(), ResolvedTestSecrets.empty());

        assertThatThrownBy(() -> shared.executeCompiled(drifted, compiled.executionControl()))
                .isInstanceOfSatisfying(
                        com.leanowtech.bloge.gateway.testing.planning.ControlPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "CONTROL_PLAN_COMPILED_BINDING_MISMATCH"));
    }

    private CompiledMirrorPlan compile(FixtureBundle fixture) {
        return compiler.compile(new MirrorPlanCompilationRequest(
                "plan-customer-view", graph, TARGET, closure, fixture,
                ResolvedReplayPayloads.empty(), policy(), null,
                COMPILED_AT, COMPILED_AT.plus(Duration.ofHours(1))));
    }

    private static MirrorRunRequest request(
            CompiledMirrorPlan compiled,
            CapabilitySnapshot.Scope scope,
            String purpose) {
        return new MirrorRunRequest("request-1", compiled,
                new GraphContext(Map.of("customerId", "C-1")), scope, purpose);
    }

    private CapabilityClosure closure() {
        EffectContract effect = EffectContract.readOnly(List.of("customer:*"));
        CapabilitySnapshot external = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "operator:customer.lookup", 1, "",
                        CapabilitySnapshot.Kind.EXTERNAL, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.OPERATOR,
                                "customer.lookup", fingerprint('e')),
                        contract(effect), runtime("OPERATOR", "customer.lookup", 'f'),
                        List.of(), ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        CapabilitySnapshot root = CapabilitySnapshotIntegrity.seal(mapper,
                new CapabilitySnapshot("", "graph:customerView", 1, "",
                        CapabilitySnapshot.Kind.COMPOSED, SCOPE,
                        new CapabilitySnapshot.Source(CapabilitySnapshot.SourceKind.GRAPH,
                                "customerView", TARGET), contract(effect),
                        runtime("BLOGE_GRAPH", "customerView", '8'),
                        List.of(new CapabilitySnapshot.Dependency("loadCustomer",
                                CapabilityClosureIntegrity.reference(external), true, List.of())),
                        ownership(), CapabilitySnapshot.Lifecycle.ACTIVE,
                        provenance(), COMPILED_AT));
        return CapabilityClosureIntegrity.seal(mapper, new CapabilityClosure("",
                CapabilityClosureIntegrity.reference(root), List.of(root, external), ""));
    }

    private static CapabilityContract contract(EffectContract effect) {
        return new CapabilityContract("", SchemaEnvelope.opaque(), SchemaEnvelope.opaque(),
                List.of(), effect, CapabilityContract.Determinism.CONTROLLED_NONDETERMINISTIC,
                new CapabilityContract.IdempotencyContract(
                        CapabilityContract.IdempotencyMode.IDEMPOTENT, "", true),
                null, CapabilityContract.CompatibilityPolicy.conservative(),
                new CapabilityContract.SecurityContract(
                        CapabilityContract.DataClassification.CONFIDENTIAL, false,
                        List.of("sg"), false), CapabilityContract.SloContract.unspecified());
    }

    private static CapabilitySnapshot.RuntimeBinding runtime(
            String kind,
            String ref,
            char value) {
        return new CapabilitySnapshot.RuntimeBinding(kind, ref, fingerprint(value), true, List.of());
    }

    private static CapabilitySnapshot.Ownership ownership() {
        return new CapabilitySnapshot.Ownership("owner-a", "support", "pager");
    }

    private static ArtifactProvenance provenance() {
        return new ArtifactProvenance("", ArtifactProvenance.SourceType.OWNER, List.of(),
                SCOPE.tenantId(), PURPOSE, null, null, null, null, List.of(),
                "owner-a", COMPILED_AT.minus(Duration.ofDays(1)),
                COMPILED_AT.plus(Duration.ofDays(1)), "");
    }

    private static MirrorPlan.ExecutionPolicy policy() {
        return new MirrorPlan.ExecutionPolicy(PURPOSE, false, false, false, false, true,
                MirrorPlan.UnmatchedResolution.ABSTAINED, 1000, Duration.ofMinutes(5),
                CapabilityContract.DataClassification.CONFIDENTIAL, List.of("sg"),
                List.of(CapabilitySnapshot.Lifecycle.ACTIVE));
    }

    private static FixtureBundle fixture(FixtureRule... rules) {
        return new FixtureBundle("", "customer-fixture", 1, TARGET, "CONFIDENTIAL",
                COMPILED_AT, 42L, List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, String nodeId, FixtureRule.Behavior behavior) {
        return new FixtureRule("", id, FixtureRule.Selector.node(nodeId), behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static Graph graph() {
        Map<String, NodeSpec> nodes = new LinkedHashMap<>();
        nodes.put("loadCustomer", node("loadCustomer", "customer.lookup"));
        nodes.put("formatCustomer", node("formatCustomer", "customer.format"));
        return new Graph("customerView", nodes, List.of(), Set.copyOf(nodes.keySet()),
                Set.copyOf(nodes.keySet()), SchemaValidationLevel.OFF);
    }

    private static NodeSpec node(String id, String operatorRef) {
        return new NodeSpec(id, operatorRef, null, ResilienceConfig.DEFAULT,
                Map.of(), OpaqueSchema.INSTANCE, OpaqueSchema.INSTANCE);
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static void assertRunRejected(Runnable action, String code) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(MirrorRunRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(code));
    }

    private static final class ExternalReadOperator implements Operator<Object, Object> {
        private final AtomicInteger calls;

        private ExternalReadOperator(AtomicInteger calls) {
            this.calls = calls;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            calls.incrementAndGet();
            return Map.of("real", true);
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class InternalOperator implements Operator<Object, Object> {
        private final AtomicInteger calls;
        private final AtomicReference<Instant> deadline;

        private InternalOperator(AtomicInteger calls, AtomicReference<Instant> deadline) {
            this.calls = calls;
            this.deadline = deadline;
        }

        @Override
        public Object execute(Object input, OperatorContext context) {
            calls.incrementAndGet();
            deadline.set(context.executionBudget().deadline().orElse(null));
            return Map.of("formatted", true);
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }
}
