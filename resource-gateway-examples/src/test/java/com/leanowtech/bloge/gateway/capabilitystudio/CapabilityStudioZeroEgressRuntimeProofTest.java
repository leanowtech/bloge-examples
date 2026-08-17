package com.leanowtech.bloge.gateway.capabilitystudio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.core.context.GraphContext;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.OperatorContext;
import com.leanowtech.bloge.core.spi.DefaultOperatorRegistry;
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
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionRequest;
import com.leanowtech.bloge.gateway.testing.runtime.TestExecutionResult;
import com.leanowtech.bloge.gateway.testing.runtime.TestRunService;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedReplayPayloads;
import com.leanowtech.bloge.operators.http.HttpRequestInput;
import com.leanowtech.bloge.operators.http.HttpRequestOperator;
import com.leanowtech.bloge.operators.http.HttpResponseOutput;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Narrow Stage 0 SPIKE-C proof: the canonical pack is materialized into governed fixtures and
 * executed by the existing TestRunService. The real HTTP binding is deliberately armed with a
 * fail-closed transport, so a real connector leak fails the test instead of being reported as a
 * fabricated zero count.
 *
 * <p>The packaged Capability Studio golden pack contains metadata references only. This test
 * therefore constructs a bounded, test-owned material closure from those exact case references;
 * it does not pretend that the demo JSON is itself an executable fixture registry.</p>
 */
class CapabilityStudioZeroEgressRuntimeProofTest {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final String RESOURCE_ID = "customer.lookup";
    private static final String PURPOSE = "CAPABILITY_STUDIO_SPIKE_C";
    private static final Instant LOGICAL_CLOCK = Instant.parse("2026-08-18T00:00:00Z");

    @Test
    void executesAllNineCanonicalCasesThroughTheRealTestRunPathWithNoExternalCall() {
        CapabilityStudioGoldenDemoPack pack = new CapabilityStudioGoldenDemoPackLoader().load(JSON);
        assertThat(pack.scenarios()).as("canonical pack must remain the nine-case baseline")
                .hasSize(9);

        RecordingFailingHttpTransport transport = new RecordingFailingHttpTransport();
        TestRunService service = new TestRunService(
                new DefaultOperatorRegistry(), JSON, null);
        String targetFingerprint = pack.toolCapabilities().getFirst().ref().fingerprint();
        Map<String, String> semanticFingerprints = new LinkedHashMap<>();

        for (CapabilityStudioGoldenDemoPack.TestScenario scenario : pack.scenarios()) {
            RuntimeCase runtimeCase = materialize(scenario);
            TestExecutionResult first = execute(service, runtimeCase, transport,
                    targetFingerprint, pack.packFingerprint());
            TestExecutionResult repeated = execute(service, runtimeCase, transport,
                    targetFingerprint, pack.packFingerprint());

            assertThat(first.evidence().status())
                    .as("canonical case %s should reach its expected terminal outcome", scenario.id())
                    .isEqualTo(runtimeCase.expectedStatus());
            assertThat(repeated.evidence().status()).isEqualTo(runtimeCase.expectedStatus());
            assertThat(first.evidence().fixtureConsumptions())
                    .as("canonical case %s must consume every governed rule", scenario.id())
                    .allSatisfy(consumption -> assertThat(consumption.status()).isEqualTo("SATISFIED"));
            assertThat(repeated.evidence().fixtureConsumptions())
                    .allSatisfy(consumption -> assertThat(consumption.status()).isEqualTo("SATISFIED"));
            assertThat(first.evidence().semanticResultFingerprint())
                    .as("semantic result must be replay-stable for %s", scenario.id())
                    .isEqualTo(repeated.evidence().semanticResultFingerprint());
            assertThat(first.plan().planFingerprint()).isEqualTo(repeated.plan().planFingerprint());
            semanticFingerprints.put(scenario.id(), first.evidence().semanticResultFingerprint());

            if (runtimeCase.sequence()) {
                assertThat(first.evidence().nodeTrace()).singleElement().satisfies(trace -> {
                    assertThat(trace.attempts())
                            .extracting(TestRunEvidence.AttemptTrace::status)
                            .containsExactly("TIMEOUT", "MOCKED");
                    assertThat(trace.attempts())
                            .extracting(TestRunEvidence.AttemptTrace::attempt)
                            .containsExactly(1, 2);
                });
            }
            if (runtimeCase.expectedStatus() == TestRunEvidence.Status.TIMED_OUT) {
                assertThat(first.evidence().nodeTrace()).singleElement()
                        .extracting(TestRunEvidence.NodeTrace::status)
                        .isEqualTo("TIMEOUT");
            }
            if (runtimeCase.mustNotCall()) {
                assertThat(first.evidence().nodeTrace()).singleElement()
                        .extracting(TestRunEvidence.NodeTrace::errorCode)
                        .isEqualTo("FORBIDDEN_WRITE");
            }

            // This assertion is evaluated after every execution, not only at the end of the loop.
            assertThat(transport.calls()).as("real connector leaked for %s", scenario.id())
                    .hasValue(0);
        }

        assertThat(semanticFingerprints).hasSize(9).doesNotContainValue("");
        assertThat(transport.calls()).hasValue(0);
    }

    @Test
    void executesAnExplicitErrorMaterialWithoutTouchingTheRealConnector() {
        RecordingFailingHttpTransport transport = new RecordingFailingHttpTransport();
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(), JSON, null);
        RuntimeCase error = new RuntimeCase(
                "synthetic-error-material",
                List.of(rule("error", resourceSelector(List.of()),
                        FixtureRule.Behavior.throwing(
                                "POLICY_MISSING", "BUSINESS", "Policy is unavailable."))),
                TestRunEvidence.Status.EXECUTION_FAILED,
                false,
                false);

        TestExecutionResult result = execute(service, error, transport,
                "sha256:" + "e".repeat(64), "synthetic-error-material");

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.EXECUTION_FAILED);
        assertThat(result.evidence().nodeTrace()).singleElement()
                .extracting(TestRunEvidence.NodeTrace::errorCode)
                .isEqualTo("POLICY_MISSING");
        assertThat(transport.calls()).hasValue(0);
    }

    @Test
    void rejectsFallbackToRealBeforeTheGraphStarts() {
        RecordingFailingHttpTransport transport = new RecordingFailingHttpTransport();
        TestRunService service = new TestRunService(new DefaultOperatorRegistry(), JSON, null);
        String targetFingerprint = "sha256:" + "f".repeat(64);
        FixtureRule fallback = new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                "forbidden-fallback",
                resourceSelector(List.of()),
                FixtureRule.Behavior.returning(Map.of("result", "never")),
                new FixtureRule.Consumption(true, 1, 1,
                        FixtureRule.ExhaustedAction.FALLBACK_TO_REAL,
                        FixtureRule.UnmatchedAction.FAIL),
                FixtureRule.SchemaCheck.strict());
        RuntimeCase runtimeCase = new RuntimeCase(
                "fallback-to-real-rejected",
                List.of(fallback),
                TestRunEvidence.Status.CONTROL_PLAN_REJECTED,
                false,
                false);

        TestExecutionResult result = execute(service, runtimeCase, transport,
                targetFingerprint, "fallback-to-real-rejected");

        assertThat(result.evidence().status()).isEqualTo(TestRunEvidence.Status.CONTROL_PLAN_REJECTED);
        assertThat(result.evidence().diagnostics()).anyMatch(value ->
                value.contains("fallback") || value.contains("FALLBACK"));
        assertThat(transport.calls()).hasValue(0);
    }

    @Test
    void realConnectorObserverRecordsAndFailsIfTheIsolationBoundaryLeaks() throws Exception {
        RecordingFailingHttpTransport transport = new RecordingFailingHttpTransport();
        HttpResourceOperator real = realResourceOperator(transport);

        assertThatThrownBy(() -> real.execute(
                new HttpResourceInput(RESOURCE_ID, Map.of("caseId", "probe")),
                new OperatorContext("dependency", "capability-studio", new GraphContext(), 0)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("REAL_EXTERNAL_CALL_FORBIDDEN");
        assertThat(transport.calls()).hasValue(1);
    }

    private TestExecutionResult execute(
            TestRunService service,
            RuntimeCase runtimeCase,
            RecordingFailingHttpTransport transport,
            String targetFingerprint,
            String packFingerprint) {
        HttpResourceOperator real = realResourceOperator(transport);
        Graph graph = graph(real, runtimeCase.sequence());
        FixtureBundle bundle = new FixtureBundle(
                FixtureBundle.SCHEMA_VERSION,
                "capability-studio-spike-c-" + runtimeCase.id(),
                1,
                targetFingerprint,
                "INTERNAL",
                runtimeCase.sequence() || runtimeCase.expectedStatus() == TestRunEvidence.Status.TIMED_OUT
                        ? LOGICAL_CLOCK : null,
                null,
                runtimeCase.rules(),
                List.of(),
                Map.of(
                        "source", "capability-studio-spike-c",
                        "canonicalPackFingerprint", packFingerprint,
                        "canonicalCaseId", runtimeCase.id(),
                        "materialAuthority", "test-owned-runtime-closure"));
        return service.execute(new TestExecutionRequest(
                graph,
                new GraphContext(Map.of("input", Map.of(
                        "resourceId", RESOURCE_ID,
                        "params", Map.of("caseId", runtimeCase.id())))),
                bundle,
                PURPOSE,
                targetFingerprint,
                TestExecutionRequest.FixtureSource.STORED,
                Map.of("canonicalCaseId", runtimeCase.id()),
                true,
                ResolvedReplayPayloads.empty()));
    }

    private RuntimeCase materialize(CapabilityStudioGoldenDemoPack.TestScenario scenario) {
        String behavior = scenario.dependencyBehaviors().getFirst().behavior();
        return switch (behavior) {
            case "RETURN", "RETURN_EMPTY", "RETURN_VERSIONED" -> new RuntimeCase(
                    scenario.id(),
                    List.of(rule("return-" + scenario.id(), resourceSelector(List.of()),
                            FixtureRule.Behavior.returning(Map.of("result", scenario.id())))),
                    TestRunEvidence.Status.PASSED, false, false);
            case "TIMEOUT" -> new RuntimeCase(
                    scenario.id(),
                    List.of(rule("timeout-" + scenario.id(), resourceSelector(List.of()),
                            FixtureRule.Behavior.timeout(Duration.ofMillis(10),
                                    "CASE_TIMEOUT", "Canonical dependency timed out."))),
                    TestRunEvidence.Status.TIMED_OUT, false, false);
            case "IDEMPOTENT" -> new RuntimeCase(
                    scenario.id(),
                    List.of(
                            rule("sequence-timeout-" + scenario.id(), resourceSelector(List.of(1)),
                                    FixtureRule.Behavior.timeout(Duration.ofMillis(10),
                                            "FIRST_ATTEMPT_TIMEOUT", "Retry is expected.")),
                            rule("sequence-return-" + scenario.id(), resourceSelector(List.of(2)),
                                    FixtureRule.Behavior.returning(Map.of("result", scenario.id())))),
                    TestRunEvidence.Status.PASSED, true, false);
            case "MUST_NOT_CALL_WRITE" -> new RuntimeCase(
                    scenario.id(),
                    List.of(rule("deny-" + scenario.id(), resourceSelector(List.of()),
                            FixtureRule.Behavior.deny("FORBIDDEN_WRITE", "Write effect is forbidden."))),
                    TestRunEvidence.Status.EXECUTION_FAILED, false, true);
            default -> throw new AssertionError(
                    "Unsupported canonical metadata behavior: " + behavior + " for " + scenario.id());
        };
    }

    private static FixtureRule rule(
            String id,
            FixtureRule.Selector selector,
            FixtureRule.Behavior behavior) {
        return new FixtureRule(
                FixtureRule.SCHEMA_VERSION,
                id,
                selector,
                behavior,
                FixtureRule.Consumption.once(),
                FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule.Selector resourceSelector(List<Integer> attempts) {
        return new FixtureRule.Selector(
                "/root", "dependency", "", RESOURCE_ID, "", List.of(), List.of(),
                InvocationSite.InvocationKind.RESOURCE,
                attempts, List.of(), "", FixtureRule.Match.none());
    }

    private static Graph graph(HttpResourceOperator real, boolean sequence) {
        GraphBuilder builder = new GraphBuilder(
                sequence ? "capability-studio-idempotent-sequence" : "capability-studio-canonical-case");
        Graph built = builder.node("dependency", real)
                .input((results, context) -> context.get("input"))
                .build();
        if (sequence) {
            built = new GraphBuilder("capability-studio-idempotent-sequence")
                    .node("dependency", real)
                    .input((results, context) -> context.get("input"))
                    .retry(1, Duration.ZERO)
                    .build();
        }
        return withOperatorRef(built, "httpResource");
    }

    private static Graph withOperatorRef(Graph graph, String operatorRef) {
        var node = graph.nodes().get("dependency").toBuilder()
                .operatorRef(operatorRef)
                .build();
        return new Graph(
                graph.name(),
                Map.of("dependency", node),
                graph.edges(),
                graph.sourceNodes(),
                graph.terminalNodes(),
                graph.schemaValidationLevel(),
                graph.embeddedOperators(),
                graph.declaredInputSchema(),
                graph.declaredOutputSchema(),
                graph.sagaConfig(),
                graph.definitionSource(),
                graph.streamingOutputNodeId(),
                graph.streamingInputs());
    }

    private static HttpResourceOperator realResourceOperator(RecordingFailingHttpTransport transport) {
        BlgeExpressionEvaluator evaluator = new BlgeExpressionEvaluator();
        return new HttpResourceOperator(
                transport,
                new OneResourceRegistry(),
                evaluator,
                new UrlTemplateRenderer(),
                new PayloadExtractor(JSON),
                new ResponseValidator(evaluator));
    }

    private record RuntimeCase(
            String id,
            List<FixtureRule> rules,
            TestRunEvidence.Status expectedStatus,
            boolean sequence,
            boolean mustNotCall) {
    }

    /** A real transport boundary that never opens a socket and fails loudly on any invocation. */
    private static final class RecordingFailingHttpTransport extends HttpRequestOperator {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public HttpResponseOutput execute(HttpRequestInput input, OperatorContext context) {
            calls.incrementAndGet();
            throw new AssertionError("REAL_EXTERNAL_CALL_FORBIDDEN: " + input.url());
        }

        AtomicInteger calls() {
            return calls;
        }
    }

    private static final class OneResourceRegistry implements ResourceRegistry {
        private final ResourceDescriptor descriptor = new ResourceDescriptor(
                RESOURCE_ID,
                "https://connector.invalid/customer/{caseId}",
                "GET",
                Map.of(),
                null,
                Duration.ofSeconds(2),
                new ParameterMapping(Map.of("caseId", "ctx.params.caseId"), Map.of(), null),
                new ResponseProtocol.HttpStatus(),
                null);

        @Override
        public ResourceDescriptor resolve(String resourceId) {
            if (!RESOURCE_ID.equals(resourceId)) {
                throw new IllegalArgumentException("Unknown resource: " + resourceId);
            }
            return descriptor;
        }

        @Override
        public boolean contains(String resourceId) {
            return RESOURCE_ID.equals(resourceId);
        }

        @Override
        public Collection<ResourceDescriptor> all() {
            return List.of(descriptor);
        }
    }
}
