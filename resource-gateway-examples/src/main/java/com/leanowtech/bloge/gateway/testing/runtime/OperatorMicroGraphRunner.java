package com.leanowtech.bloge.gateway.testing.runtime;

import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.model.NodeSpec;
import com.leanowtech.bloge.core.operator.Idempotency;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.OperatorResult;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.operator.SuspendableOperator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate.AdmissionGuard;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thin operator-test entry that executes the exact runtime binding as a BLOGE micro graph.
 *
 * <p>The micro graph intentionally reuses {@link TestRunService}; it is not a second execution
 * mechanism. Normal execution uses one subject node; durable execution adds only a server-owned
 * pre-execution signal gate. Classification is affirmative: only a read-only operator, or an HTTP
 * resource tested with a transport-boundary fixture, earns
 * {@link Classification#EXECUTABLE_UNIT}.</p>
 */
public class OperatorMicroGraphRunner {

    /** Operator testability classification used by inventory and release policy. */
    public enum Classification {
        EXECUTABLE_UNIT,
        OPAQUE_RUNTIME
    }

    private static final String NODE_ID = "subject";
    /** Server-owned signal gate used only by durable operator-test creation and recovery. */
    public static final String DURABLE_START_NODE_ID = "durable-operator-start";
    private static final SuspendableOperator<Void, Object> DURABLE_START_GATE =
            new DurableOperatorStartGate();
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
        return execute(request, compiled -> new AdmissionGuard() {
            @Override
            public void checkpoint() {
                // Compatibility path for focused runner tests.
            }

            @Override
            public void close() {
                // No distributed permit exists.
            }
        });
    }

    /**
     * Runs one operator after compiled-plan admission and before terminal evidence publication.
     *
     * @param request frozen binding, formal input, fixtures, and provenance
     * @param admissionFactory permit factory over the one-node compiled inventory
     * @return classification and unified test execution result
     */
    public Result execute(
            Request request,
            TestRunService.AdmissionFactory admissionFactory) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(admissionFactory, "admissionFactory");
        String bindingFingerprint = request.runtimeBindingFingerprint().isBlank()
                ? ProtocolFingerprint.ofText(request.operatorRef() + "|"
                + request.operator().getClass().getName())
                : request.runtimeBindingFingerprint();
        Graph graph = microGraph(request.operatorRef(), request.operator());
        FixtureBundle bundle = request.fixtureBundle() == null
                ? defaultBundle(request.operator(), bindingFingerprint)
                : request.fixtureBundle();
        Assessment assessment = assess(request.operator(), bundle, bindingFingerprint);
        Map<String, Object> metadata = new LinkedHashMap<>(request.metadata());
        metadata.put("targetKind", "OPERATOR");
        metadata.put("operatorRef", request.operatorRef());
        metadata.put("testabilityClass", assessment.classification().name());
        metadata.put("targetCertificationEligible",
                request.certificationEligible()
                        && assessment.classification() == Classification.EXECUTABLE_UNIT);
        Map<String, Object> graphContext = new LinkedHashMap<>();
        if (request.input() != null) {
            graphContext.put("operatorInput", request.input());
        }
        TestExecutionResult execution = testRunService.execute(new TestExecutionRequest(
                graph,
                new GraphContext(graphContext),
                bundle,
                request.authorizedPurpose(),
                bindingFingerprint,
                request.fixtureSource(),
                Map.copyOf(metadata),
                request.certificationEligible()
                        && assessment.classification() == Classification.EXECUTABLE_UNIT,
                request.replayPayloads(), request.testSecrets()), admissionFactory);
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

    /**
     * Reconstructs the canonical one-node graph used by both fresh operator tests and recovery.
     *
     * @param operatorRef stable registry reference
     * @param operator exact synchronous runtime binding
     * @return canonical operator micro graph
     */
    public static Graph microGraph(String operatorRef, Operator<?, ?> operator) {
        Objects.requireNonNull(operator, "operator");
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

    /**
     * Builds the canonical recoverable operator graph with a server-owned pre-execution signal gate.
     *
     * <p>Fresh durable creation executes only the gate and therefore cannot invoke the business
     * operator before its checkpoint commits. A later authenticated signal completes the gate; the
     * exact frozen subject then reads its formal input from the persisted graph context. The gate is
     * read-only, idempotent, has no caller-controlled configuration, and is reconstructed identically
     * during recovery authorization.</p>
     *
     * @param operatorRef stable registry reference
     * @param operator exact synchronous runtime binding
     * @return canonical start-gated operator micro graph
     */
    public static Graph durableMicroGraph(String operatorRef, Operator<?, ?> operator) {
        Objects.requireNonNull(operator, "operator");
        Graph built = new GraphBuilder("durable-operator-test:" + normalized(operatorRef))
                .suspendNode(DURABLE_START_NODE_ID, DURABLE_START_GATE)
                .node(NODE_ID, operator).dependsOn(DURABLE_START_NODE_ID)
                .input((results, context) -> context.get("operatorInput"))
                .build();
        NodeSpec normalizedNode = built.nodes().get(NODE_ID).toBuilder()
                .operatorRef(normalized(operatorRef)).build();
        Map<String, NodeSpec> nodes = new LinkedHashMap<>(built.nodes());
        nodes.put(NODE_ID, normalizedNode);
        return new Graph(built.name(), nodes, built.edges(), built.sourceNodes(),
                built.terminalNodes(), built.schemaValidationLevel(), built.embeddedOperators(),
                built.declaredInputSchema(), built.declaredOutputSchema(), built.sagaConfig(),
                built.definitionSource(), built.streamingOutputNodeId(), built.streamingInputs());
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

    private static final class DurableOperatorStartGate
            implements SuspendableOperator<Void, Object> {
        @Override
        public OperatorResult<Object> execute(Void input, OperatorContext context) {
            return OperatorResult.suspend("durable-operator-start");
        }

        @Override
        public Idempotency idempotency() {
            return Idempotency.IDEMPOTENT;
        }

        @Override
        public SideEffectType sideEffectType() {
            return SideEffectType.READ_ONLY;
        }
    }

    /**
     * @param operatorRef stable operator catalog reference
     * @param operator exact runtime binding
     * @param runtimeBindingFingerprint frozen binding fingerprint; generated when blank
     * @param input formal operator input
     * @param fixtureBundle optional control bundle; null means one REAL rule
     * @param authorizedPurpose server-authorized purpose
     * @param fixtureSource fixture provenance
     * @param certificationEligible binding-level certification readiness
     * @param metadata bounded caller provenance
     * @param replayPayloads run-scoped replay values resolved by the authorized API boundary
     * @param testSecrets run-scoped secret values resolved by the external authority
     */
    public record Request(String operatorRef, Operator<?, ?> operator,
                          String runtimeBindingFingerprint, Object input,
                          FixtureBundle fixtureBundle, String authorizedPurpose,
                          TestExecutionRequest.FixtureSource fixtureSource,
                          boolean certificationEligible,
                          Map<String, Object> metadata,
                          ResolvedReplayPayloads replayPayloads,
                          ResolvedTestSecrets testSecrets) {
        /** Normalizes request identifiers. */
        public Request {
            operatorRef = normalized(operatorRef);
            operator = Objects.requireNonNull(operator, "operator");
            runtimeBindingFingerprint = runtimeBindingFingerprint == null
                    ? "" : runtimeBindingFingerprint.trim();
            authorizedPurpose = authorizedPurpose == null ? "OPERATOR_UNIT_TEST" : authorizedPurpose.trim();
            fixtureSource = fixtureSource == null ? TestExecutionRequest.FixtureSource.INLINE : fixtureSource;
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
            replayPayloads = replayPayloads == null ? ResolvedReplayPayloads.empty() : replayPayloads;
            testSecrets = testSecrets == null ? ResolvedTestSecrets.empty() : testSecrets;
        }

        /** Backward-compatible request without governed replay dependencies. */
        public Request(String operatorRef, Operator<?, ?> operator,
                       String runtimeBindingFingerprint, Object input,
                       FixtureBundle fixtureBundle, String authorizedPurpose,
                       TestExecutionRequest.FixtureSource fixtureSource,
                       boolean certificationEligible,
                       Map<String, Object> metadata) {
            this(operatorRef, operator, runtimeBindingFingerprint, input, fixtureBundle,
                    authorizedPurpose, fixtureSource, certificationEligible, metadata,
                    ResolvedReplayPayloads.empty(), ResolvedTestSecrets.empty());
        }

        /** Backward-compatible request without caller provenance metadata. */
        public Request(String operatorRef, Operator<?, ?> operator,
                       String runtimeBindingFingerprint, Object input,
                       FixtureBundle fixtureBundle, String authorizedPurpose,
                       TestExecutionRequest.FixtureSource fixtureSource) {
            this(operatorRef, operator, runtimeBindingFingerprint, input, fixtureBundle,
                    authorizedPurpose, fixtureSource, true, Map.of(), ResolvedReplayPayloads.empty(),
                    ResolvedTestSecrets.empty());
        }

        /** Backward-compatible request that assumes the caller already froze certification readiness. */
        public Request(String operatorRef, Operator<?, ?> operator,
                       String runtimeBindingFingerprint, Object input,
                       FixtureBundle fixtureBundle, String authorizedPurpose,
                       TestExecutionRequest.FixtureSource fixtureSource,
                       Map<String, Object> metadata) {
            this(operatorRef, operator, runtimeBindingFingerprint, input, fixtureBundle,
                    authorizedPurpose, fixtureSource, true, metadata, ResolvedReplayPayloads.empty(),
                    ResolvedTestSecrets.empty());
        }

        /** Backward-compatible request carrying replay values but no external test secrets. */
        public Request(String operatorRef, Operator<?, ?> operator,
                       String runtimeBindingFingerprint, Object input,
                       FixtureBundle fixtureBundle, String authorizedPurpose,
                       TestExecutionRequest.FixtureSource fixtureSource,
                       boolean certificationEligible,
                       Map<String, Object> metadata,
                       ResolvedReplayPayloads replayPayloads) {
            this(operatorRef, operator, runtimeBindingFingerprint, input, fixtureBundle,
                    authorizedPurpose, fixtureSource, certificationEligible, metadata,
                    replayPayloads, ResolvedTestSecrets.empty());
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
