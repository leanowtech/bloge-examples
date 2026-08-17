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
        Graph graph = graph(transport);
        String graphFingerprint = GraphArtifactFingerprint.of(objectMapper, graph);
        FixtureBundle fixture = fixture(scenario, graphFingerprint);
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

    private CapabilityStudioGoldenDemoPack.TestScenario scenario(String caseId) {
        String normalized = caseId == null ? "" : caseId.trim();
        return pack.scenarios().stream()
                .filter(candidate -> candidate.id().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new UnknownScenarioException(normalized));
    }

    private Graph graph(FailFastHttpTransport transport) {
        HttpResourceOperator resourceOperator = resourceOperator(transport);
        Operator<Map<String, Object>, Map<String, Object>> aggregate = new Operator<>() {
            @Override
            public Map<String, Object> execute(
                    Map<String, Object> input, OperatorContext context) {
                return Map.of(
                        "order", input.get("order"),
                        "responsibility", input.get("responsibility"),
                        "policy", input.get("policy"),
                        "compensationHistory", input.get("compensationHistory"),
                        "caseId", input.get("caseId"));
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
                Object policy = input.get("policy");
                boolean policyMissing = policy instanceof Map<?, ?> values && values.isEmpty();
                return Map.of(
                        "action", policyMissing ? "MANUAL_REVIEW" : "AUTO_QUOTE",
                        "reasonCode", policyMissing
                                ? "CITY_POLICY_MISSING" : "CANCELLATION_CONTEXT_READY",
                        "caseId", input.get("caseId"));
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
        return withOperatorRefs(built);
    }

    private FixtureBundle fixture(
            CapabilityStudioGoldenDemoPack.TestScenario scenario,
            String graphFingerprint) {
        String specialResource = specialResource(scenario);
        String specialBehavior = specialBehavior(scenario);
        List<FixtureRule> rules = List.of(
                resourceRule("order", "orderLookup", ORDER_RESOURCE, specialResource, specialBehavior),
                resourceRule("responsibility", "responsibilityLookup", RESPONSIBILITY_RESOURCE,
                        specialResource, specialBehavior),
                resourceRule("policy", "cityPolicyLookup", POLICY_RESOURCE, specialResource,
                        specialBehavior),
                resourceRule("compensation", "compensationHistoryLookup", COMPENSATION_RESOURCE,
                        specialResource, specialBehavior),
                observedComputationRule("aggregate", "aggregateCancellationContext",
                        "capabilityStudio.aggregate"),
                observedComputationRule("decision", "cancellationDecision",
                        "capabilityStudio.decision"));
        return new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION,
                "capability-studio-feature-rehearsal-" + scenario.id(),
                1,
                graphFingerprint,
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
            String id, String nodeId, String resource, String specialResource,
            String specialBehavior) {
        FixtureRule.Behavior behavior = resource.equals(specialResource)
                ? specialBehavior(specialBehavior, resource)
                : FixtureRule.Behavior.returning(payload(resource));
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "feature-rehearsal-" + id,
                new FixtureRule.Selector(
                        "/root", nodeId, "httpResource", resource, "", List.of(), List.of(),
                        InvocationSite.InvocationKind.RESOURCE,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                behavior,
                FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule observedComputationRule(
            String id, String nodeId, String operatorRef) {
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "feature-rehearsal-" + id,
                new FixtureRule.Selector(
                        "/root", nodeId, operatorRef, "", "", List.of(), List.of(),
                        InvocationSite.InvocationKind.PRIMARY,
                        List.of(), List.of(), "", FixtureRule.Match.none()),
                FixtureRule.Behavior.spy(),
                FixtureRule.Consumption.optionalOnce(),
                FixtureRule.SchemaCheck.strict());
    }

    private static String specialResource(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        return scenario.dependencyBehaviors().stream()
                .map(CapabilityStudioGoldenDemoPack.DependencyBehavior::dependencyRef)
                .map(CapabilityStudioGoldenDemoPack.ExactRef::id)
                .filter(id -> id.startsWith("api-"))
                .findFirst().orElse("");
    }

    private static String specialBehavior(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        return scenario.dependencyBehaviors().stream()
                .map(CapabilityStudioGoldenDemoPack.DependencyBehavior::behavior)
                .findFirst().orElse("RETURN");
    }

    private static FixtureRule.Behavior specialBehavior(String behavior, String resource) {
        return switch (behavior) {
            case "TIMEOUT" -> FixtureRule.Behavior.timeout(
                    Duration.ofMillis(10), "COMPENSATION_HISTORY_TIMEOUT", "历史补偿查询超时。");
            case "RETURN_EMPTY" -> FixtureRule.Behavior.returning(Map.of());
            case "RETURN_VERSIONED" -> FixtureRule.Behavior.returning(payload(resource + ":v2"));
            default -> FixtureRule.Behavior.returning(payload(resource));
        };
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
                    "hasHistory", false,
                    "records", List.of());
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
