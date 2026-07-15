package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin operator-test entry that executes the exact runtime binding as a one-node BLOGE graph.
 *
 * <p>The micro graph intentionally reuses {@link TestRunService}; it is not a second execution
 * mechanism. Classification is affirmative: only a read-only operator, or an HTTP resource tested
 * with a transport-boundary fixture, earns {@link Classification#EXECUTABLE_UNIT}.</p>
 */
public class OperatorMicroGraphRunner {

    /** Operator testability classification used by inventory and release policy. */
    public enum Classification {
        EXECUTABLE_UNIT,
        OPAQUE_RUNTIME
    }

    private static final String NODE_ID = "subject";
    private final TestRunService testRunService;

    /** @param testRunService shared execution-control kernel */
    public OperatorMicroGraphRunner(TestRunService testRunService) {
        this.testRunService = Objects.requireNonNull(testRunService, "testRunService");
    }

    /**
     * Runs one operator through a single-node graph with production BLOGE input assembly.
     *
     * @param request frozen binding, formal input, fixtures, and provenance
     * @return classification and unified test execution result
     */
    public Result execute(Request request) {
        Objects.requireNonNull(request, "request");
        String bindingFingerprint = request.runtimeBindingFingerprint().isBlank()
                ? ProtocolFingerprint.ofText(request.operatorRef() + "|"
                + request.operator().getClass().getName())
                : request.runtimeBindingFingerprint();
        Graph graph = graph(request.operatorRef(), request.operator());
        FixtureBundle bundle = request.fixtureBundle() == null
                ? defaultBundle(request.operator(), bindingFingerprint)
                : request.fixtureBundle();
        Assessment assessment = assess(request.operator(), bundle, bindingFingerprint);
        TestExecutionResult execution = testRunService.execute(new TestExecutionRequest(
                graph,
                new GraphContext(Map.of("operatorInput", request.input())),
                bundle,
                request.authorizedPurpose(),
                bindingFingerprint,
                request.fixtureSource(),
                Map.of("targetKind", "OPERATOR_BINDING", "operatorRef", request.operatorRef(),
                        "classification", assessment.classification().name())));
        return new Result(assessment.classification(), bindingFingerprint,
                assessment.reasons(), execution);
    }

    /** Returns an inventory assessment without executing the binding. */
    public Assessment assess(Operator<?, ?> operator, FixtureBundle bundle,
                             String runtimeBindingFingerprint) {
        List<FixtureRule> rules = bundle == null ? List.of() : bundle.rules();
        boolean transportFixture = rules.stream().anyMatch(rule ->
                        rule.behavior().boundary() == FixtureRule.DoubleBoundary.TRANSPORT
                        && rule.behavior().kind() == FixtureRule.BehaviorKind.RETURN
                        && rule.behavior().statusCode() != null
                        && rule.behavior().value() == null);
        if (operator instanceof HttpResourceOperator) {
            return transportFixture
                    ? new Assessment(Classification.EXECUTABLE_UNIT,
                    List.of("HTTP transport is controlled while descriptor mapping and response logic execute."))
                    : new Assessment(Classification.OPAQUE_RUNTIME,
                    List.of("HttpResourceOperator requires a TRANSPORT raw-response fixture."));
        }
        if (operator.sideEffectType() == SideEffectType.READ_ONLY) {
            return new Assessment(Classification.EXECUTABLE_UNIT,
                    List.of("Binding declares READ_ONLY and executes as the real micro-graph subject."));
        }
        return new Assessment(Classification.OPAQUE_RUNTIME, List.of(
                "Binding declares " + operator.sideEffectType()
                        + " but no composability port proves its effects are isolated."));
    }

    private static Graph graph(String operatorRef, Operator<?, ?> operator) {
        Graph built = new GraphBuilder("operator-test:" + normalized(operatorRef))
                .node(NODE_ID, operator)
                .input((results, context) -> context.get("operatorInput"))
                .build();
        NodeSpec normalizedNode = built.nodes().get(NODE_ID).toBuilder()
                .operatorRef(normalized(operatorRef)).build();
        return new Graph(built.name(), Map.of(NODE_ID, normalizedNode), built.edges(),
                built.sourceNodes(), built.terminalNodes(), built.schemaValidationLevel(),
                built.embeddedOperators(), built.declaredInputSchema(), built.declaredOutputSchema(),
                built.sagaConfig(), built.definitionSource(), built.streamingOutputNodeId(),
                built.streamingInputs());
    }

    private static FixtureBundle defaultBundle(Operator<?, ?> operator, String targetFingerprint) {
        List<FixtureRule> rules = operator.sideEffectType() == SideEffectType.READ_ONLY
                ? List.of(new FixtureRule(FixtureRule.SCHEMA_VERSION, "subject-real",
                FixtureRule.Selector.node(NODE_ID), FixtureRule.Behavior.real(),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict()))
                : List.of();
        return new FixtureBundle(FixtureBundle.SCHEMA_VERSION, "operator-real", 1,
                targetFingerprint, "INTERNAL", null, null, rules, List.of(), Map.of());
    }

    private static String normalized(String operatorRef) {
        return operatorRef == null || operatorRef.isBlank() ? "operator" : operatorRef.trim();
    }

    /**
     * @param operatorRef stable operator catalog reference
     * @param operator exact runtime binding
     * @param runtimeBindingFingerprint frozen binding fingerprint; generated when blank
     * @param input formal operator input
     * @param fixtureBundle optional control bundle; null means one REAL rule
     * @param authorizedPurpose server-authorized purpose
     * @param fixtureSource fixture provenance
     */
    public record Request(String operatorRef, Operator<?, ?> operator,
                          String runtimeBindingFingerprint, Object input,
                          FixtureBundle fixtureBundle, String authorizedPurpose,
                          TestExecutionRequest.FixtureSource fixtureSource) {
        /** Normalizes request identifiers. */
        public Request {
            operatorRef = normalized(operatorRef);
            operator = Objects.requireNonNull(operator, "operator");
            runtimeBindingFingerprint = runtimeBindingFingerprint == null
                    ? "" : runtimeBindingFingerprint.trim();
            authorizedPurpose = authorizedPurpose == null ? "OPERATOR_UNIT_TEST" : authorizedPurpose.trim();
            fixtureSource = fixtureSource == null ? TestExecutionRequest.FixtureSource.INLINE : fixtureSource;
        }
    }

    /** @param classification affirmative testability classification @param reasons audit reasons */
    public record Assessment(Classification classification, List<String> reasons) {
        /** Creates immutable reasons. */
        public Assessment {
            classification = classification == null ? Classification.OPAQUE_RUNTIME : classification;
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }

    /** Unified micro-graph result. */
    public record Result(Classification classification, String runtimeBindingFingerprint,
                         List<String> reasons, TestExecutionResult execution) {
        /** Creates immutable result facts. */
        public Result {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
