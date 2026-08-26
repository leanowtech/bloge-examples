package com.leanowtech.bloge.gateway.testing;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.leanowtech.bloge.core.dsl.GraphBuilder;
import com.leanowtech.bloge.core.model.Graph;
import com.leanowtech.bloge.core.operator.Operator;
import com.leanowtech.bloge.core.spi.OperatorRegistry;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;
import com.leanowtech.bloge.gateway.gateway.GatewayGraphService;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.testing.admission.TestRuntimeAdmissionGate;
import com.leanowtech.bloge.gateway.testing.api.FixtureBundleRepository;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiRequest;
import com.leanowtech.bloge.gateway.testing.api.TestExecutionApiService;
import com.leanowtech.bloge.gateway.testing.api.TestReplayPayloadService;
import com.leanowtech.bloge.gateway.testing.api.TestRunRepository;
import com.leanowtech.bloge.gateway.testing.api.TestSecurityEventRepository;
import com.leanowtech.bloge.gateway.testing.domain.ExecutionMode;
import com.leanowtech.bloge.gateway.testing.domain.FixtureBundle;
import com.leanowtech.bloge.gateway.testing.domain.FixtureRule;
import com.leanowtech.bloge.gateway.testing.evidence.TestEvidenceIntegrityService;
import com.leanowtech.bloge.gateway.testing.security.ExecutionControlBoundaryGuardFilter;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.runtime.VisualDslRunnerFactory;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSimulationExecutor;
import com.leanowtech.bloge.gateway.visual.simulation.JsonSchemaSampleGenerator;
import com.leanowtech.bloge.gateway.visual.simulation.VisualGraphSimulationService;
import com.leanowtech.bloge.gateway.visual.simulation.VisualProductionAdmissionPolicy;
import com.leanowtech.bloge.gateway.visual.simulation.VisualSimulationProductionAdmissionException;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Fixed Stage 0 proof that production test-control rejection survives either boundary bypass. */
class Stage0Exit04ProductionBypassMatrixTest {

    private static final String FILTER_CODE = "RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN";
    private static final String SERVICE_CODE = "RG.TEST.ENVIRONMENT_FORBIDDEN";
    private static final String FILTER_TITLE =
            "Production execution cannot accept test-control headers.";
    private static final String FINGERPRINT = "sha256:" + "0123456789abcdef".repeat(4);

    @ParameterizedTest(name = "production filter rejects {0} before deserialization and downstream work")
    @MethodSource("stage0Cases")
    void productionFilterRejectsFixedMatrixBeforeDeserializationAndDownstreamWork(
            Stage0ControlCase controlCase) throws Exception {
        BodyDeserializationSentinel.calls.set(0);
        NonProductionExecutionService downstream = new NonProductionExecutionService();
        IntegrationAccessAuditRepository audit = mock(IntegrationAccessAuditRepository.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductionRunEndpoint(downstream))
                .addFilters(new ExecutionControlBoundaryGuardFilter(
                        new ObjectMapper(), audit, true, "production"))
                .build();

        String response = mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "s0-exit-04-filter")
                        .header("X-BLOGE-Test-Envelope", controlCase.transportEnvelope())
                        .header("X-BLOGE-Test-Fidelity", controlCase.controlMode())
                        .header("X-BLOGE-Test-Inline", controlCase.transportInline())
                        .content(controlCase.businessBody()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.code").value(FILTER_CODE))
                .andExpect(jsonPath("$.title").value(FILTER_TITLE))
                .andReturn().getResponse().getContentAsString();

        assertThat(BodyDeserializationSentinel.calls).hasValue(0);
        assertThat(downstream.productionDeployment).isFalse();
        assertThat(downstream.compilerCalls).hasValue(0);
        assertThat(downstream.executorCalls).hasValue(0);
        assertSanitized(response, controlCase);
        verify(audit).append(any());
    }

    @org.junit.jupiter.api.Test
    void productionFilterRejectsFunctionControlReferenceBeforeDeserialization() throws Exception {
        BodyDeserializationSentinel.calls.set(0);
        NonProductionExecutionService downstream = new NonProductionExecutionService();
        IntegrationAccessAuditRepository audit = mock(IntegrationAccessAuditRepository.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProductionRunEndpoint(downstream))
                .addFilters(new ExecutionControlBoundaryGuardFilter(
                        new ObjectMapper(), audit, true, "production"))
                .build();
        String envelope = base64Url("{\"purpose\":\"GRAPH_CONTRACT_TEST\","
                + "\"worldModel\":{\"id\":\"world-ref\",\"revision\":1,"
                + "\"fingerprint\":\"" + FINGERPRINT + "\"},"
                + "\"functionControl\":{\"id\":\"function-ref\",\"revision\":1,"
                + "\"fingerprint\":\"" + FINGERPRINT + "\"},"
                + "\"correlationId\":\"production-function-control\"}");

        String response = mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "production-function-control")
                        .header("X-BLOGE-Test-Envelope", envelope)
                        .content("{\"businessPayload\":\"secret-business-payload\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(FILTER_CODE))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("function-ref", FINGERPRINT, "secret-business-payload");
        assertThat(BodyDeserializationSentinel.calls).hasValue(0);
        assertThat(downstream.compilerCalls).hasValue(0);
        assertThat(downstream.executorCalls).hasValue(0);
        verify(audit).append(any());
    }

    @ParameterizedTest(name = "production service rejects {0} when the filter is bypassed")
    @MethodSource("stage0Cases")
    void productionServiceRejectsFixedMatrixBeforePlanningCompilationAndExecution(
            Stage0ControlCase controlCase) {
        AtomicInteger targetPlanningCalls = new AtomicInteger();
        AtomicInteger postCompileAdmissionCalls = new AtomicInteger();
        AtomicInteger executorCalls = new AtomicInteger();

        Operator<Object, Object> countingOperator = (input, context) -> {
            executorCalls.incrementAndGet();
            return input;
        };
        Graph graph = new GraphBuilder("must-not-run")
                .node("subject", countingOperator)
                .build();
        GatewayGraphService graphService = mock(GatewayGraphService.class);
        when(graphService.requireGraph(anyString())).thenAnswer(invocation -> {
            targetPlanningCalls.incrementAndGet();
            return graph;
        });
        OperatorRegistry compilerRegistry = mock(OperatorRegistry.class);
        TestRuntimeAdmissionGate postCompileAdmissionSentinel = (identity, intent) -> {
            postCompileAdmissionCalls.incrementAndGet();
            return noOpGuard();
        };
        FixtureBundleRepository fixtures = mock(FixtureBundleRepository.class);
        TestRunRepository runs = mock(TestRunRepository.class);
        TestSecurityEventRepository securityEvents = mock(TestSecurityEventRepository.class);
        TestExecutionApiService service = new TestExecutionApiService(
                graphService, compilerRegistry, mock(ResourceRegistry.class),
                new BlgeExpressionEvaluator(), new ObjectMapper().findAndRegisterModules(),
                fixtures, runs, securityEvents, Duration.ofDays(1),
                mock(TestReplayPayloadService.class), mock(TestEvidenceIntegrityService.class),
                postCompileAdmissionSentinel);

        assertThatThrownBy(() -> service.execute(
                controlCase.request(), productionIdentity()))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    IntegrationProblem problem = failure.problem();
                    assertThat(problem.status()).isEqualTo(403);
                    assertThat(problem.code()).isEqualTo(SERVICE_CODE);
                    assertThat(problem.title()).isEqualTo(
                            "Caller-driven execution control is restricted to test and staging identities.");
                    assertSanitized(problem.toString() + failure.getMessage(), controlCase);
                });

        assertThat(targetPlanningCalls).hasValue(0);
        // Registry lookup is a compiler dependency; admission is a separate post-compile boundary.
        verifyNoInteractions(compilerRegistry);
        assertThat(postCompileAdmissionCalls).hasValue(0);
        assertThat(executorCalls).hasValue(0);
        verifyNoInteractions(fixtures, runs);
        verify(securityEvents).append(any());
    }

    @ParameterizedTest(name = "visual production service rejects {0} before kernel execution")
    @MethodSource("stage0Cases")
    void visualProductionServiceRejectsFixedMatrixBeforeKernelExecution(
            Stage0ControlCase controlCase) {
        AtomicInteger visualExecutorCalls = new AtomicInteger();
        VisualSimulationExecutor visualExecutor = plan -> {
            visualExecutorCalls.incrementAndGet();
            throw new AssertionError("production visual simulation reached the kernel");
        };
        VisualGraphSimulationService visualService = new VisualGraphSimulationService(
                mock(GraphDraftValidator.class), mock(VisualOperatorCatalog.class),
                new JsonSchemaSampleGenerator(), mock(VisualDslRunnerFactory.class),
                visualExecutor, VisualProductionAdmissionPolicy.productionDefault());

        assertThatThrownBy(() -> visualService.simulate(null,
                Map.of("requestedControlMode", controlCase.controlMode(),
                        "secretBusinessPayload", controlCase.secretToken()),
                controlCase.controlMode(), Map.of()))
                .isInstanceOfSatisfying(
                        VisualSimulationProductionAdmissionException.class, failure -> {
                            assertThat(VisualSimulationProductionAdmissionException.CODE)
                                    .isEqualTo("RG.PRODUCTION.VISUAL_SIMULATION_FORBIDDEN");
                            assertThat(failure.getMessage())
                                    .isEqualTo(VisualSimulationProductionAdmissionException.TITLE);
                            assertSanitized(failure.getMessage(), controlCase);
                        });

        assertThat(visualExecutorCalls).hasValue(0);
    }

    /** Literal matrix: expected values are not derived through {@link ExecutionMode#resolve}. */
    private static Stream<Stage0ControlCase> stage0Cases() {
        return Stream.of(
                new Stage0ControlCase("real/read-only", "REAL", "stage0-read-only", null),
                new Stage0ControlCase("schema stand-in", ExecutionMode.SCHEMA_STANDIN.name(),
                        "stage0-read-only",
                        rule("schema-standin", "designOnlyOperator",
                                FixtureRule.Behavior.returning(Map.of("status", "synthetic")))),
                new Stage0ControlCase("descriptor protocol", ExecutionMode.DESCRIPTOR_PROTOCOL.name(),
                        "stage0-resource",
                        descriptorRule("descriptor-protocol", FixtureRule.DoubleBoundary.NODE)),
                new Stage0ControlCase("descriptor transport", ExecutionMode.DESCRIPTOR_TRANSPORT.name(),
                        "stage0-resource",
                        descriptorRule("descriptor-transport", FixtureRule.DoubleBoundary.TRANSPORT)),
                new Stage0ControlCase("return/output-level", "OUTPUT_LEVEL", "stage0-read-only",
                        rule("output-level", "deployedOperator",
                                FixtureRule.Behavior.returning(Map.of("status", "fixed")))),
                new Stage0ControlCase("implicit deny", "IMPLICIT_DENY", "stage0-resource", null));
    }

    private static FixtureRule rule(String ruleId, String operatorRef,
                                    FixtureRule.Behavior behavior) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, ruleId,
                FixtureRule.Selector.operator(operatorRef), behavior,
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static FixtureRule descriptorRule(String ruleId,
                                               FixtureRule.DoubleBoundary boundary) {
        return new FixtureRule(FixtureRule.SCHEMA_VERSION, ruleId,
                FixtureRule.Selector.resource("customer.lookup"),
                FixtureRule.Behavior.protocolResponse(
                        "{\"status\":\"ok\"}", 200, Map.of("X-Fixture", "fixed"), boundary),
                FixtureRule.Consumption.once(), FixtureRule.SchemaCheck.strict());
    }

    private static IntegrationRequestContext productionIdentity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "production", "local", "WORKLOAD",
                "test-runner", "", "TEST_EXECUTION", "s0-exit-04-service",
                java.util.Set.of("quality"), "CONFIDENTIAL", "");
    }

    private static TestRuntimeAdmissionGate.AdmissionGuard noOpGuard() {
        return new TestRuntimeAdmissionGate.AdmissionGuard() {
            @Override public void checkpoint() { }
            @Override public void close() { }
        };
    }

    private static void assertSanitized(String failureText, Stage0ControlCase controlCase) {
        assertThat(failureText)
                .doesNotContain(controlCase.secretToken())
                .doesNotContain(controlCase.controlMode())
                .doesNotContain(controlCase.businessBody())
                .doesNotContain(controlCase.transportEnvelope())
                .doesNotContain(controlCase.transportInline())
                .doesNotContain("java.lang.")
                .doesNotContain("\tat ")
                .doesNotContain("Exception:");
    }

    private record Stage0ControlCase(
            String displayName,
            String controlMode,
            String targetId,
            FixtureRule rule
    ) {
        private String secretToken() {
            return "sensitive-" + displayName.replaceAll("[^a-zA-Z0-9]", "-");
        }

        private String transportEnvelope() {
            return base64Url("{\"purpose\":\"GRAPH_CONTRACT_TEST\",\"scenario\":{"
                    + "\"id\":\"s0-exit-04-" + slug() + "\",\"revision\":1,"
                    + "\"fingerprint\":\"" + FINGERPRINT + "\"},"
                    + "\"correlationId\":\"s0-exit-04-filter\"}");
        }

        private String transportInline() {
            return base64Url("{\"fixtureBundle\":{\"secret\":\"" + secretToken() + "\"}}");
        }

        private String businessBody() {
            return "{\"businessPayload\":\"" + secretToken() + "\"}";
        }

        private TestExecutionApiRequest request() {
            List<FixtureRule> rules = rule == null ? List.of() : List.of(rule);
            FixtureBundle bundle = new FixtureBundle(
                    FixtureBundle.SCHEMA_VERSION, "s0-exit-04-" + displayName, 1, "",
                    "INTERNAL", null, null, rules, List.of(),
                    Map.of("requestedControlMode", controlMode, "secret", secretToken()));
            return new TestExecutionApiRequest(
                    TestExecutionApiRequest.SCHEMA_VERSION,
                    new TestExecutionApiRequest.Target("GRAPH", targetId, ""),
                    TestExecutionApiService.AUTHORIZED_PURPOSE,
                    Map.of("businessPayload", secretToken()), bundle, null,
                    TestExecutionApiRequest.Verbosity.FULL,
                    Map.of("requestedControlMode", controlMode));
        }

        @Override
        public String toString() {
            return displayName;
        }

        private String slug() {
            return displayName.replaceAll("[^a-zA-Z0-9]", "-");
        }
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    @JsonDeserialize(using = BodyDeserializationSentinel.class)
    private record ProductionRunBody() { }

    private static final class BodyDeserializationSentinel
            extends JsonDeserializer<ProductionRunBody> {
        private static final AtomicInteger calls = new AtomicInteger();

        @Override
        public ProductionRunBody deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            calls.incrementAndGet();
            throw new IOException("request body must not be deserialized");
        }
    }

    private static final class NonProductionExecutionService {
        private final boolean productionDeployment = false;
        private final AtomicInteger compilerCalls = new AtomicInteger();
        private final AtomicInteger executorCalls = new AtomicInteger();

        private void compileAndExecute() {
            compilerCalls.incrementAndGet();
            executorCalls.incrementAndGet();
        }
    }

    @RestController
    private static final class ProductionRunEndpoint {
        private final NonProductionExecutionService downstream;

        private ProductionRunEndpoint(NonProductionExecutionService downstream) {
            this.downstream = downstream;
        }

        @PostMapping("/api/visual/graphs/simulate")
        private Map<String, Object> execute(@RequestBody ProductionRunBody ignored) {
            downstream.compileAndExecute();
            return Map.of("status", "unexpected");
        }
    }
}
