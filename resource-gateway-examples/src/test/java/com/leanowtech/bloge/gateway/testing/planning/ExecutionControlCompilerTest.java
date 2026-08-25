package com.leanowtech.bloge.gateway.testing.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.schema.TypedSchema;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
import com.leanowtech.bloge.gateway.integration.mirror.ArtifactProvenance;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilityCorpusClusterValidation;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorPlan;
import com.leanowtech.bloge.gateway.integration.mirror.MirrorArtifactRef;
import com.leanowtech.bloge.gateway.testing.domain.EffectiveExecutionPlan;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionServiceStateSnapshot;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedCorpusPayloads;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionControlCompilerTest {

    private static final String TARGET = "sha256:" + "a".repeat(64);
    private static final String MUTANT_TARGET = "sha256:" + "b".repeat(64);
    private static final String REPLAY_REF = "bloge-replay:approved-order@7#sha256:"
            + "c".repeat(64);
    private final DefaultOperatorRegistry registry = new DefaultOperatorRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutionControlCompiler compiler = new ExecutionControlCompiler(
            registry, objectMapper);

    @Test
    void exactNodeSelectorProducesFrozenTestDoubleResolution() {
        Graph graph = graph(new ReadOnlyOperator());
        FixtureRule rule = rule("return-result", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("approved", true)));

        CompiledExecutionControl result = compiler.compile(graph, bundle(rule),
                "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(result.effectivePlan().targetFingerprint()).isEqualTo(TARGET);
        assertThat(result.effectivePlan().planFingerprint()).startsWith("sha256:");
        assertThat(result.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.invocationSiteId()).isEqualTo("/root/subject#PRIMARY");
            assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.TEST_DOUBLE);
            assertThat(site.ruleRefs()).containsExactly("return-result");
        });
    }

    @Test
    void mutationCompilationSeparatesBaselineFixtureBindingFromMutantIdentity() {
        FixtureBundle fixture = bundle();

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), fixture,
                "MUTATION_SUITE_EXECUTION", MUTANT_TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.diagnostics()).anyMatch(value ->
                                value.contains("does not match")));

        CompiledExecutionControl compiled = compiler.compileMutation(
                graph(new ReadOnlyOperator()), fixture, "MUTATION_SUITE_EXECUTION",
                MUTANT_TARGET, TARGET, ResolvedReplayPayloads.empty());

        assertThat(compiled.effectivePlan().targetFingerprint()).isEqualTo(MUTANT_TARGET);
        assertThat(compiled.effectivePlan().fixtureBundleFingerprint()).isNotBlank();
        assertThat(compiled.effectivePlan().authorizedPurpose())
                .isEqualTo("MUTATION_SUITE_EXECUTION");
        assertThat(compiled.effectivePlan().planFingerprint()).startsWith("sha256:");
    }

    @Test
    void separateFixtureBindingIsUnavailableToOtherPurposesOrSameTarget() {
        assertThatThrownBy(() -> compiler.compileMutation(graph(new ReadOnlyOperator()), bundle(),
                "GRAPH_CONTRACT_TEST", MUTANT_TARGET, TARGET, ResolvedReplayPayloads.empty()))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_MUTATION_BINDING_INVALID"));
        assertThatThrownBy(() -> compiler.compileMutation(graph(new ReadOnlyOperator()), bundle(),
                "MUTATION_SUITE_EXECUTION", TARGET, TARGET, ResolvedReplayPayloads.empty()))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code())
                                .isEqualTo("CONTROL_PLAN_MUTATION_BINDING_INVALID"));
    }

    @Test
    void selectorWithNoStaticInvocationSiteIsRejected() {
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(rule("missing", FixtureRule.Selector.node("does-not-exist"),
                        FixtureRule.Behavior.returning("x"))), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_ZERO_MATCH"));
    }

    @Test
    void samePrecedenceRulesForOneSiteAreRejectedBeforeExecution() {
        FixtureRule first = rule("first", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("a"));
        FixtureRule second = rule("second", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("b"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(first, second), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_AMBIGUOUS"));
    }

    @Test
    void dynamicCoordinatesAreOrderedAheadOfAnExplicitGeneralFallback() {
        FixtureRule.Selector selector = new FixtureRule.Selector("/root", "subject", "", "", "",
                List.of(), List.of(), InvocationSite.InvocationKind.PRIMARY,
                List.of(1), List.of(2), "", FixtureRule.Match.none());
        FixtureRule dynamic = rule("dynamic", selector, FixtureRule.Behavior.returning("specific"));
        FixtureRule fallback = rule("fallback", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("general"));

        CompiledExecutionControl compiled = compiler.compile(graph(new ReadOnlyOperator()),
                bundle(fallback, dynamic), "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(compiled.controls().get("/root/subject#PRIMARY").rules())
                .extracting(FixtureRule::ruleId)
                .containsExactly("dynamic", "fallback");
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement().satisfies(site ->
                assertThat(site.ruleRefs()).containsExactly("dynamic", "fallback"));
    }

    @Test
    void descriptorModesAreCompiledPerDynamicRuleWithoutFirstRuleLeakage() {
        registry.register("httpResource", new ExternalOperator());
        Graph graph = registryGraph("httpResource");
        FixtureRule protocol = rule("protocol", FixtureRule.Selector.resource("customer.protocol"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.NODE));
        FixtureRule transport = rule("transport", FixtureRule.Selector.resource("customer.transport"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.TRANSPORT));

        CompiledExecutionControl compiled = compiler.compile(
                graph, bundle(protocol, transport), "GRAPH_CONTRACT_TEST", TARGET);
        CompiledExecutionControl.ResolvedControl control =
                compiled.controls().get("/root/subject#RESOURCE");

        assertThat(control.executionMode(protocol)).contains(ExecutionMode.DESCRIPTOR_PROTOCOL);
        assertThat(control.executionMode(transport)).contains(ExecutionMode.DESCRIPTOR_TRANSPORT);
        assertThat(control.executionModesByRuleId()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "protocol", ExecutionMode.DESCRIPTOR_PROTOCOL,
                "transport", ExecutionMode.DESCRIPTOR_TRANSPORT));
    }

    @Test
    void explicitSchemaStandinHintKeepsFingerprintStableAcrossRepeatedCompilation() {
        FixtureRule output = rule("standin-output", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("approved", true)));
        Graph graph = graph(new ReadOnlyOperator());

        CompiledExecutionControl first = compiler.compileWithExecutionModeHints(
                graph, bundle(output), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", output.ruleId()));
        CompiledExecutionControl second = compiler.compileWithExecutionModeHints(
                graph, bundle(output), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", output.ruleId()));
        assertThat(first.controls().get("/root/subject#PRIMARY").executionMode(output))
                .contains(ExecutionMode.SCHEMA_STANDIN);
        assertThat(second.effectivePlan().planFingerprint())
                .isEqualTo(first.effectivePlan().planFingerprint());
    }

    @Test
    void bindingAndOutputControlsCompileOnlyTheirDocumentedExecutionModes() {
        FixtureRule real = rule("real", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.real());

        CompiledExecutionControl realPlan = compiler.compile(
                graph(new ReadOnlyOperator()), bundle(real), "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(realPlan.controls().get("/root/subject#PRIMARY").executionMode(real))
                .contains(ExecutionMode.BINDING_REAL);

        List<FixtureRule> outputControls = List.of(
                rule("return", FixtureRule.Selector.node("subject"),
                        FixtureRule.Behavior.returning("fixed")),
                rule("throw", FixtureRule.Selector.node("subject"),
                        FixtureRule.Behavior.throwing("FAILED", "TEST", "failed")),
                rule("delay", FixtureRule.Selector.node("subject"),
                        FixtureRule.Behavior.delayed(Duration.ofSeconds(1), "fixed")),
                rule("timeout", FixtureRule.Selector.node("subject"),
                        FixtureRule.Behavior.timeout(Duration.ofSeconds(1))),
                rule("deny", FixtureRule.Selector.node("subject"),
                        FixtureRule.Behavior.deny("DENIED", "must not run")));
        for (FixtureRule outputControl : outputControls) {
            CompiledExecutionControl outputPlan = compiler.compile(
                    graph(new ReadOnlyOperator()), logicalBundle(outputControl),
                    "GRAPH_CONTRACT_TEST", TARGET);
            assertThat(outputPlan.controls().get("/root/subject#PRIMARY")
                    .executionMode(outputControl)).isEmpty();
        }
        assertThat(ExecutionMode.resolve("readOnly", outputControls.getFirst().behavior()))
                .isEmpty();
        assertThat(ExecutionMode.resolve("readOnly", FixtureRule.Behavior.returning(null)))
                .isEmpty();

        FixtureRule replay = rule("replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));
        CompiledExecutionControl replayPlan = compiler.compile(
                graph(new ReadOnlyOperator()), bundle(replay), "GRAPH_CONTRACT_TEST", TARGET,
                replays(true, "source-a"));
        assertThat(replayPlan.controls().get("/root/subject#PRIMARY").executionMode(replay))
                .isEmpty();

        registry.register("httpResource", new ExternalOperator());
        FixtureRule transportOutput = rule("transport-output",
                FixtureRule.Selector.resource("customer.get"),
                new FixtureRule.Behavior(FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.TRANSPORT, Map.of("fixed", true), "", null,
                        Map.of(), "", "", "", null, List.of(), ""));
        CompiledExecutionControl transportOutputPlan = compiler.compile(
                registryGraph("httpResource"), bundle(transportOutput),
                "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(transportOutputPlan.controls().get("/root/subject#RESOURCE")
                .executionMode(transportOutput)).isEmpty();
    }

    @Test
    void schemaStandinCannotReplaceDescriptorTransportWithFinalOutput() {
        registry.register("httpResource", new ExternalOperator());
        FixtureRule confused = rule("transport-output",
                FixtureRule.Selector.resource("customer.get"),
                new FixtureRule.Behavior(FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.TRANSPORT, Map.of("fixed", true), "", null,
                        Map.of(), "", "", "", null, List.of(), ""));

        assertThatThrownBy(() -> compiler.compileWithExecutionModeHints(
                registryGraph("httpResource"), bundle(confused),
                "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#RESOURCE", confused.ruleId())))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code())
                                .isEqualTo("CONTROL_PLAN_DESCRIPTOR_TRANSPORT_CONFUSION"));
    }

    @Test
    void explicitSchemaStandinHintIsFrozenInTheInternalPlan() {
        FixtureRule fixed = rule("fixed", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("fixture"));

        CompiledExecutionControl first = compiler.compileWithExecutionModeHints(
                graph(new ReadOnlyOperator()), bundle(fixed), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", fixed.ruleId()));
        CompiledExecutionControl second = compiler.compileWithExecutionModeHints(
                graph(new ReadOnlyOperator()), bundle(fixed), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", fixed.ruleId()));

        assertThat(first.controls().get("/root/subject#PRIMARY")
                .executionModesByRuleId()).containsEntry("fixed", ExecutionMode.SCHEMA_STANDIN);
        assertThat(first.effectivePlan().resolvedSites()).singleElement()
                .satisfies(site -> assertThat(site.fidelity()).isEqualTo("SCHEMA_STANDIN"));
        assertThat(first.effectivePlan().planFingerprint())
                .isEqualTo(second.effectivePlan().planFingerprint());
    }

    @Test
    void explicitSchemaStandinHintRequiresNodeReturnShapeWithoutLeakingPayload() {
        FixtureRule missing = rule("missing", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.throwing("MISSING_OUTPUT", "TEST", "missing output"));

        CompiledExecutionControl ordinary = compiler.compile(
                graph(new ReadOnlyOperator()), bundle(missing), "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(ordinary.controls().get("/root/subject#PRIMARY").executionMode(missing))
                .isEmpty();

        assertThatThrownBy(() -> compiler.compileWithExecutionModeHints(
                graph(new ReadOnlyOperator()), bundle(missing), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", missing.ruleId())))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("CONTROL_PLAN_SCHEMA_STANDIN_OUTPUT_REQUIRED");
                    assertThat(ex.diagnostics()).noneMatch(value -> value.contains("fixture"));
                });
    }

    @Test
    void explicitSchemaStandinHintAcceptsNullForOpaqueAndNullableOutputs() {
        FixtureRule nullOutput = rule("null-output", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(null));

        CompiledExecutionControl opaque = compiler.compileWithExecutionModeHints(
                graph(new ReadOnlyOperator()), bundle(nullOutput), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", nullOutput.ruleId()));
        CompiledExecutionControl nullable = compiler.compileWithExecutionModeHints(
                withOutputSchema(graph(new ReadOnlyOperator()), new TypedSchema(String.class)),
                bundle(nullOutput), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", nullOutput.ruleId()));

        assertThat(opaque.controls().get("/root/subject#PRIMARY").executionMode(nullOutput))
                .contains(ExecutionMode.SCHEMA_STANDIN);
        assertThat(nullable.controls().get("/root/subject#PRIMARY").executionMode(nullOutput))
                .contains(ExecutionMode.SCHEMA_STANDIN);
    }

    @Test
    void explicitSchemaStandinOutputMustMatchTheFrozenNodeSchema() {
        FixtureRule invalid = rule("invalid", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning(Map.of("unexpected", true)));

        assertThatThrownBy(() -> compiler.compileWithExecutionModeHints(
                withOutputSchema(graph(new ReadOnlyOperator()), new TypedSchema(String.class)),
                bundle(invalid), "GRAPH_CONTRACT_TEST", TARGET,
                ExecutionModeHints.schemaStandin("/root/subject#PRIMARY", invalid.ruleId())))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex -> {
                    assertThat(ex.code())
                            .isEqualTo("CONTROL_PLAN_SCHEMA_STANDIN_OUTPUT_SCHEMA_INVALID");
                    assertThat(ex.diagnostics()).noneMatch(value -> value.contains("unexpected"));
                });
    }

    @Test
    void descriptorModeParticipatesInPlanFingerprintWithoutChangingPublicPlanShape()
            throws Exception {
        registry.register("httpResource", new ExternalOperator());
        Graph graph = registryGraph("httpResource");
        FixtureRule protocol = rule("response", FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.NODE));
        FixtureRule transport = rule("response", FixtureRule.Selector.resource("customer.get"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.TRANSPORT));

        CompiledExecutionControl protocolPlan = compiler.compile(
                graph, bundle(protocol), "GRAPH_CONTRACT_TEST", TARGET);
        CompiledExecutionControl transportPlan = compiler.compile(
                graph, bundle(transport), "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(protocolPlan.effectivePlan().planFingerprint())
                .isNotEqualTo(transportPlan.effectivePlan().planFingerprint());
        assertThat(objectMapper.writeValueAsString(protocolPlan.effectivePlan()))
                .doesNotContain("\"executionMode\"", "\"executionModesByRuleId\"");
    }

    @Test
    void equivalentMixedDescriptorCompilationsKeepStableFingerprintAcrossSiteOrder() {
        registry.register("httpResource", new ExternalOperator());
        FixtureRule protocol = rule("protocol", FixtureRule.Selector.resource("customer.protocol"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.NODE));
        FixtureRule transport = rule("transport", FixtureRule.Selector.resource("customer.transport"),
                FixtureRule.Behavior.protocolResponse("{\"ok\":true}", 200, Map.of(),
                        FixtureRule.DoubleBoundary.TRANSPORT));
        String expected = null;

        for (int run = 0; run < 10; run++) {
            Graph equivalentSiteOrder = registryGraphWithAuxiliarySite(
                    "httpResource", run % 2 == 0);
            String actual = compiler.compile(equivalentSiteOrder, bundle(protocol, transport),
                    "GRAPH_CONTRACT_TEST", TARGET).effectivePlan().planFingerprint();
            if (expected == null) {
                expected = actual;
            }
            assertThat(actual).isEqualTo(expected);
        }

        String reorderedDeclaration = compiler.compile(
                registryGraphWithAuxiliarySite("httpResource", true),
                bundle(transport, protocol), "GRAPH_CONTRACT_TEST", TARGET)
                .effectivePlan().planFingerprint();
        assertThat(reorderedDeclaration)
                .as("Fixture rule list order is explicit fingerprint material")
                .isNotEqualTo(expected);
    }

    @Test
    void executionModeProtocolRemainsTheExactDocumentedClosedSet() {
        assertThat(ExecutionMode.values()).containsExactly(
                ExecutionMode.PRIMITIVE_REAL,
                ExecutionMode.SCHEMA_STANDIN,
                ExecutionMode.DESCRIPTOR_PROTOCOL,
                ExecutionMode.DESCRIPTOR_TRANSPORT,
                ExecutionMode.BINDING_TRANSPORT,
                ExecutionMode.BINDING_REAL,
                ExecutionMode.WORLD_DELEGATE);
    }

    @Test
    void disjointAttemptSelectorsCanShareOneInvocationSite() {
        FixtureRule first = rule("first-attempt", selector(List.of(1), List.of()),
                FixtureRule.Behavior.throwing("FIRST_FAILED", "TEST", "retry"));
        FixtureRule second = rule("second-attempt", selector(List.of(2), List.of()),
                FixtureRule.Behavior.returning("recovered"));

        CompiledExecutionControl compiled = compiler.compile(graph(new ReadOnlyOperator()),
                bundle(first, second), "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(compiled.controls().get("/root/subject#PRIMARY").rules())
                .extracting(FixtureRule::ruleId)
                .containsExactly("first-attempt", "second-attempt");
    }

    @Test
    void overlappingAttemptSelectorsAreRejectedAtEqualPrecedence() {
        FixtureRule first = rule("attempts-1-2", selector(List.of(1, 2), List.of()),
                FixtureRule.Behavior.returning("first"));
        FixtureRule second = rule("attempts-2-3", selector(List.of(2, 3), List.of()),
                FixtureRule.Behavior.returning("second"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(first, second), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("CONTROL_PLAN_AMBIGUOUS");
                    assertThat(ex.diagnostics().getFirst()).contains("attempts-1-2", "attempts-2-3");
                });
    }

    @Test
    void nonCanonicalOrOutOfRangeDynamicCoordinatesAreRejected() {
        FixtureRule nonIncreasing = rule("non-increasing", selector(List.of(2, 1), List.of()),
                FixtureRule.Behavior.returning("x"));
        FixtureRule duplicate = rule("duplicate", selector(List.of(), List.of(1, 1)),
                FixtureRule.Behavior.returning("x"));
        FixtureRule outOfRange = rule("out-of-range", selector(List.of(100_001), List.of()),
                FixtureRule.Behavior.returning("x"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(nonIncreasing), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("strictly increasing")));
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(duplicate), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("duplicates")));
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                bundle(outOfRange), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("between 1 and 100000")));
    }

    @Test
    void unconfiguredExternalEffectCompilesToImplicitDeny() {
        CompiledExecutionControl result = compiler.compile(graph(new ExternalOperator()),
                bundle(), "GRAPH_CONTRACT_TEST", TARGET);

        assertThat(result.controls()).containsKey("/root/subject#PRIMARY");
        assertThat(result.controls().get("/root/subject#PRIMARY").implicitDeny()).isTrue();
        assertThat(result.effectivePlan().resolvedSites()).singleElement().satisfies(site ->
                assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.DENIED));
    }

    @Test
    void explicitRealCannotBypassExternalEffectIsolation() {
        FixtureRule real = rule("unsafe-real", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.real());

        assertThatThrownBy(() -> compiler.compile(graph(new ExternalOperator()), bundle(real),
                "OPERATOR_UNIT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.code()).isEqualTo("CONTROL_PLAN_UNSAFE_EXTERNAL_REAL"));
    }

    @Test
    void mirrorCompilationInterceptsCapabilityDeclaredReadOnlyExternalSites() {
        CompiledExecutionControl compiled = compiler.compileMirror(
                graph(new ReadOnlyOperator()), logicalBundle(), "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of("/root/subject#PRIMARY"));

        assertThat(compiled.controls().get("/root/subject#PRIMARY").implicitDeny()).isTrue();
        assertThat(compiled.controls().get("/root/subject#PRIMARY").resolutionStrategy())
                .isEqualTo(CompiledExecutionControl.ResolvedControl.ResolutionStrategy
                        .MIRROR_SOURCE_THEN_SELECTOR);
        assertThat(compiled.controls().get("/root/subject#PRIMARY").resolverOrder())
                .containsExactly(MirrorPlan.MirrorSource.ABSTAINED);
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.DENIED);
            assertThat(site.ruleRefs()).containsExactly(
                    "implicit-deny:/root/subject#PRIMARY");
        });
    }

    @Test
    void mirrorCompilationRejectsRealFallbackAndUnknownCapabilitySites() {
        FixtureRule unsafe = rule("unsafe-real", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.real());
        assertThatThrownBy(() -> compiler.compileMirror(
                graph(new ReadOnlyOperator()), logicalBundle(unsafe), "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of("/root/subject#PRIMARY")))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTROL_PLAN_UNSAFE_EXTERNAL_REAL"));

        assertThatThrownBy(() -> compiler.compileMirror(
                graph(new ReadOnlyOperator()), logicalBundle(), "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of("/root/missing#PRIMARY")))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo("CONTROL_PLAN_MIRROR_SITE_UNRESOLVED"));
    }

    @Test
    void mirrorCompilationConsumesTheSameInventoryUsedForCapabilityBinding() {
        ReadOnlyOperator first = new ReadOnlyOperator();
        ReadOnlyOperator replacement = new ReadOnlyOperator();
        registry.register("mirror-binding", first);
        Graph graph = registryGraph("mirror-binding");
        InvocationInventory frozen = new InvocationInventoryBuilder(registry).build(graph, TARGET);
        registry.register("mirror-binding", replacement);

        CompiledExecutionControl compiled = compiler.compileMirrorFromInventory(
                graph, logicalBundle(), "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of("/root/subject#PRIMARY"), frozen);

        assertThat(compiled.inventory().byInvocationSiteId()
                .get("/root/subject#PRIMARY").frozenOperator()).isSameAs(first);
        assertThat(compiled.inventory().byInvocationSiteId()
                .get("/root/subject#PRIMARY").frozenOperator()).isNotSameAs(replacement);
    }

    @Test
    void mirrorCompilationFreezesExactBeforeTrajectoryForTheSameSite()
            throws Exception {
        registry.register("mirror-binding", new ReadOnlyOperator());
        Graph graph = registryGraph("mirror-binding", 1);
        InvocationInventory frozen =
                new InvocationInventoryBuilder(registry).build(graph, TARGET);
        MirrorArtifactRef capability =
                ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef trajectoryPublication = ref(
                "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                "subject-retry", '4');
        String exactRequest = fingerprint('5');
        String trajectoryRequest = fingerprint('6');
        ResolvedCorpusPayloads payloads = ResolvedCorpusPayloads.of(List.of(
                        new ResolvedCorpusPayloads.CapabilityCorpus(
                                capability, publication, revision,
                                Instant.parse("2026-07-23T08:00:00Z"),
                                Instant.parse("2026-07-24T08:00:00Z"),
                                List.of(ResolvedCorpusPayloads.Sample.response(
                                        exactRequest,
                                        objectMapper.writeValueAsBytes("exact"),
                                        List.of(publication),
                                        List.of("exact"),
                                        1,
                                        List.of())),
                                List.of(new ResolvedCorpusPayloads.Trajectory(
                                        trajectoryRequest,
                                        trajectoryPublication,
                                        List.of(
                                                ResolvedCorpusPayloads.Sample.error(
                                                        trajectoryRequest,
                                                        "RETRY",
                                                        "TRANSIENT",
                                                        true,
                                                        fingerprint('7'),
                                                        List.of(
                                                                trajectoryPublication),
                                                        List.of("attempt-1"),
                                                        1,
                                                        List.of()),
                                                ResolvedCorpusPayloads.Sample.response(
                                                        trajectoryRequest,
                                                        objectMapper.writeValueAsBytes(
                                                                "recovered"),
                                                        List.of(
                                                                trajectoryPublication),
                                                        List.of("attempt-2"),
                                                        1,
                                                        List.of())))))))
                .bindSites(Map.of("/root/subject#PRIMARY", capability));

        CompiledExecutionControl compiled = compiler.compileMirrorFromInventory(
                graph, logicalBundle(), "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of("/root/subject#PRIMARY"),
                frozen, payloads);

        assertThat(compiled.controls().get("/root/subject#PRIMARY")
                .resolverOrder()).containsExactly(
                MirrorPlan.MirrorSource.RECORDED_EXACT,
                MirrorPlan.MirrorSource.RECORDED_TRAJECTORY,
                MirrorPlan.MirrorSource.ABSTAINED);
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement()
                .satisfies(site -> assertThat(site.fidelity())
                        .isEqualTo("RECORDED_EXACT+TRAJECTORY"));
    }

    @Test
    void mirrorCompilationFreezesClusterAfterExactForTheSameSite()
            throws Exception {
        registry.register("mirror-binding", new ReadOnlyOperator());
        Graph graph = registryGraph("mirror-binding", 1);
        InvocationInventory frozen =
                new InvocationInventoryBuilder(registry).build(graph, TARGET);
        MirrorArtifactRef capability =
                ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef clusterPublication = ref(
                "CAPABILITY_CORPUS_CLUSTER_PUBLICATION",
                "subject-cluster", '4');
        ResolvedCorpusPayloads.Cluster cluster =
                new ResolvedCorpusPayloads.Cluster(
                        clusterPublication,
                        List.of(new ResolvedCorpusPayloads.MatchCriterion(
                                "/operation",
                                objectMapper.valueToTree("lookup"))),
                        CapabilityCorpusClusterValidation.IdentityMode
                                .IDENTITY_FREE_RESPONSE,
                        List.of(),
                        objectMapper.writeValueAsBytes(
                                Map.of("tier", "gold")),
                        List.of(clusterPublication),
                        List.of("subject-cluster@1"),
                        new ArtifactProvenance.Confidence(
                                0.98,
                                0.91,
                                1,
                                CapabilityCorpusClusterValidation
                                        .CONFIDENCE_METHOD),
                        0.8,
                        List.of("STATE_DEPENDENCE_NOT_MODELED"));
        ResolvedCorpusPayloads payloads = ResolvedCorpusPayloads.of(List.of(
                        new ResolvedCorpusPayloads.CapabilityCorpus(
                                capability,
                                publication,
                                revision,
                                Instant.parse("2026-07-23T08:00:00Z"),
                                Instant.parse("2026-07-24T08:00:00Z"),
                                List.of(
                                        ResolvedCorpusPayloads.Sample.response(
                                                fingerprint('5'),
                                                objectMapper.writeValueAsBytes(
                                                        "exact"),
                                                List.of(publication),
                                                List.of("exact"),
                                                1,
                                                List.of())),
                                List.of(),
                                List.of(cluster))))
                .bindSites(Map.of("/root/subject#PRIMARY", capability));

        CompiledExecutionControl compiled =
                compiler.compileMirrorFromInventory(
                        graph,
                        logicalBundle(),
                        "MIRROR_REHEARSAL",
                        TARGET,
                        ResolvedReplayPayloads.empty(),
                        Set.of("/root/subject#PRIMARY"),
                        frozen,
                        payloads);

        assertThat(compiled.controls().get("/root/subject#PRIMARY")
                .resolverOrder()).containsExactly(
                MirrorPlan.MirrorSource.RECORDED_EXACT,
                MirrorPlan.MirrorSource.RECORDED_CLUSTER,
                MirrorPlan.MirrorSource.ABSTAINED);
        assertThat(compiled.effectivePlan().resolvedSites())
                .singleElement()
                .satisfies(site -> assertThat(site.fidelity())
                        .isEqualTo("RECORDED_EXACT+CLUSTER"));
    }

    @Test
    void mirrorCompilationRejectsTrajectoryLongerThanNodeRetryCapacity()
            throws Exception {
        registry.register("mirror-binding", new ReadOnlyOperator());
        Graph graph = registryGraph("mirror-binding");
        InvocationInventory frozen =
                new InvocationInventoryBuilder(registry).build(graph, TARGET);
        MirrorArtifactRef capability =
                ref("CAPABILITY", "operator:subject", '1');
        MirrorArtifactRef publication = ref(
                "CAPABILITY_CORPUS_PUBLICATION", "subject-corpus", '2');
        MirrorArtifactRef revision = ref(
                "CAPABILITY_CORPUS_REVISION", "subject-corpus", '3');
        MirrorArtifactRef trajectoryPublication = ref(
                "CAPABILITY_CORPUS_TRAJECTORY_PUBLICATION",
                "subject-retry", '4');
        String requestFingerprint = fingerprint('5');
        ResolvedCorpusPayloads payloads = ResolvedCorpusPayloads.of(List.of(
                        new ResolvedCorpusPayloads.CapabilityCorpus(
                                capability,
                                publication,
                                revision,
                                Instant.parse("2026-07-23T08:00:00Z"),
                                Instant.parse("2026-07-24T08:00:00Z"),
                                List.of(),
                                List.of(new ResolvedCorpusPayloads.Trajectory(
                                        requestFingerprint,
                                        trajectoryPublication,
                                        List.of(
                                                ResolvedCorpusPayloads.Sample.error(
                                                        requestFingerprint,
                                                        "RETRY",
                                                        "TRANSIENT",
                                                        true,
                                                        fingerprint('6'),
                                                        List.of(
                                                                trajectoryPublication),
                                                        List.of("attempt-1"),
                                                        1,
                                                        List.of()),
                                                ResolvedCorpusPayloads.Sample.response(
                                                        requestFingerprint,
                                                        objectMapper.writeValueAsBytes(
                                                                "recovered"),
                                                        List.of(
                                                                trajectoryPublication),
                                                        List.of("attempt-2"),
                                                        1,
                                                        List.of())))))))
                .bindSites(Map.of("/root/subject#PRIMARY", capability));

        assertThatThrownBy(() -> compiler.compileMirrorFromInventory(
                graph,
                logicalBundle(),
                "MIRROR_REHEARSAL",
                TARGET,
                ResolvedReplayPayloads.empty(),
                Set.of("/root/subject#PRIMARY"),
                frozen,
                payloads))
                .isInstanceOfSatisfying(
                        ControlPlanRejectedException.class,
                        failure -> assertThat(failure.code()).isEqualTo(
                                "CONTROL_PLAN_TRAJECTORY_RETRY_INCOMPATIBLE"));
    }

    @Test
    void mirrorModeIsFingerprintVisibleEvenWhenItsExternalSiteSetIsEmpty() {
        Graph graph = graph(new ReadOnlyOperator());
        FixtureBundle fixture = logicalBundle();

        CompiledExecutionControl ordinary = compiler.compile(
                graph, fixture, "MIRROR_REHEARSAL", TARGET);
        CompiledExecutionControl emptyMirror = compiler.compileMirror(
                graph, fixture, "MIRROR_REHEARSAL", TARGET,
                ResolvedReplayPayloads.empty(), Set.of());

        assertThat(emptyMirror.effectivePlan().planFingerprint())
                .isNotEqualTo(ordinary.effectivePlan().planFingerprint());
        assertThat(emptyMirror.effectivePlan().defaultPolicies())
                .containsEntry("mirrorResolverPrecedence", "FIXED_V1");
    }

    @Test
    void mirrorCompilationOrdersOwnerRulesBeforeMoreSpecificGovernedReplayRules() {
        FixtureRule replay = rule("governed-replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));
        FixtureRule owner = rule("owner-fallback", FixtureRule.Selector.any(),
                FixtureRule.Behavior.returning("owner"));

        CompiledExecutionControl compiled = compiler.compileMirror(
                graph(new ReadOnlyOperator()), logicalBundle(replay, owner),
                "MIRROR_REHEARSAL", TARGET, replays(true, "source-a"),
                Set.of("/root/subject#PRIMARY"));
        CompiledExecutionControl.ResolvedControl control =
                compiled.controls().get("/root/subject#PRIMARY");

        assertThat(control.rules()).extracting(FixtureRule::ruleId)
                .containsExactly("owner-fallback", "governed-replay");
        assertThat(control.resolverOrder()).containsExactly(
                MirrorPlan.MirrorSource.OWNER_SPECIFIED,
                MirrorPlan.MirrorSource.GOVERNED_REPLAY,
                MirrorPlan.MirrorSource.ABSTAINED);
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement().satisfies(site ->
                assertThat(site.ruleRefs()).containsExactly(
                        "owner-fallback", "governed-replay"));
    }

    @Test
    void mirrorCompilationWithNoExternalEdgesStillRejectsInternalFixtureControls() {
        FixtureRule internal = rule("internal", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.returning("replaced"));

        assertThatThrownBy(() -> compiler.compileMirror(
                graph(new ReadOnlyOperator()), logicalBundle(internal),
                "MIRROR_REHEARSAL", TARGET, ResolvedReplayPayloads.empty(), Set.of()))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure ->
                        assertThat(failure.code()).isEqualTo(
                                "CONTROL_PLAN_MIRROR_INTERNAL_CONTROL"));
    }

    @Test
    void unsafeRegexIsRejectedBeforeGraphExecution() {
        FixtureRule unsafe = new FixtureRule(FixtureRule.SCHEMA_VERSION, "unsafe-regex",
                FixtureRule.Selector.node("subject").matching(new FixtureRule.Match(
                        null, Map.of(), List.of(), List.of(), Map.of(), "",
                        Map.of("/value", "(a+)+$"))),
                FixtureRule.Behavior.returning("fixture"), FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(unsafe),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOf(ControlPlanRejectedException.class)
                .hasMessageContaining("grouping")
                .hasMessageContaining("boundedRegex");
    }

    @Test
    void compiledPlanRetainsTheBindingThatItsFingerprintDescribes() {
        ReadOnlyOperator first = new ReadOnlyOperator();
        ReadOnlyOperator replacement = new ReadOnlyOperator();
        registry.register("mutable-binding", first);
        Graph graph = registryGraph("mutable-binding");

        CompiledExecutionControl compiled = compiler.compile(
                graph, bundle(), "GRAPH_CONTRACT_TEST", TARGET);
        registry.register("mutable-binding", replacement);

        assertThat(compiled.inventory().byInvocationSiteId()
                .get("/root/subject#PRIMARY").frozenOperator()).isSameAs(first);
        assertThat(compiled.inventory().byInvocationSiteId()
                .get("/root/subject#PRIMARY").frozenOperator()).isNotSameAs(replacement);
    }

    @Test
    void logicalTimeControlsRequireAndAcceptAnExplicitRunClock() {
        FixtureRule delay = rule("delay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.delayed(Duration.ofSeconds(5), "later"));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(delay),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("logicalClock")));

        CompiledExecutionControl compiled = compiler.compile(graph(new ReadOnlyOperator()),
                logicalBundle(delay), "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(compiled.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.behavior()).isEqualTo(FixtureRule.BehaviorKind.DELAY);
            assertThat(site.resolution()).isEqualTo(EffectiveExecutionPlan.Resolution.TEST_DOUBLE);
        });
    }

    @Test
    void invalidTimePayloadsFailClosedAndRandomSeedFreezesServiceBindings() {
        FixtureRule misplacedAfter = rule("bad-after", FixtureRule.Selector.node("subject"),
                new FixtureRule.Behavior(FixtureRule.BehaviorKind.RETURN,
                        FixtureRule.DoubleBoundary.NODE, "value", "", null, Map.of(), "", "", "",
                        Duration.ofSeconds(1), List.of(), ""));
        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()),
                logicalBundle(misplacedAfter), "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("only valid")));

        FixtureBundle seeded = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of());
        CompiledExecutionControl compiled = compiler.compile(graph(new ReadOnlyOperator()), seeded,
                "GRAPH_CONTRACT_TEST", TARGET);
        assertThat(compiled.effectivePlan().schemaVersion())
                .isEqualTo(EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(compiled.effectivePlan().executionServiceBindings())
                .filteredOn(binding -> List.of("RANDOM", "UUID").contains(binding.service()))
                .allSatisfy(binding -> {
                    assertThat(binding.available()).isTrue();
                    assertThat(binding.deterministic()).isTrue();
                    assertThat(binding.configurationFingerprint()).startsWith("sha256:");
                    assertThat(binding.certificationGaps()).isEmpty();
                });
    }

    @Test
    void restoredProviderStateContinuesOnlyTheExactRecompiledPlan() throws Exception {
        Graph graph = graph(new ReadOnlyOperator());
        FixtureBundle fixture = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 42L,
                List.of(), List.of(), Map.of());
        CompiledExecutionControl running = compiler.compile(
                graph, fixture, "GRAPH_CONTRACT_TEST", TARGET);
        running.executionServices().services().timeSource().sleep(Duration.ofSeconds(3));
        running.executionServices().services().randomSource().nextLong("decision-scope");
        ExecutionServiceStateSnapshot snapshot = running.executionServices().snapshotState();

        CompiledExecutionControl resumed = compiler.compile(graph, fixture,
                "GRAPH_CONTRACT_TEST", TARGET, ResolvedReplayPayloads.empty(), snapshot);

        assertThat(resumed.effectivePlan().planFingerprint())
                .isEqualTo(running.effectivePlan().planFingerprint());
        assertThat(resumed.executionServices().services().timeSource().now())
                .isEqualTo(Instant.parse("2026-07-15T00:00:03Z"));
        assertThat(resumed.executionServices().services().randomSource().nextLong("decision-scope"))
                .isEqualTo(running.executionServices().services().randomSource()
                        .nextLong("decision-scope"));

        FixtureBundle drifted = new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1,
                TARGET, "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), 43L,
                List.of(), List.of(), Map.of());
        assertThatThrownBy(() -> compiler.compile(graph, drifted,
                "GRAPH_CONTRACT_TEST", TARGET, ResolvedReplayPayloads.empty(), snapshot))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, failure -> {
                    assertThat(failure.code()).isEqualTo("CONTROL_PLAN_UNAVAILABLE");
                    assertThat(failure.diagnostics()).containsExactly(
                            "Checkpointed execution-service state does not match the frozen plan.");
                });
    }

    @Test
    void replayRequiresAnExactPreResolvedDependencyClosure() {
        FixtureRule replay = rule("replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(replay),
                "GRAPH_CONTRACT_TEST", TARGET))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("not resolved")));

        assertThatThrownBy(() -> compiler.compile(graph(new ReadOnlyOperator()), bundle(),
                "GRAPH_CONTRACT_TEST", TARGET, replays(true, "source-a")))
                .isInstanceOfSatisfying(ControlPlanRejectedException.class, ex ->
                        assertThat(ex.diagnostics()).anyMatch(item -> item.contains("not referenced")));
    }

    @Test
    void replayDependencyIsPayloadFreeAndParticipatesInTheVersionTwoPlanIdentity() throws Exception {
        FixtureRule replay = rule("replay", FixtureRule.Selector.node("subject"),
                FixtureRule.Behavior.replaying(REPLAY_REF));

        CompiledExecutionControl first = compiler.compile(graph(new ReadOnlyOperator()), bundle(replay),
                "GRAPH_CONTRACT_TEST", TARGET, replays(true, "source-a"));
        CompiledExecutionControl changedLineage = compiler.compile(graph(new ReadOnlyOperator()), bundle(replay),
                "GRAPH_CONTRACT_TEST", TARGET, replays(true, "source-b"));

        assertThat(first.effectivePlan().schemaVersion())
                .isEqualTo(EffectiveExecutionPlan.SCHEMA_VERSION);
        assertThat(first.effectivePlan().replayDependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.replayRef()).isEqualTo(REPLAY_REF);
            assertThat(dependency.sourceRunId()).isEqualTo("source-a");
            assertThat(dependency.certificationEligible()).isTrue();
        });
        assertThat(first.effectivePlan().resolvedSites()).singleElement().satisfies(site -> {
            assertThat(site.behavior()).isEqualTo(FixtureRule.BehaviorKind.REPLAY);
            assertThat(site.fidelity()).isEqualTo("REPLAYED");
        });
        assertThat(first.effectivePlan().planFingerprint())
                .isNotEqualTo(changedLineage.effectivePlan().planFingerprint());
        assertThat(new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(first.effectivePlan()))
                .doesNotContain("APPROVE", "canonicalJson");
    }

    private static Graph graph(Operator<Object, Object> operator) {
        return new GraphBuilder("subject-graph").node("subject", operator).build();
    }

    private static Graph withOutputSchema(Graph graph,
                                          com.leanowtech.bloge.core.schema.SchemaDescriptor schema) {
        var node = graph.nodes().get("subject").toBuilder().outputSchema(schema).build();
        return new Graph(graph.name(), Map.of("subject", node), graph.edges(), graph.sourceNodes(),
                graph.terminalNodes(), graph.schemaValidationLevel(), graph.embeddedOperators(),
                graph.declaredInputSchema(), graph.declaredOutputSchema(), graph.sagaConfig(),
                graph.definitionSource(), graph.streamingOutputNodeId(), graph.streamingInputs());
    }

    private static Graph registryGraph(String operatorRef) {
        return registryGraph(operatorRef, 0);
    }

    private static Graph registryGraph(String operatorRef, int retryAttempts) {
        Graph embedded = new GraphBuilder("subject-graph")
                .node("subject", new ReadOnlyOperator())
                .retry(retryAttempts)
                .build();
        var node = embedded.nodes().get("subject").toBuilder().operatorRef(operatorRef).build();
        return new Graph(embedded.name(), Map.of("subject", node), embedded.edges(),
                embedded.sourceNodes(), embedded.terminalNodes(), embedded.schemaValidationLevel(),
                Map.of(), embedded.declaredInputSchema(), embedded.declaredOutputSchema(),
                embedded.sagaConfig(), embedded.definitionSource(), embedded.streamingOutputNodeId(),
                embedded.streamingInputs());
    }

    private static Graph registryGraphWithAuxiliarySite(
            String operatorRef, boolean subjectFirst) {
        Graph embedded = new GraphBuilder("subject-graph")
                .node("subject", new ReadOnlyOperator())
                .node("auxiliary", new ReadOnlyOperator())
                .build();
        var subject = embedded.nodes().get("subject").toBuilder()
                .operatorRef(operatorRef).build();
        Map<String, com.leanowtech.bloge.core.model.NodeSpec> nodes = new LinkedHashMap<>();
        if (subjectFirst) {
            nodes.put("subject", subject);
            nodes.put("auxiliary", embedded.nodes().get("auxiliary"));
        } else {
            nodes.put("auxiliary", embedded.nodes().get("auxiliary"));
            nodes.put("subject", subject);
        }
        return new Graph(embedded.name(), nodes, embedded.edges(), embedded.sourceNodes(),
                embedded.terminalNodes(), embedded.schemaValidationLevel(),
                Map.of("auxiliary", embedded.embeddedOperators().get("auxiliary")),
                embedded.declaredInputSchema(), embedded.declaredOutputSchema(),
                embedded.sagaConfig(), embedded.definitionSource(), embedded.streamingOutputNodeId(),
                embedded.streamingInputs());
    }

    private static FixtureBundle bundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "fixture", 1, TARGET,
                "INTERNAL", null, null, List.of(rules), List.of(), Map.of());
    }

    private static FixtureBundle logicalBundle(FixtureRule... rules) {
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "logical-fixture", 1, TARGET,
                "INTERNAL", Instant.parse("2026-07-15T00:00:00Z"), null,
                List.of(rules), List.of(), Map.of());
    }

    private static FixtureRule rule(String id, FixtureRule.Selector selector,
                                    FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, id, selector, behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule.Selector selector(List<Integer> attempts,
                                                 List<Integer> occurrences) {
        return new FixtureRule.Selector("/root", "subject", "", "", "",
                List.of(), List.of(), InvocationSite.InvocationKind.PRIMARY,
                attempts, occurrences, "", FixtureRule.Match.none());
    }

    private static ResolvedReplayPayloads replays(boolean certificationEligible,
                                                   String sourceRunId) {
        return new ResolvedReplayPayloads(Map.of(REPLAY_REF, new ResolvedReplayPayloads.Payload(
                REPLAY_REF, "INTERNAL", "{\"decision\":\"APPROVE\"}", sourceRunId,
                "decision", 1, "sha256:" + "d".repeat(64), "sha256:" + "e".repeat(64),
                Instant.parse("2030-01-01T00:00:00Z"), certificationEligible,
                certificationEligible ? List.of() : List.of("SOURCE_NOT_CERTIFIABLE"))));
    }

    private static MirrorArtifactRef ref(
            String kind, String id, char fingerprint) {
        return new MirrorArtifactRef(
                kind, id, 1,
                fingerprint(fingerprint));
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private static class ReadOnlyOperator implements Operator<Object, Object> {
        @Override
        public Object execute(Object input, OperatorContext ctx) {
            return input;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    private static final class ExternalOperator extends ReadOnlyOperator {
        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.EXTERNAL_CALL;
        }
    }
}
