package com.leanowtech.bloge.gateway.testing.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRecord;
import com.leanowtech.bloge.gateway.integration.IntegrationAccessAuditRepository;
import com.leanowtech.bloge.gateway.testing.protocol.TestControlProtocolLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExecutionControlBoundaryGuardFilterTest {

    @Test
    void productionRunWithNestedControlIsRejectedAndAuditedBeforeController() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit)).build();

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Tenant-Id", "tenant-a")
                        .header("X-Environment-Id", "prod")
                        .content("""
                                {"context":{"orderId":"O-1","controlPlan":{"rules":[]}}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"))
                .andExpect(jsonPath("$.details.field").value("controlPlan"));

        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reasonCode()).isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(record.tenantId()).isBlank();
            assertThat(record.environmentId()).isEqualTo("prod");
        });
    }

    @ParameterizedTest(name = "ordinary payload is forwarded on {0}")
    @MethodSource("runPaths")
    void ordinaryBusinessPayloadIsReplayedUnchangedToController(String path) throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit)).build();
        String payload = "{\"context\":{\"orderId\":\"O-1\",\"customerNote\":\"business payload\"}}";

        mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.context.orderId").value("O-1"))
                .andExpect(jsonPath("$.context.customerNote").value("business payload"));

        assertThat(endpoint.calls).isEqualTo(1);
        assertThat(endpoint.lastBody).isEqualTo(new ObjectMapper().readTree(payload));
        assertThat(audit.records).isEmpty();
    }

    @Test
    void rejectionFailsClosedWhenSecurityAuditCannotCommit() throws Exception {
        IntegrationAccessAuditRepository unavailable = new UnavailableAudit();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunEndpoint())
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), unavailable)).build();

        mvc.perform(post("/api/gateway/resources/execute")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resourceId\":\"customer.get\",\"fixtureBundle\":{}}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE"));
    }

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("productionRunPathsAndControlFields")
    void productionRunRejectsCapabilityStudioControlsBeforeController(String path, String field)
            throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        String businessPayload = "customer-secret-should-not-cross-boundary";
        String requestBody = "{\"context\":{\"nested\":{\"" + field
                + "\":{},\"businessPayload\":\"" + businessPayload + "\"}}}";
        String response = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", "corr-capability-control")
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"))
                .andExpect(jsonPath("$.details.field").value(field))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(businessPayload);
        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reasonCode())
                    .isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(record.toString()).doesNotContain(businessPayload);
        });
    }

    @ParameterizedTest(name = "{0} rejects control header {1}")
    @MethodSource("productionRunPathsAndControlHeaders")
    void productionRunRejectsControlHeadersBeforeDtoParsing(String path, String headerName)
            throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();
        String headerValue = "secret-control-header-value";

        String response = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header(headerName, headerValue)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(headerName, headerValue);
        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).singleElement().satisfies(record -> {
            assertThat(record.operation()).isEqualTo("PRODUCTION_RUN_CONTROL_GUARD");
            assertThat(record.outcome()).isEqualTo("DENIED");
            assertThat(record.reasonCode()).isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(record.toString()).doesNotContain(headerName, headerValue);
        });
    }

    @ParameterizedTest(name = "simulate rejects control header by presence: {0}")
    @MethodSource("presenceOnlyControlHeaderCases")
    void simulateRejectsEmptyOversizedAndDuplicateControlHeaderValues(
            String caseName, List<String> headerValues) throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-BLOGE-Test-Envelope", headerValues.toArray())
                        .content("{\"context\":{\"orderId\":\"O-1\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code")
                        .value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"));

        assertThat(endpoint.calls).as(caseName).isZero();
        assertThat(audit.records).as(caseName).singleElement().satisfies(record -> {
            assertThat(record.reasonCode())
                    .isEqualTo("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(record.outcome()).isEqualTo("DENIED");
        });
    }

    @Test
    void nonJsonControlHeaderIsRejectedBeforeBodyInspection() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("x-bloge-test-inline", "malformed-and-sensitive")
                        .content("business payload that is not JSON"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN"));

        assertThat(endpoint.calls).isZero();
        assertThat(audit.records).hasSize(1);
    }

    @Test
    void benignLookalikeHeadersAreForwarded() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-BLOGE-Test-Envelope-Extra", "ordinary")
                        .header("X-BLOGE-Test-Inline-Extra", "ordinary")
                        .content("{\"context\":{\"orderId\":\"O-1\"}}"))
                .andExpect(status().isOk());

        assertThat(endpoint.calls).isEqualTo(1);
        assertThat(audit.records).isEmpty();
    }

    @ParameterizedTest(name = "{0} visual simulation does not use production guard")
    @ValueSource(strings = {"test", "staging"})
    void visualSimulationIsNotProductionGuardedOutsideProductionDeployment(String deploymentProfile)
            throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(
                        new ObjectMapper(), audit, false, deploymentProfile))
                .build();

        String response = mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Environment-Id", "production")
                        .header("X-BLOGE-Test-Inline", "allowed-outside-production")
                        .content("{\"context\":{\"controlPlan\":{}}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain("RG.PRODUCTION.");
        assertThat(endpoint.calls).as(deploymentProfile).isEqualTo(1);
        assertThat(audit.records).isEmpty();
    }

    @ParameterizedTest(name = "profile={0}, environment={1}, rejects={2}")
    @MethodSource("serverProductionDeploymentMatrix")
    void visualSimulationUsesEitherServerProductionSignal(
            boolean productionProfileActive, String configuredEnvironment, boolean rejects)
            throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(
                        new ObjectMapper(), audit, productionProfileActive, configuredEnvironment))
                .build();

        var result = mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Environment-Id", "test")
                        .header("X-BLOGE-Test-Inline", "server-owned-decision")
                        .content("{\"context\":{\"orderId\":\"O-1\"}}"))
                .andReturn();

        if (rejects) {
            assertThat(result.getResponse().getStatus()).isEqualTo(400);
            assertThat(result.getResponse().getContentAsString())
                    .contains("RG.PRODUCTION.CONTROL_FIELD_FORBIDDEN");
            assertThat(endpoint.calls).isZero();
            assertThat(audit.records).hasSize(1);
        } else {
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            assertThat(endpoint.calls).isEqualTo(1);
            assertThat(audit.records).isEmpty();
        }
    }

    @Test
    void testingEndpointIsNotTreatedAsProductionExecution() throws Exception {
        RecordingAudit audit = new RecordingAudit();
        RunEndpoint endpoint = new RunEndpoint();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(endpoint)
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), audit))
                .build();

        mvc.perform(post("/api/testing/executions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-BLOGE-Test-Inline", "allowed-on-testing-surface")
                        .content("{\"context\":{\"orderId\":\"O-1\"}}"))
                .andExpect(status().isOk());

        assertThat(endpoint.calls).isEqualTo(1);
        assertThat(audit.records).isEmpty();
    }

    @Test
    void headerRejectionFailsClosedWhenSecurityAuditCannotCommit() throws Exception {
        IntegrationAccessAuditRepository unavailable = new UnavailableAudit();
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunEndpoint())
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), unavailable))
                .build();

        mvc.perform(post("/api/visual/graphs/simulate")
                        .contentType(MediaType.TEXT_PLAIN)
                        .header("X-BLOGE-Test-Envelope", "must-not-be-retained")
                        .content("not-json"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.SECURITY_AUDIT_UNAVAILABLE"));
    }

    private static Stream<String> runPaths() {
        return Stream.of(
                "/api/gateway/resources/execute",
                "/api/gateway/examples/compose/run",
                "/api/visual/drafts/run",
                "/api/visual/graphs/simulate",
                "/api/visual/drafts/draft-1/run",
                "/api/visual/publications/publication-1/run",
                "/api/gateway/graphs/contracts/tests/run",
                "/api/visual/operators/tests/run",
                "/api/visual/golden-cases/case-1/run",
                "/api/gateway/graphs/contracts/tests/suites/suite-1/run",
                "/api/gateway/graphs/contracts/tests/suites/run-all",
                "/api/visual/operators/tests/suites/suite-1/run",
                "/api/visual/operators/tests/suites/run-all",
                "/api/visual/golden-cases/publications/publication-1/run");
    }

    private static Stream<Arguments> productionRunPathsAndControlFields() {
        return runPaths().flatMap(path -> Stream.of(
                Arguments.of(path, "mirrorPlan"),
                Arguments.of(path, "replayPayloads"),
                Arguments.of(path, "replacementRules"),
                Arguments.of(path, "resolverOverrides"),
                Arguments.of(path, "scenarioPackRef"),
                Arguments.of(path, "Fixture"),
                Arguments.of(path, "fixtures"),
                Arguments.of(path, "stub"),
                Arguments.of(path, "STUBS"),
                Arguments.of(path, "binding_override"),
                Arguments.of(path, "Binding-Overrides"),
                Arguments.of(path, "dependencyBehavior"),
                Arguments.of(path, "dependency_behaviors"),
                Arguments.of(path, "scenarioDataset"),
                Arguments.of(path, "scenario_dataset_ref"),
                Arguments.of(path, "scenarioDatasetRefs")));
    }

    private static Stream<Arguments> productionRunPathsAndControlHeaders() {
        return runPaths().flatMap(path -> Stream.of(
                Arguments.of(path, "x-bloge-test-envelope"),
                Arguments.of(path, "X-BLOGE-TEST-FIDELITY"),
                Arguments.of(path, "x-BLoGe-TeSt-Scope"),
                Arguments.of(path, "X-BLOGE-Test-Inline")));
    }

    private static Stream<Arguments> presenceOnlyControlHeaderCases() {
        return Stream.of(
                Arguments.of("empty value", List.of("")),
                Arguments.of("oversized value", List.of(
                        "x".repeat(TestControlProtocolLimits.MAX_ENCODED_HEADER_BYTES + 1))),
                Arguments.of("duplicate values", List.of("first", "second")));
    }

    private static Stream<Arguments> serverProductionDeploymentMatrix() {
        return Stream.of(
                Arguments.of(true, "test", true),
                Arguments.of(false, "prod", true),
                Arguments.of(false, "staging", false));
    }

    @Test
    void oversizedGuardedPayloadIsRejectedInsteadOfForwardingATruncatedBody() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new RunEndpoint())
                .addFilters(new ExecutionControlBoundaryGuardFilter(new ObjectMapper(), new RecordingAudit()))
                .build();
        String body = "{\"context\":{\"value\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}}";

        mvc.perform(post("/api/visual/drafts/run")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("RG.PRODUCTION.REQUEST_BODY_TOO_LARGE"));
    }

    @RestController
    private static final class RunEndpoint {
        private int calls;
        private JsonNode lastBody;

        @PostMapping({"/api/visual/drafts/run", "/api/gateway/resources/execute",
                "/api/visual/graphs/simulate"})
        Map<String, Object> run(@RequestBody Map<String, Object> body) {
            calls++;
            lastBody = new ObjectMapper().valueToTree(body);
            return body;
        }

        @PostMapping({"/api/gateway/graphs/contracts/tests/run",
                "/api/visual/operators/tests/run",
                "/api/visual/golden-cases/{caseId}/run",
                "/api/gateway/graphs/contracts/tests/suites/{suiteId}/run",
                "/api/gateway/graphs/contracts/tests/suites/run-all",
                "/api/visual/operators/tests/suites/{suiteId}/run",
                "/api/visual/operators/tests/suites/run-all",
                "/api/visual/golden-cases/publications/{publicationId}/run"})
        Map<String, Object> callerDrivenTestRun(@RequestBody Map<String, Object> body) {
            calls++;
            lastBody = new ObjectMapper().valueToTree(body);
            return body;
        }

        @PostMapping({"/api/gateway/examples/compose/run",
                "/api/visual/drafts/{draftId}/run",
                "/api/visual/publications/{publicationId}/run"})
        Map<String, Object> parameterizedRun(@RequestBody Map<String, Object> body) {
            calls++;
            lastBody = new ObjectMapper().valueToTree(body);
            return body;
        }

        @PostMapping("/api/testing/executions")
        Map<String, Object> testingRun(@RequestBody Map<String, Object> body) {
            calls++;
            lastBody = new ObjectMapper().valueToTree(body);
            return body;
        }
    }

    private static final class RecordingAudit implements IntegrationAccessAuditRepository {
        private final List<IntegrationAccessAuditRecord> records = new ArrayList<>();
        @Override public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            IntegrationAccessAuditRecord stored = record.withSequence(records.size() + 1L);
            records.add(stored);
            return stored;
        }
        @Override public List<IntegrationAccessAuditRecord> recent(int limit) { return List.copyOf(records); }
    }

    private static final class UnavailableAudit implements IntegrationAccessAuditRepository {
        @Override
        public IntegrationAccessAuditRecord append(IntegrationAccessAuditRecord record) {
            throw new IllegalStateException("unavailable");
        }

        @Override
        public List<IntegrationAccessAuditRecord> recent(int limit) {
            return List.of();
        }
    }
}
