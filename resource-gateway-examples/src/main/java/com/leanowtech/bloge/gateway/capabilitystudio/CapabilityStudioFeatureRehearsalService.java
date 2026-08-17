package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.operator.SideEffectType;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.operator.HttpResourceInput;
import com.leanowtech.bloge.gateway.operator.HttpResourceOperator;
import com.leanowtech.bloge.gateway.operator.HttpResourceOutput;
import com.leanowtech.bloge.gateway.operator.PayloadExtractor;
import com.leanowtech.bloge.gateway.operator.ResponseValidator;
import com.leanowtech.bloge.gateway.operator.UrlTemplateRenderer;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.domain.InvocationSite;
import com.leanowtech.bloge.gateway.testing.domain.TestRunEvidence;
import com.leanowtech.bloge.gateway.testing.evidence.GraphArtifactFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static com.leanowtech.bloge.gateway.capabilitystudio.CapabilityStudioDataLensProjection.PermissionMode;

/**
 * Non-production Feature Rehearsal application service.
 *
 * <p>The service is deliberately an adapter around BLOGE's existing test runtime: it builds the
 * demo graph, supplies a governed fixture bundle, calls {@link TestRunService}, and projects the
 * returned {@link TestRunEvidence}. It does not introduce a Capability Studio execution engine.</p>
 */
public final class CapabilityStudioFeatureRehearsalService {
    public static final String BINDING_MODE = "FIXTURE_CONTROLLED_NON_PRODUCTION";
    static final String TOOL_REF = "tool-cancellation-fee-dispute-handling";
    private static final String PURPOSE = "CAPABILITY_STUDIO_FEATURE_REHEARSAL";
    private static final String GRAPH_ID = "feature-cancellation-dispute-context";
    private static final Instant LOGICAL_CLOCK = Instant.parse("2026-08-18T00:00:00Z");
    private static final String ORDER_RESOURCE = "api-order-lookup";
    private static final String RESPONSIBILITY_RESOURCE = "api-cancellation-responsibility";
    private static final String POLICY_RESOURCE = "api-city-pricing-policy";
    private static final String COMPENSATION_RESOURCE = "api-compensation-history";

    private final CapabilityStudioGoldenDemoPack pack;
    private final ObjectMapper objectMapper;
    private final OperatorRegistry operatorRegistry;
    private final CapabilityStudioDataLensProjector dataLensProjector;

    public CapabilityStudioFeatureRehearsalService(
            CapabilityStudioGoldenDemoPack pack,
            ObjectMapper objectMapper,
            OperatorRegistry operatorRegistry) {
        this.pack = Objects.requireNonNull(pack, "pack");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper").findAndRegisterModules();
        this.operatorRegistry = Objects.requireNonNull(operatorRegistry, "operatorRegistry");
        this.dataLensProjector = new CapabilityStudioDataLensProjector(this.objectMapper);
    }

    /** Executes one canonical case with an explicit Data Lens permission. */
    public CapabilityStudioFeatureRehearsalProjection rehearse(
            String caseId, PermissionMode permissionMode) {
        CapabilityStudioGoldenDemoPack.TestScenario scenario = scenario(caseId);
        PermissionMode mode = Objects.requireNonNull(permissionMode, "permissionMode");
        FailFastHttpTransport transport = new FailFastHttpTransport();
        GraphAssembly assembly = graph(transport);
        Graph graph = assembly.graph();
        String graphFingerprint = GraphArtifactFingerprint.of(objectMapper, graph);
        FixtureBundle fixture = fixture(scenario, graphFingerprint, "/root");
        TestExecutionResult result = new TestRunService(operatorRegistry, objectMapper, null)
                .execute(new TestExecutionRequest(
                graph,
                new GraphContext(Map.of("orderId", "DEMO-ORDER-20260818-001", "caseId", scenario.id())),
                fixture,
                PURPOSE,
                graphFingerprint,
                TestExecutionRequest.FixtureSource.STORED,
                Map.of(
                        "scenarioId", scenario.id(),
                        "graphId", GRAPH_ID,
                        "bindingMode", BINDING_MODE),
                false,
                ResolvedReplayPayloads.empty()));
        int realExternalCallCount = transport.calls().get();
        if (realExternalCallCount != 0) {
            throw new IllegalStateException("REAL_EXTERNAL_CALL_FORBIDDEN");
        }
        if (result.evidence() == null) {
            throw new IllegalStateException("Feature Rehearsal did not produce TestRunEvidence");
        }
        if (result.evidence().status() == TestRunEvidence.Status.CONTROL_PLAN_REJECTED
                || result.evidence().status() == TestRunEvidence.Status.FIXTURE_UNMATCHED) {
            throw new IllegalStateException("Feature Rehearsal fixture control failed: "
                    + String.join("; ", result.evidence().diagnostics()));
        }
        return new CapabilityStudioFeatureRehearsalProjection(
                CapabilityStudioFeatureRehearsalProjection.SCHEMA_VERSION,
                new CapabilityStudioFeatureRehearsalProjection.Scenario(
                        scenario.id(), scenario.name(), scenario.expectedResult()),
                new CapabilityStudioFeatureRehearsalProjection.Graph(GRAPH_ID, graphFingerprint),
                new CapabilityStudioFeatureRehearsalProjection.Run(
                        result.evidence().runId(),
                        result.evidence().status().name(),
                        result.evidence().semanticResultFingerprint(),
                        realExternalCallCount,
                        BINDING_MODE),
                dataLensProjector.project(result.evidence(), mode));
    }

    /** Executes the same real graph with payload visibility for the development-only Oracle. */
    CapabilityStudioFeatureRehearsalProjection rehearseForOracle(String caseId) {
        return rehearse(caseId, PermissionMode.PAYLOAD_VISIBLE);
    }

    /** Internal-only graph facts for development evidence; never part of the v1 wire projection. */
    List<OperatorFootprint> operatorFootprints() {
        return graph(new FailFastHttpTransport()).operatorFootprints();
    }

    /** Returns the exact nested Tool binding used by the governed-compilation integration spike. */
    RuntimeAsset runtimeAsset() {
        FailFastHttpTransport transport = new FailFastHttpTransport();
        Graph graph = graph(transport).graph();
        String graphFingerprint = GraphArtifactFingerprint.of(objectMapper, graph);
        return new RuntimeAsset(
                new CapabilityStudioFeatureToolOperator(graph, operatorRegistry, graphFingerprint),
                graphFingerprint,
                transport.calls());
    }

    /** Builds a Tool-target fixture over the same canonical material used by Feature rehearsal. */
    FixtureBundle toolFixture(String caseId, String targetFingerprint) {
        return fixture(
                scenario(caseId),
                targetFingerprint,
                "/root/subject/" + GRAPH_ID);
    }

    private CapabilityStudioGoldenDemoPack.TestScenario scenario(String caseId) {
        String normalized = caseId == null ? "" : caseId.trim();
        return pack.scenarios().stream()
                .filter(candidate -> candidate.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownScenarioException(normalized));
    }

    private GraphAssembly graph(FailFastHttpTransport transport) {
        HttpResourceOperator resourceOperator = resourceOperator(transport);
        Operator<Map<String, Object>, Map<String, Object>> aggregate = new Operator<>() {
            @Override
            public Map<String, Object> execute(
                    Map<String, Object> input, OperatorContext context) {
                Map<String, Object> output = new LinkedHashMap<>();
                output.put("order", payloadOf(input.get("order")));
                output.put("responsibility", payloadOf(input.get("responsibility")));
                output.put("policy", payloadOf(input.get("policy")));
                output.put("compensationHistory", payloadOf(input.get("compensationHistory")));
                output.put("caseId", input.get("caseId"));
                return Map.copyOf(output);
            }

            @Override
            public SideEffectType sideEffectType() {
                return SideEffectType.READ_ONLY;
            }
        };
        Operator<Map<String, Object>, Map<String, Object>> decision = new Operator<>() {
            @Override
            public Map<String, Object> execute(
                    Map<String, Object> input, OperatorContext context) {
                Map<?, ?> policy = asMap(input.get("policy"));
                Map<?, ?> responsibility = asMap(input.get("responsibility"));
                Map<?, ?> compensationHistory = asMap(input.get("compensationHistory"));
                boolean policyMissing = policy.isEmpty();
                String owner = text(responsibility.get("owner"));
                boolean historyTimedOut = "TIMEOUT".equals(compensationHistory.get("availability"));
                boolean historyMissing = compensationHistory.isEmpty()
                        || Boolean.FALSE.equals(compensationHistory.get("hasHistory"))
                        || (compensationHistory.get("records") instanceof List<?> records
                        && records.isEmpty());

                Map<String, Object> output = new LinkedHashMap<>();
                if (historyTimedOut) {
                    output.put("action", "MANUAL_REVIEW");
                    output.put("reasonCode", "COMPENSATION_HISTORY_TIMEOUT");
                    output.put("responsibilityReason", "DEPENDENCY_NOT_AVAILABLE");
                } else if (policyMissing) {
                    output.put("action", "MANUAL_REVIEW");
                    output.put("reasonCode", "CITY_POLICY_MISSING");
                    output.put("responsibilityReason", "POLICY_NOT_AVAILABLE");
                } else if ("RIDER".equals(owner)) {
                    output.put("action", "WAIVE_CANCELLATION_FEE");
                    output.put("reasonCode", "RIDER_NOT_AT_FAULT");
                    output.put("responsibilityReason", "RIDER_NOT_RESPONSIBLE");
                } else if ("DRIVER".equals(owner)) {
                    output.put("action", "APPLY_DRIVER_RESPONSIBILITY_RULE");
                    output.put("reasonCode", text(responsibility.get("reasonCode")));
                    output.put("responsibilityReason", "DRIVER_RESPONSIBLE");
                } else {
                    output.put("action", "AUTO_QUOTE");
                    output.put("reasonCode", "CANCELLATION_CONTEXT_READY");
                    output.put("responsibilityReason", "PLATFORM_POLICY_CONTEXT");
                }
                output.put("informationGap", historyTimedOut
                        ? "COMPENSATION_HISTORY_TIMEOUT"
                        : historyMissing ? "COMPENSATION_HISTORY_EMPTY" : "NONE");
                output.put("policyVersion", text(policy.get("version")));
                output.put("caseId", input.get("caseId"));
                return Map.copyOf(output);
            }

            @Override
            public SideEffectType sideEffectType() {
                return SideEffectType.READ_ONLY;
            }
        };
        Graph built = new GraphBuilder(GRAPH_ID)
                .node("orderLookup", resourceOperator)
                .input((results, context) -> resourceInput(ORDER_RESOURCE, context))
                .node("responsibilityLookup", resourceOperator)
                .input((results, context) -> resourceInput(RESPONSIBILITY_RESOURCE, context))
                .node("cityPolicyLookup", resourceOperator)
                .input((results, context) -> resourceInput(POLICY_RESOURCE, context))
                .node("compensationHistoryLookup", resourceOperator)
                .input((results, context) -> resourceInput(COMPENSATION_RESOURCE, context))
                .fallback(() -> new HttpResourceOutput(
                        COMPENSATION_RESOURCE,
                        504,
                        Map.of(
                                "availability", "TIMEOUT",
                                "errorCode", "COMPENSATION_HISTORY_TIMEOUT"),
                        "",
                        Duration.ZERO,
                        false))
                .node("aggregateCancellationContext", aggregate)
                .dependsOn("orderLookup")
                .dependsOn("responsibilityLookup")
                .dependsOn("cityPolicyLookup")
                .dependsOn("compensationHistoryLookup")
                .input((results, context) -> Map.of(
                        "order", results.get("orderLookup", Object.class),
                        "responsibility", results.get("responsibilityLookup", Object.class),
                        "policy", results.get("cityPolicyLookup", Object.class),
                        "compensationHistory", results.get("compensationHistoryLookup", Object.class),
                        "caseId", context.get("caseId", String.class)))
                .node("cancellationDecision", decision)
                .dependsOn("aggregateCancellationContext")
                .input((results, context) -> results.get("aggregateCancellationContext", Object.class))
                .build();
        Graph graph = withOperatorRefs(built);
        List<OperatorFootprint> footprints =
                graph.nodes().values().stream()
                        .map(node -> new OperatorFootprint(
                                node.id(), node.operatorRef(), sideEffect(node.id(), resourceOperator,
                                aggregate, decision)))
                        .toList();
        return new GraphAssembly(graph, footprints);
    }

    private FixtureBundle fixture(
            CapabilityStudioGoldenDemoPack.TestScenario scenario,
            String targetFingerprint,
            String graphPath) {
        String specialResource = specialResource(scenario);
        String specialBehavior = specialBehavior(scenario);
        List<FixtureRule> rules = List.of(
                resourceRule(graphPath, "order", "orderLookup", ORDER_RESOURCE,
                        specialResource, specialBehavior),
                resourceRule(graphPath, "responsibility", "responsibilityLookup", RESPONSIBILITY_RESOURCE,
                        specialResource, specialBehavior),
                resourceRule(graphPath, "policy", "cityPolicyLookup", POLICY_RESOURCE, specialResource,
                        specialBehavior),
                resourceRule(graphPath, "compensation", "compensationHistoryLookup", COMPENSATION_RESOURCE,
                        specialResource, specialBehavior),
                observedComputationRule(graphPath, "aggregate", "aggregateCancellationContext",
                        "capabilityStudio.aggregate"),
                observedComputationRule(graphPath, "decision", "cancellationDecision",
                        "capabilityStudio.decision"));
        return new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION,
                "capability-studio-feature-rehearsal-" + scenario.id(),
                1,
                targetFingerprint,
                "INTERNAL",
                LOGICAL_CLOCK,
                null,
                rules,
                List.of(),
                Map.of(
                        "schemaVersion", "resource-gateway.capability-studio.feature-fixture.v1",
                        "scenarioId", scenario.id(),
                        "bindingMode", BINDING_MODE,
                        "fallbackToReal", false,
                        "allowedResourceRefs", List.of(
                                ORDER_RESOURCE, RESPONSIBILITY_RESOURCE, POLICY_RESOURCE,
                                COMPENSATION_RESOURCE)));
    }

    private FixtureRule resourceRule(
            String graphPath, String id, String nodeId, String resource, String specialResource,
            String specialBehavior) {
        FixtureRule.Behavior behavior = resource.equals(specialResource)
                ? specialBehavior(specialBehavior, resource)
                : FixtureRule.Behavior.returning(payload(resource));
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "feature-rehearsal-" + id,
                new FixtureRule.Selector(
                        graphPath, nodeId, "httpResource", resource, "", List.of(), List.of(),
                        InvocationSite.InvocationKind.RESOURCE,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                behavior,
                FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule observedComputationRule(
            String graphPath, String id, String nodeId, String operatorRef) {
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "feature-rehearsal-" + id,
                new FixtureRule.Selector(
                        graphPath, nodeId, operatorRef, "", "", List.of(), List.of(),
                        InvocationSite.InvocationKind.PRIMARY,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.spy(),
                FixtureRule.Consumption.optionalOnce(),
                FixtureRule.SchemaCheck.strict());
    }

    private static String specialResource(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        return switch (scenario.id()) {
            case "case-rider-not-responsible", "case-driver-responsible" -> RESPONSIBILITY_RESOURCE;
            case "case-city-policy-missing", "case-policy-revision-regression" -> POLICY_RESOURCE;
            case "case-compensation-history-empty", "case-compensation-history-timeout" ->
                    COMPENSATION_RESOURCE;
            default -> "";
        };
    }

    private static String specialBehavior(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        return switch (scenario.id()) {
            case "case-rider-not-responsible" -> "RETURN_RIDER";
            case "case-driver-responsible" -> "RETURN_DRIVER";
            case "case-city-policy-missing", "case-compensation-history-empty" -> "RETURN_EMPTY";
            case "case-compensation-history-timeout" -> "TIMEOUT";
            case "case-policy-revision-regression" -> "RETURN_VERSIONED";
            default -> "RETURN";
        };
    }

    private static FixtureRule.Behavior specialBehavior(String behavior, String resource) {
        return switch (behavior) {
            case "TIMEOUT" -> FixtureRule.Behavior.timeout(
                    Duration.ofMillis(10), "COMPENSATION_HISTORY_TIMEOUT", "历史补偿查询超时。");
            case "RETURN_EMPTY" -> FixtureRule.Behavior.returning(Map.of());
            case "RETURN_RIDER" -> FixtureRule.Behavior.returning(Map.of(
                    "owner", "RIDER",
                    "reasonCode", "RIDER_NOT_AT_FAULT",
                    "responsibilityReason", "RIDER_NOT_RESPONSIBLE"));
            case "RETURN_DRIVER" -> FixtureRule.Behavior.returning(Map.of(
                    "owner", "DRIVER",
                    "reasonCode", "DRIVER_LATE",
                    "responsibilityReason", "DRIVER_RESPONSIBLE"));
            case "RETURN_VERSIONED" -> FixtureRule.Behavior.returning(Map.of(
                    "version", "SZ-CANCEL-2026.08-R2",
                    "feeRule", "CANCEL_FEE_AFTER_5_MIN",
                    "effectiveFrom", "2026-08-01T00:00:00Z"));
            default -> FixtureRule.Behavior.returning(payload(resource));
        };
    }

    private static SideEffectType sideEffect(
            String nodeId,
            HttpResourceOperator resourceOperator,
            Operator<Map<String, Object>, Map<String, Object>> aggregate,
            Operator<Map<String, Object>, Map<String, Object>> decision) {
        return switch (nodeId) {
            case "orderLookup", "responsibilityLookup", "cityPolicyLookup", "compensationHistoryLookup" ->
                    resourceOperator.sideEffectType();
            case "aggregateCancellationContext" -> aggregate.sideEffectType();
            case "cancellationDecision" -> decision.sideEffectType();
            default -> SideEffectType.MIXED;
        };
    }

    private static Map<?, ?> asMap(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static Object payloadOf(Object value) {
        return value instanceof HttpResourceOutput output ? output.payload() : value;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> payload(String resource) {
        return switch (resource) {
            case ORDER_RESOURCE -> Map.of(
                    "orderId", "DEMO-ORDER-20260818-001",
                    "cityCode", "SZ",
                    "serviceType", "ECONOMY",
                    "status", "CANCELLED");
            case RESPONSIBILITY_RESOURCE -> Map.of(
                    "owner", "PLATFORM",
                    "reasonCode", "DRIVER_LATE");
            case POLICY_RESOURCE -> Map.of(
                    "version", "SZ-CANCEL-2026.08",
                    "feeRule", "CANCEL_FEE_AFTER_5_MIN",
                    "effectiveFrom", "2026-08-01T00:00:00Z");
            case COMPENSATION_RESOURCE -> Map.of(
                    "hasHistory", true,
                    "records", List.of(Map.of("recordType", "CANCELLATION_REVIEW")));
            default -> Map.of("resource", resource);
        };
    }

    private static HttpResourceInput resourceInput(String resource, GraphContext context) {
        return new HttpResourceInput(resource, Map.of(
                "orderId", context.get("orderId", String.class),
                "caseId", context.get("caseId", String.class)));
    }

    private HttpResourceOperator resourceOperator(FailFastHttpTransport transport) {
        BlgeExpressionEvaluator evaluator = new BlgeExpressionEvaluator();
        return new HttpResourceOperator(
                transport,
                new DemoResourceRegistry(),
                evaluator,
                new UrlTemplateRenderer(),
                new PayloadExtractor(objectMapper),
                new ResponseValidator(evaluator));
    }

    private static Graph withOperatorRefs(Graph graph) {
        Map<String, com.leanowtech.bloge.core.model.NodeSpec> nodes = new LinkedHashMap<>();
        graph.nodes().forEach((id, node) -> nodes.put(id, node.toBuilder()
                .operatorRef(operatorRef(id))
                .build()));
        return new Graph(
                graph.name(), nodes, graph.edges(), graph.sourceNodes(), graph.terminalNodes(),
                graph.schemaValidationLevel(), graph.embeddedOperators(), graph.declaredInputSchema(),
                graph.declaredOutputSchema(), graph.sagaConfig(), graph.definitionSource(),
                graph.streamingOutputNodeId(), graph.streamingInputs());
    }

    private static String operatorRef(String nodeId) {
        return switch (nodeId) {
            case "orderLookup", "responsibilityLookup", "cityPolicyLookup", "compensationHistoryLookup" ->
                    "httpResource";
            case "aggregateCancellationContext" -> "capabilityStudio.aggregate";
            case "cancellationDecision" -> "capabilityStudio.decision";
            default -> nodeId;
        };
    }

    private record GraphAssembly(
            Graph graph,
            List<OperatorFootprint> operatorFootprints) {
    }

    record OperatorFootprint(String nodeId, String operatorRef, SideEffectType sideEffectType) {
    }

    /** Internal runtime closure; fixture material and transport counters never enter public DTOs. */
    record RuntimeAsset(
            CapabilityStudioFeatureToolOperator operator,
            String graphFingerprint,
            AtomicInteger realExternalCalls) {
    }

    /** Raised before execution when a caller asks for a case outside the frozen canonical pack. */
    public static final class UnknownScenarioException extends RuntimeException {
        private final String caseId;

        public UnknownScenarioException(String caseId) {
            super("Unknown Capability Studio Feature Rehearsal case");
            this.caseId = caseId;
        }

        public String caseId() {
            return caseId;
        }
    }

    /** Transport sentinel: a real delegate leak is an execution failure, never a warning. */
    private static final class FailFastHttpTransport extends HttpRequestOperator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public HttpResponseOutput execute(HttpRequestInput input, OperatorContext context) {
            calls.incrementAndGet();
            throw new AssertionError("REAL_EXTERNAL_CALL_FORBIDDEN");
        }

        private AtomicInteger calls() {
            return calls;
        }
    }

    private static final class DemoResourceRegistry implements ResourceRegistry {
        private final Map<String, ResourceDescriptor> resources = Map.of(
                ORDER_RESOURCE, descriptor(ORDER_RESOURCE),
                RESPONSIBILITY_RESOURCE, descriptor(RESPONSIBILITY_RESOURCE),
                POLICY_RESOURCE, descriptor(POLICY_RESOURCE),
                COMPENSATION_RESOURCE, descriptor(COMPENSATION_RESOURCE));

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            ResourceDescriptor descriptor = resources.get(resourceId);
            if (descriptor == null) {
                throw new IllegalArgumentException("Unknown demo resource: " + resourceId);
            }
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return resources.containsKey(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return resources.values();
        }

        private static ResourceDescriptor descriptor(String resourceId) {
            return new ResourceDescriptor(
                    resourceId,
                    "https://capability-studio.invalid/fixture/" + resourceId,
                    "GET",
                    Map.of(),
                    null,
                    Duration.ofSeconds(1),
                    ParameterMapping.empty(),
                    new ResponseProtocol.HttpStatus(),
                    null);
        }
    }
}
