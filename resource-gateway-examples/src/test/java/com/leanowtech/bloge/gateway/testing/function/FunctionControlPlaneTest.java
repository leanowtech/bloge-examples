package com.leanowtech.bloge.gateway.testing.function;

import com.leanowtech.bloge.core.model.CompiledGraph;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.GraphFunctionCall;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.ExpressionFunction;
import com.leanowtech.bloge.core.spi.FunctionCallSite;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.planning.InvocationInventory;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FunctionControlPlaneTest {

    private static final String FP = "sha256:" + "a".repeat(64);
    private static final Operator<Object, Object> OPERATOR = (input, context) -> input;

    @Test
    void siteKeyIsStrictAndDelimiterSafe() {
        FunctionInvocationSite first = new FunctionInvocationSite(
                "/root/a|b", "n=1", "f|x", 1, 2);
        FunctionInvocationSite second = new FunctionInvocationSite(
                "/root/a", "b|n=1", "f|x", 1, 2);

        assertThat(first.structuralKey()).isNotEqualTo(second.structuralKey());
        assertThat(first.toString()).doesNotContain("secret-payload");
        assertThatThrownBy(() -> new FunctionInvocationSite("root", "node", "f", 0, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.SITE_INVALID");
        assertThatThrownBy(() -> new FunctionInvocationSite("/root", "node", "", 0, 0))
                .isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionInvocationSite("/root", "node\u0000", "f", 0, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessageNotContaining("node");
        assertThatThrownBy(() -> new FunctionControlRule.Selector("/root", "node\u0000", "f", 0, 0))
                .isInstanceOf(FunctionControlException.class);
    }

    @Test
    void inventorySortsAndFingerprintsStably() {
        List<FunctionInvocationSite> input = List.of(
                site("/root/z", "node", "f", 2, 1),
                site("/root/a", "node", "f", 1, 1));
        String fingerprint = new FunctionInvocationInventory(input).inventoryFingerprint();

        for (int i = 0; i < 20; i++) {
            assertThat(new FunctionInvocationInventory(new ArrayList<>(input)).inventoryFingerprint())
                    .isEqualTo(fingerprint);
        }
        assertThatThrownBy(() -> new FunctionInvocationInventory(List.of(input.getFirst(), input.getFirst())))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.SITE_COLLISION");
    }

    @Test
    void builderMapsRootAndReusedNestedArtifactToEveryRuntimePath() {
        Graph child = graph("child", "child-node");
        Graph root = graph("root", "left", "right");
        CompiledGraph childArtifact = new CompiledGraph(child,
                List.of(new GraphFunctionCall("child-node", new FunctionCallSite("lookup", 3, 4))));
        CompiledGraph rootArtifact = new CompiledGraph(root,
                List.of(new GraphFunctionCall("left", new FunctionCallSite("rootFn", 1, 1))),
                Map.of("leftBody", childArtifact, "rightBody", childArtifact));

        InvocationInventory inventory = inventory(List.of(
                entry(root, "/root", "left"),
                entry(root, "/root", "right"),
                entry(child, "/root/left/child", "child-node"),
                entry(child, "/root/right/child", "child-node")));

        FunctionInvocationInventory result = new FunctionInvocationInventoryBuilder()
                .build(rootArtifact, inventory);

        assertThat(result.sites()).extracting(FunctionInvocationSite::graphPath)
                .containsExactlyInAnyOrder("/root", "/root/left/child", "/root/right/child");
        assertThat(result.sites()).extracting(FunctionInvocationSite::functionName)
                .containsExactlyInAnyOrder("rootFn", "lookup", "lookup");
    }

    @Test
    void builderPreservesImportForeachAndLoopArtifactRuntimePaths() {
        Graph root = new com.leanowtech.bloge.core.dsl.GraphBuilder("root")
                .node("import", OPERATOR)
                .node("foreach", OPERATOR)
                .node("loop", OPERATOR)
                .build();
        Graph imported = graph("imported", "node");
        Graph iterated = graph("iterated", "node");
        Graph looped = graph("looped", "node");
        CompiledGraph artifact = new CompiledGraph(root, List.of(), Map.of(
                "import", new CompiledGraph(imported,
                        List.of(new GraphFunctionCall("node", new FunctionCallSite("importFn", 1, 1)))),
                "foreach", new CompiledGraph(iterated,
                        List.of(new GraphFunctionCall("node", new FunctionCallSite("foreachFn", 2, 1)))),
                "loop", new CompiledGraph(looped,
                        List.of(new GraphFunctionCall("node", new FunctionCallSite("loopFn", 3, 1))))));

        FunctionInvocationInventory result = new FunctionInvocationInventoryBuilder().build(artifact,
                inventory(List.of(
                        entry(root, "/root", "import"),
                        entry(root, "/root", "foreach"),
                        entry(root, "/root", "loop"),
                        entry(imported, "/root/import/body", "node"),
                        entry(iterated, "/root/foreach/body", "node"),
                        entry(looped, "/root/loop/body", "node"))));

        assertThat(result.sites()).extracting(FunctionInvocationSite::graphPath)
                .containsExactlyInAnyOrder("/root/import/body", "/root/foreach/body", "/root/loop/body");
        assertThat(result.sites()).extracting(FunctionInvocationSite::functionName)
                .containsExactlyInAnyOrder("importFn", "foreachFn", "loopFn");
    }

    @Test
    void builderRejectsMissingArtifactOwner() {
        Graph graph = graph("root", "node", "other");
        CompiledGraph compiled = new CompiledGraph(graph,
                List.of(new GraphFunctionCall("other", new FunctionCallSite("f", 1, 1))));

        assertThatThrownBy(() -> new FunctionInvocationInventoryBuilder().build(
                compiled, inventory(List.of(entry(graph, "/root", "node")))))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.INVENTORY_INVALID");
    }

    @Test
    void declarationFreezesSchemasAndRegistryDriftIsRejected() {
        FunctionLibraryDeclaration declaration = declaration("clock", false, Set.of("TIME"),
                FunctionEffect.ENVIRONMENT_FACT);
        assertThat(declaration.functionFingerprint()).startsWith("sha256:");
        assertThatThrownBy(() -> declaration.parameterSchema().put("x", Map.of()))
                .isInstanceOf(UnsupportedOperationException.class);

        FunctionInvocationInventory inventory = new FunctionInvocationInventory(
                List.of(site("/root", "node", "clock", 1, 1)));
        ExpressionFunction drifted = function("clock", true, Set.of());

        assertThatThrownBy(() -> compiler().compile(inventory, Map.of("clock", drifted),
                List.of(declaration), List.of()))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.DECLARATION_DRIFT");
    }

    @Test
    void directSiteAndCertifiedEnvironmentControlAreDistinguished() {
        FunctionInvocationSite site = site("/root", "node", "clock", 1, 1);
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(List.of(site));
        FunctionControlRule rule = new FunctionControlRule("clock-rule",
                new FunctionControlRule.Selector("/root", "node", "clock", 1, 1),
                FunctionControlRule.Behavior.RETURN, 7, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);

        CompiledFunctionControlPlan plan = compiler().compile(inventory,
                Map.of("clock", function("clock", false, Set.of("TIME"))),
                List.of(declaration("clock", false, Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT)),
                List.of(rule));
        assertThat(plan.controls()).singleElement().satisfies(control -> {
            assertThat(control.mode()).isEqualTo(FunctionControlMode.CONTROLLED);
            assertThat(control.evidenceCeiling()).isEqualTo(FunctionEvidenceCeiling.CERTIFIABLE);
            assertThat(control.returnValueFingerprint()).startsWith("sha256:");
        });
        assertThat(plan.payloadFreeProjection().getFirst()).doesNotContainKey("returnValue");
        assertThat(new FunctionControlCompiler().compile(inventory,
                Map.of("clock", function("clock", false, Set.of("TIME"))),
                List.of(declaration("clock", false, Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT)),
                List.of()).controls().getFirst().mode()).isEqualTo(FunctionControlMode.DIRECT);
    }

    @Test
    void pureControlRequiresExplicitOverrideAndBecomesExploratory() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(
                List.of(site("/root", "node", "add", 1, 1)));
        FunctionControlRule rule = new FunctionControlRule("add-rule",
                new FunctionControlRule.Selector("/root", "node", "add", 1, 1),
                FunctionControlRule.Behavior.RETURN, 3, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("add", function("add", true, Set.of())),
                List.of(declaration("add", true, Set.of(), FunctionEffect.PURE_COMPUTATION)),
                List.of(rule))).hasMessage("RG.FUNCTION.PURE_NOT_OVERRIDDEN");

        FunctionControlRule forced = new FunctionControlRule("add-rule",
                rule.selector(), FunctionControlRule.Behavior.RETURN, 3, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), true, 0);
        assertThat(compiler().compile(inventory, Map.of("add", function("add", true, Set.of())),
                List.of(declaration("add", true, Set.of(), FunctionEffect.PURE_COMPUTATION)),
                List.of(forced)).evidenceCeiling()).isEqualTo(FunctionEvidenceCeiling.EXPLORATORY);
    }

    @Test
    void unknownDeclarationIsPreviewOnlyAndCannotBeControlled() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(
                List.of(site("/root", "node", "old", 1, 1)));
        FunctionLibraryDeclaration unknown = new FunctionLibraryDeclaration("old", false, Set.of(),
                FunctionEffect.EXTERNAL_QUERY, Map.of(), Map.of(), FunctionDeclarationStatus.UNKNOWN, "");
        assertThat(compiler().compile(inventory, Map.of("old", function("old", false, Set.of())),
                List.of(unknown), List.of()).evidenceCeiling()).isEqualTo(FunctionEvidenceCeiling.PREVIEW);
        FunctionControlRule rule = new FunctionControlRule("old-rule",
                new FunctionControlRule.Selector("/root", "node", "old", 1, 1),
                FunctionControlRule.Behavior.THROW, null, "blocked", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThatThrownBy(() -> compiler().compile(inventory, Map.of("old", function("old", false, Set.of())),
                List.of(unknown), List.of(rule))).hasMessage("RG.FUNCTION.UNKNOWN_NOT_CERTIFIABLE");
    }

    @Test
    void ruleMatchingRejectsZeroHitOverlapAndAmbiguousBroadSelector() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(List.of(
                site("/root", "a", "f", 1, 1), site("/root", "b", "f", 2, 1)));
        Map<String, ExpressionFunction> runtime = Map.of("f", function("f", false, Set.of("TIME")));
        List<FunctionLibraryDeclaration> declarations = List.of(
                declaration("f", false, Set.of("TIME"), FunctionEffect.EXTERNAL_QUERY));
        FunctionControlRule broad = new FunctionControlRule("broad",
                new FunctionControlRule.Selector("", "", "f", -1, -1),
                FunctionControlRule.Behavior.DELAY, "delayed", "", Duration.ofMillis(1),
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThatThrownBy(() -> compiler().compile(inventory, runtime, declarations, List.of(broad)))
                .hasMessage("RG.FUNCTION.RULE_AMBIGUOUS");
        FunctionControlRule noHit = new FunctionControlRule("no-hit",
                new FunctionControlRule.Selector("/root", "a", "other", 1, 1),
                FunctionControlRule.Behavior.RETURN, 1, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThatThrownBy(() -> compiler().compile(
                new FunctionInvocationInventory(List.of(inventory.sites().getFirst())), runtime,
                declarations, List.of(noHit))).hasMessage("RG.FUNCTION.RULE_ZERO_MATCH");
    }

    @Test
    void valuesDurationsAndCountsAreBoundedAndDefensivelyFrozen() {
        List<Object> value = new ArrayList<>(List.of(Map.of("key", "before")));
        FunctionControlRule rule = new FunctionControlRule("r",
                new FunctionControlRule.Selector("/root", "n", "f", 0, 0),
                value, FunctionControlRule.Behavior.RETURN, value, "", Duration.ZERO,
                new FunctionControlRule.Consumption(0, 2), false, 0);
        value.clear();
        assertThat(rule.expectedArguments()).hasSize(1);
        assertThat(rule.returnValueFingerprint()).startsWith("sha256:");
        assertThatThrownBy(() -> new FunctionControlRule("bad",
                rule.selector(), FunctionControlRule.Behavior.DELAY, null, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0))
                .isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionControlRule.Consumption(2, 1))
                .isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionControlRule("overflow", rule.selector(),
                FunctionControlRule.Behavior.DELAY, "late", "", Duration.ofSeconds(Long.MAX_VALUE),
                FunctionControlRule.Consumption.exactly(1), false, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.RULE_INVALID");
        FunctionControlRule explicitNull = new FunctionControlRule("explicit-null", rule.selector(),
                FunctionControlRule.Behavior.DELAY, null, "", Duration.ofMillis(1),
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThat(explicitNull.returnValueProvided()).isTrue();
        assertThat(explicitNull.returnValueFingerprint()).startsWith("sha256:");
    }

    @Test
    void parameterCandidatesKeepExactAndWildcardPayloadFreeProjections() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(
                List.of(site("/root", "n", "f", 1, 1)));
        Map<String, Object> arrayOfIntegers = Map.of(
                "type", "array", "items", Map.of("type", "integer"));
        Map<String, Object> integer = Map.of("type", "integer");
        List<FunctionControlRule> rules = List.of(
                returnRule("exact-one", List.of(1), 2),
                returnRule("exact-two", List.of(2), 3),
                returnRule("fallback", null, 0, 100));

        CompiledFunctionControlPlan plan = compiler().compile(inventory,
                Map.of("f", function("f", false, Set.of("TIME"))),
                List.of(declaration("f", "f", false, Set.of("TIME"),
                        FunctionEffect.EXTERNAL_QUERY, arrayOfIntegers, integer)), rules);
        ResolvedFunctionControl resolved = plan.controls().getFirst();
        assertThat(resolved.expectedArgumentsFingerprints()).hasSize(3)
                .allMatch(value -> value.isEmpty() || value.startsWith("sha256:"));
        assertThat(resolved.candidateProjections()).hasSize(3);
        assertThat(resolved.candidateProjections().getFirst().get("expectedArgumentsFingerprint"))
                .isNotNull();
        assertThat(resolved.candidateProjections().getLast().get("expectedArgumentsFingerprint"))
                .isNull();
        assertThat(plan.payloadFreeProjection().getFirst()).doesNotContainKey("expectedArguments");

        FunctionControlRule duplicate = returnRule("duplicate", List.of(1), 9);
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("f", function("f", false, Set.of("TIME"))),
                List.of(declaration("f", "f", false, Set.of("TIME"),
                        FunctionEffect.EXTERNAL_QUERY, arrayOfIntegers, integer)),
                List.of(rules.getFirst(), duplicate)))
                .hasMessage("RG.FUNCTION.RULE_OVERLAP");
        FunctionControlRule secondFallback = returnRule("fallback-two", null, 1);
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("f", function("f", false, Set.of("TIME"))),
                List.of(declaration("f", "f", false, Set.of("TIME"),
                        FunctionEffect.EXTERNAL_QUERY, arrayOfIntegers, integer)),
                List.of(rules.get(2), secondFallback)))
                .hasMessage("RG.FUNCTION.RULE_OVERLAP");
    }

    @Test
    void compilerValidatesExpectedArgumentsAndReturnAgainstDeclaredSchemas() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(
                List.of(site("/root", "n", "f", 1, 1)));
        Map<String, Object> arrayOfIntegers = Map.of(
                "type", "array", "items", Map.of("type", "integer"));
        Map<String, Object> integer = Map.of("type", "integer");
        FunctionLibraryDeclaration declaration = declaration("f", "f", false, Set.of("TIME"),
                FunctionEffect.EXTERNAL_QUERY, arrayOfIntegers, integer);
        FunctionControlRule badArguments = returnRule("bad-args", List.of("secret"), 1);
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("f", function("f", false, Set.of("TIME"))), List.of(declaration),
                List.of(badArguments))).hasMessage("RG.FUNCTION.SCHEMA_INVALID");
        FunctionControlRule badReturn = returnRule("bad-return", List.of(1), "secret");
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("f", function("f", false, Set.of("TIME"))), List.of(declaration),
                List.of(badReturn))).hasMessage("RG.FUNCTION.SCHEMA_INVALID");
    }

    @Test
    void mixedEvidenceUsesWeakestCeilingAndAliasIsSupported() {
        FunctionInvocationInventory inventory = new FunctionInvocationInventory(List.of(
                site("/root", "environment", "clock", 1, 1),
                site("/root", "legacy", "old", 2, 1)));
        FunctionLibraryDeclaration clock = declaration("clock", "system.clock", false,
                Set.of("TIME"), FunctionEffect.ENVIRONMENT_FACT, Map.of(), Map.of());
        FunctionLibraryDeclaration old = new FunctionLibraryDeclaration("old", false, Set.of(),
                FunctionEffect.EXTERNAL_QUERY, Map.of(), Map.of(), FunctionDeclarationStatus.UNKNOWN, "");
        FunctionControlRule controlled = new FunctionControlRule("clock-rule",
                new FunctionControlRule.Selector("/root", "environment", "clock", 1, 1),
                FunctionControlRule.Behavior.RETURN, 1, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        CompiledFunctionControlPlan plan = compiler().compile(inventory,
                Map.of("alias", function("system.clock", false, Set.of("TIME")),
                        "old", function("old", false, Set.of())),
                List.of(clock, old), List.of(controlled));
        assertThat(plan.evidenceCeiling()).isEqualTo(FunctionEvidenceCeiling.PREVIEW);
        assertThat(plan.controls().getFirst().runtimeFact().registryName()).isEqualTo("alias");

        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("alias", function("system.clock", false, Set.of("RANDOM")),
                        "old", function("old", false, Set.of())), List.of(clock, old), List.of(controlled)))
                .hasMessage("RG.FUNCTION.DECLARATION_DRIFT");
        assertThatThrownBy(() -> compiler().compile(inventory,
                Map.of("alias", function("wrong.clock", false, Set.of("TIME")),
                        "old", function("old", false, Set.of())), List.of(clock, old), List.of(controlled)))
                .hasMessage("RG.FUNCTION.DECLARATION_DRIFT");
    }

    @Test
    void contradictoryEffectsAndRuntimeFingerprintsFailClosed() {
        assertThatThrownBy(() -> declaration("bad", false, Set.of(), FunctionEffect.PURE_COMPUTATION))
                .hasMessage("RG.FUNCTION.DECLARATION_INVALID");
        assertThatThrownBy(() -> declaration("bad", false, Set.of(), FunctionEffect.ENVIRONMENT_FACT))
                .hasMessage("RG.FUNCTION.DECLARATION_INVALID");
        assertThatThrownBy(() -> new FunctionRuntimeFact("alias", "f", false, List.of("TIME"), "sha256:bad"))
                .hasMessage("RG.FUNCTION.RUNTIME_INVALID");
    }

    @Test
    void rootGraphMustBeBoundEvenWhenItContainsNoFunctionCalls() {
        Graph graph = graph("root", "node");
        CompiledGraph compiled = new CompiledGraph(graph, List.of());
        assertThatThrownBy(() -> new FunctionInvocationInventoryBuilder().build(
                compiled, inventory(List.of())))
                .hasMessage("RG.FUNCTION.INVENTORY_INVALID");
    }

    @Test
    void behaviorIsAClosedDiscriminatedUnion() {
        FunctionControlRule.Selector selector = new FunctionControlRule.Selector(
                "/root", "n", "f", 0, 0);
        assertThatThrownBy(() -> new FunctionControlRule("return-error", selector,
                FunctionControlRule.Behavior.RETURN, 1, "error", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0)).isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionControlRule("throw-return", selector,
                FunctionControlRule.Behavior.THROW, 1, "error", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0)).isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionControlRule("timeout-return", selector,
                FunctionControlRule.Behavior.TIMEOUT, 1, "", Duration.ofMillis(1),
                FunctionControlRule.Consumption.exactly(1), false, 0)).isInstanceOf(FunctionControlException.class);
        FunctionControlRule returnNull = new FunctionControlRule("return-null", selector,
                FunctionControlRule.Behavior.RETURN, null, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        FunctionControlRule delayNull = new FunctionControlRule("delay-null", selector,
                FunctionControlRule.Behavior.DELAY, null, "", Duration.ofMillis(1),
                FunctionControlRule.Consumption.exactly(1), false, 0);
        FunctionControlRule throwRule = new FunctionControlRule("throw", selector,
                FunctionControlRule.Behavior.THROW, null, "error", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0);
        FunctionControlRule timeoutRule = new FunctionControlRule("timeout", selector,
                FunctionControlRule.Behavior.TIMEOUT, null, "", Duration.ofMillis(1),
                FunctionControlRule.Consumption.exactly(1), false, 0);
        assertThat(returnNull.returnValueProvided()).isTrue();
        assertThat(returnNull.returnValueFingerprint()).startsWith("sha256:");
        assertThat(delayNull.returnValueProvided()).isTrue();
        assertThat(delayNull.returnValueFingerprint()).startsWith("sha256:");
        assertThat(returnNull.errorFingerprint()).isEmpty();
        assertThat(delayNull.errorFingerprint()).isEmpty();
        assertThat(throwRule.returnValueFingerprint()).isEmpty();
        assertThat(timeoutRule.returnValueFingerprint()).isEmpty();
        assertThat(throwRule.errorFingerprint()).startsWith("sha256:");
        assertThat(timeoutRule.errorFingerprint()).isEmpty();
        assertThatThrownBy(() -> new FunctionControlRule("return-unprovided", selector,
                FunctionControlRule.Behavior.RETURN)).isInstanceOf(FunctionControlException.class);
        assertThatThrownBy(() -> new FunctionControlRule("newline-error", selector,
                FunctionControlRule.Behavior.THROW, null, "bad\nerror", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0)).isInstanceOf(FunctionControlException.class);
    }

    @Test
    void sizeDepthAndCountLimitsFailWithoutPayloadDiagnostics() {
        assertThatThrownBy(() -> new FunctionInvocationSite("/root", "x".repeat(4_097), "f", 0, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.SITE_INVALID");
        assertThatThrownBy(() -> new FunctionInvocationSite("/root", "node\n", "f", 0, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.SITE_INVALID");
        List<Object> tooMany = new ArrayList<>();
        for (int i = 0; i < 257; i++) {
            tooMany.add(i);
        }
        assertThatThrownBy(() -> new FunctionControlRule("too-many",
                new FunctionControlRule.Selector("/root", "n", "f", 0, 0), tooMany,
                FunctionControlRule.Behavior.RETURN, 1, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessage("RG.FUNCTION.LIMIT_EXCEEDED");
        Object deep = 1;
        for (int i = 0; i < 65; i++) {
            deep = Map.of("next", deep);
        }
        Object deepSchema = deep;
        assertThatThrownBy(() -> new FunctionLibraryDeclaration("deep", false, Set.of(),
                FunctionEffect.EXTERNAL_QUERY, Map.of("type", "object", "x", deepSchema), Map.of()))
                .isInstanceOf(FunctionControlException.class)
                .hasMessageNotContaining("next");
        assertThatThrownBy(() -> new FunctionLibraryDeclaration("large", false, Set.of(),
                FunctionEffect.EXTERNAL_QUERY,
                Map.of("type", "string", "description", "sensitive-schema-".repeat(10_000)), Map.of()))
                .isInstanceOf(FunctionControlException.class)
                .hasMessageNotContaining("sensitive-schema");
        assertThatThrownBy(() -> new FunctionControlRule("long-error", new FunctionControlRule.Selector(
                "/root", "n", "f", 0, 0), FunctionControlRule.Behavior.THROW, null,
                "sensitive-error-".repeat(100), Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, 0))
                .isInstanceOf(FunctionControlException.class)
                .hasMessageNotContaining("sensitive-error");
    }

    private static FunctionControlCompiler compiler() {
        return new FunctionControlCompiler();
    }

    private static FunctionInvocationSite site(String graphPath, String node, String function,
                                               int line, int column) {
        return new FunctionInvocationSite(graphPath, node, function, line, column);
    }

    private static FunctionLibraryDeclaration declaration(String name, boolean pure,
                                                          Set<String> services, FunctionEffect effect) {
        return new FunctionLibraryDeclaration(name, pure, services, effect, Map.of(), Map.of());
    }

    private static FunctionLibraryDeclaration declaration(String name, String runtimeName,
                                                          boolean pure, Set<String> services,
                                                          FunctionEffect effect,
                                                          Map<String, Object> parameterSchema,
                                                          Map<String, Object> returnSchema) {
        return new FunctionLibraryDeclaration(name, runtimeName, pure, services, effect,
                parameterSchema, returnSchema);
    }

    private static FunctionControlRule returnRule(String ruleId, List<?> arguments, Object value) {
        return returnRule(ruleId, arguments, value, 0);
    }

    private static FunctionControlRule returnRule(String ruleId, List<?> arguments, Object value,
                                                  int priority) {
        return new FunctionControlRule(ruleId,
                new FunctionControlRule.Selector("/root", "n", "f", 1, 1), arguments,
                FunctionControlRule.Behavior.RETURN, value, "", Duration.ZERO,
                FunctionControlRule.Consumption.exactly(1), false, priority);
    }

    private static ExpressionFunction function(String name, boolean pure, Set<String> services) {
        return new ExpressionFunction() {
            @Override public String name() { return name; }
            @Override public Object apply(Object... args) { return null; }
            @Override public String returnType(String... argTypes) { return "Any"; }
            @Override public boolean isPure() { return pure; }
            @Override public Set<com.leanowtech.bloge.core.spi.ExecutionServiceKind> requiredExecutionServices() {
                return services.stream().map(com.leanowtech.bloge.core.spi.ExecutionServiceKind::valueOf).collect(java.util.stream.Collectors.toSet());
            }
        };
    }

    private static Graph graph(String name, String... nodeIds) {
        if (nodeIds.length == 1) {
            return new com.leanowtech.bloge.core.dsl.GraphBuilder(name)
                    .node(nodeIds[0], OPERATOR).build();
        }
        return new com.leanowtech.bloge.core.dsl.GraphBuilder(name)
                .node(nodeIds[0], OPERATOR)
                .node(nodeIds[1], OPERATOR)
                .build();
    }

    private static InvocationInventory inventory(List<InvocationInventory.Entry> entries) {
        Map<String, InvocationInventory.Entry> byEngine = new LinkedHashMap<>();
        Map<String, InvocationInventory.Entry> bySite = new LinkedHashMap<>();
        for (InvocationInventory.Entry entry : entries) {
            byEngine.put(entry.engineStructuralId(), entry);
            bySite.put(entry.site().invocationSiteId(), entry);
        }
        return new InvocationInventory(entries, byEngine, bySite);
    }

    private static InvocationInventory.Entry entry(Graph graph, String graphPath, String nodeId) {
        var node = graph.nodes().get(nodeId);
        InvocationSite site = new InvocationSite(InvocationSite.SCHEMA_VERSION, FP, graphPath,
                nodeId, "operator", "", "", FP, InvocationSite.InvocationKind.PRIMARY,
                null, "", null);
        String engineId = graphPath + "/" + nodeId + "#PRIMARY";
        return new InvocationInventory.Entry(graph, node, site, engineId, OPERATOR);
    }
}
