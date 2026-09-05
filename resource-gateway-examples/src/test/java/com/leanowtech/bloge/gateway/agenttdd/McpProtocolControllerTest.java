package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorLibraryRegistry;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies the MCP Streamable HTTP boundary for legacy and stateless Codex clients. */
class McpProtocolControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void listsToolsUsingModernStatelessRoutingAndReadPurpose() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("called", name));
        HttpHeaders headers = modernHeaders("tools/list", null);

        JsonNode response = controller.exchange(request(7, "tools/list", Map.of()), headers).getBody();

        assertThat(response.path("jsonrpc").asText()).isEqualTo("2.0");
        assertThat(response.path("id").asInt()).isEqualTo(7);
        assertThat(response.path("result").path("tools")).hasSize(46);
        assertThat(response.path("result").path("tools").toString()).contains("rg.library.overview.get");
        assertThat(response.path("result").path("tools").toString()).contains("rg.dsl.reference.get");
        assertThat(response.path("result").path("tools").toString()).contains("rg.fixture.provide");
        assertThat(response.path("result").path("tools").toString()).contains("rg.resource.declare");
        assertThat(response.path("result").path("tools").toString())
                .contains("rg.solution.compose", "rg.solution.invoke", "rg.solution.performance");
        assertThat(response.path("result").fieldNames()).toIterable().containsExactly("tools");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
    }

    @Test
    void listsToolsForNegotiatedLegacyClientWithoutDraftRoutingHeaders() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("called", name));
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);

        ResponseEntity<JsonNode> response = controller.exchange(
                request(8, "tools/list", Map.of()), headers);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().path("result").path("tools")).hasSize(46);
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
    }

    @Test
    void businessSurfaceListsOnlyPurposeAuthorizedBusinessTools() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity("AGENT_TDD_READ"));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of());
        HttpHeaders headers = modernHeaders("tools/list", null);
        headers.set("X-RG-Surface", "BUSINESS_SOLUTION");

        JsonNode response = controller.exchange(request(81, "tools/list", Map.of()), headers).getBody();
        String listed = response.at("/result/tools").toString();

        assertThat(listed)
                .contains("rg.library.overview.get", "rg.solution.getContract", "rg.solution.readiness")
                .doesNotContain("rg.tool.compose", "rg.dsl.reference.get", "rg.scenario.test",
                        "rg.fixture.provide");
    }

    @Test
    void businessSurfaceRejectsAHiddenToolBeforeInvocation() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_DRAFT_WRITE)))
                .thenReturn(identity("AGENT_TDD_AUTHORING"));
        McpToolControllerProbe invoker = new McpToolControllerProbe();
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);
        HttpHeaders headers = modernHeaders("tools/call", "rg.tool.compose");
        headers.set("X-RG-Surface", "BUSINESS_SOLUTION");

        JsonNode response = controller.exchange(request(82, "tools/call", Map.of(
                "name", "rg.tool.compose", "arguments", Map.of(
                        "toolRef", "tool:hidden", "graph", Map.of("dsl", "graph hidden {}"),
                        "libraryRefs", List.of(), "authoringContextFingerprint", "sha256:context",
                        "authoringReceiptFingerprint", "sha256:receipt",
                        "idempotencyKey", "hidden-tool"))), headers).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32031);
        assertThat(response.path("error").path("message").asText())
                .isEqualTo("TOOL_NOT_VISIBLE_IN_SURFACE");
        assertThat(invoker.called).isFalse();
    }

    @Test
    void acceptsLegacyInitializedNotificationWithoutJsonRpcResponse() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        JsonNode notification = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));

        ResponseEntity<JsonNode> response = controller.exchange(notification, headers);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void callsToolWithImpactSpecificAuthenticationAndStructuredEnvelope() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_EXECUTE)))
                .thenReturn(identity());
        McpToolInvoker invoker = (name, arguments, identity) -> Map.of(
                "ok", true,
                "data", Map.of("goldenSetId", "sha256:golden", "side", "RED",
                        "realExternalCalls", 0),
                "diagnostics", java.util.List.of());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);
        HttpHeaders headers = modernHeaders("tools/call", "rg.simulate");
        JsonNode request = request(9, "tools/call", Map.of(
                "name", "rg.simulate",
                "arguments", Map.of("toolRef", "ride:cancel", "libraryRefs", java.util.List.of("ride"),
                        "cases", Map.of("caseSetRef", "cancel-golden"))));

        JsonNode response = controller.exchange(request, headers).getBody();

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.path("result").path("structuredContent").path("ok").asBoolean()).isTrue();
        assertThat(response.path("result").path("structuredContent").path("data")
                .path("realExternalCalls").asInt()).isZero();
        assertThat(response.path("result").path("content").get(0).path("type").asText()).isEqualTo("text");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_EXECUTE);
    }

    @Test
    void validatesFeatureTrustAndSolutionRuntimeResponsesAgainstStrictSchemas() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), any(IntegrationOperation.class))).thenReturn(identity());
        McpToolInvoker invoker = (name, arguments, identity) -> Map.of(
                "ok", true,
                "data", switch (name) {
                    case "rg.feature.evaluate" -> Map.of(
                            "featureRef", "responsibility.party", "value", "none",
                            "evaluationToken", "a.b.c", "evaluationKind", "API");
                    case "rg.solution.getContract" -> Map.of(
                            "solutionRef", "sol:cancel", "problem", "Resolve a dispute.",
                            "inputs", List.of(Map.of(
                                    "name", "party", "featureRef", "responsibility.party",
                                    "evaluationKind", "API", "determinism", "DETERMINISTIC",
                                    "evaluationInputs", Map.of("orderId", "string"),
                                    "output", Map.of("type", "string"))),
                            "output", Map.of("result", "structured", "reasoning", "required"));
                    case "rg.solution.invoke" -> Map.of(
                            "result", Map.of("decision", "UPHELD"), "reasoning", "rule R1",
                            "instructionRef", "ins:uphold", "rulePath", List.of("R1"),
                            "verifiedFeatureCount", 1,
                            "publicationId", "solution-publication:cancel-v1",
                            "implementationFingerprint", "sha256:implementation",
                            "executionStatus", "COMPLETED");
                    case "rg.scenario.test" -> Map.of(
                            "scenarioRef", "scn:root", "byCase", List.of(Map.of(
                                    "caseId", "g1", "hitRuleId", "R1", "outlet", Map.of(
                                            "outletKind", "INSTRUCTION", "ref", "ins:uphold"),
                                    "pass", true)), "passed", 1, "failed", 0,
                            "realExternalCalls", 0);
                    case "rg.solution.baseline" -> Map.ofEntries(
                            Map.entry("solutionRef", "sol:cancel"),
                            Map.entry("caseSetRef", "caseSet:cancel"),
                            Map.entry("caseSetRevision", 2), Map.entry("solutionRevision", 1),
                            Map.entry("solutionContractFingerprint", "sha256:solution"),
                            Map.entry("goldenSetId", "sha256:golden"),
                            Map.entry("evidenceRef", "sol:cancel@1"), Map.entry("side", "GREEN"),
                            Map.entry("byLayer", Map.of("integration", Map.of("pass", 1, "fail", 0))),
                            Map.entry("cases", List.of(Map.of("caseId", "g1", "verdict", "GREEN_PASS"))),
                            Map.entry("businessBacklog", List.of()), Map.entry("realExternalCalls", 0),
                            Map.entry("status", "GO"));
                    default -> throw new IllegalArgumentException();
                },
                "diagnostics", List.of());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);

        JsonNode evaluated = controller.exchange(request(91, "tools/call", Map.of(
                "name", "rg.feature.evaluate", "arguments", Map.of(
                        "featureRef", "responsibility.party", "inputs", Map.of("orderId", "O-1")))),
                modernHeaders("tools/call", "rg.feature.evaluate")).getBody();
        JsonNode contract = controller.exchange(request(92, "tools/call", Map.of(
                "name", "rg.solution.getContract", "arguments", Map.of("solutionRef", "sol:cancel"))),
                modernHeaders("tools/call", "rg.solution.getContract")).getBody();
        JsonNode invoked = controller.exchange(request(93, "tools/call", Map.of(
                "name", "rg.solution.invoke", "arguments", Map.of(
                        "solutionRef", "sol:cancel", "inputs", Map.of(
                                "party", Map.of("value", "none")),
                        "idempotencyKey", "invoke-cancel-O-1"))),
                modernHeaders("tools/call", "rg.solution.invoke")).getBody();
        JsonNode scenario = controller.exchange(request(94, "tools/call", Map.of(
                "name", "rg.scenario.test", "arguments", Map.of(
                        "scenarioRef", "scn:root", "cases", List.of(Map.of(
                                "caseId", "g1", "given", Map.of("party", "none"),
                                "expect", Map.of("outletKind", "INSTRUCTION")))))),
                modernHeaders("tools/call", "rg.scenario.test")).getBody();
        JsonNode baseline = controller.exchange(request(95, "tools/call", Map.of(
                "name", "rg.solution.baseline", "arguments", Map.of(
                        "solutionRef", "sol:cancel", "caseSetRef", "caseSet:cancel", "side", "GREEN"))),
                modernHeaders("tools/call", "rg.solution.baseline")).getBody();

        assertThat(evaluated.path("result").path("isError").asBoolean()).isFalse();
        assertThat(contract.path("result").path("isError").asBoolean()).isFalse();
        assertThat(invoked.path("result").path("isError").asBoolean()).isFalse();
        assertThat(scenario.path("result").path("isError").asBoolean()).isFalse();
        assertThat(baseline.path("result").path("isError").asBoolean()).isFalse();
    }

    @Test
    void servesTheScopedDslReferenceThroughTheRealStrictMcpBoundary() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        OperatorLibraryRegistry libraries = mock(OperatorLibraryRegistry.class);
        when(libraries.all()).thenReturn(List.of());
        when(libraries.find(any())).thenReturn(Optional.empty());
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        when(operators.builtInFunctions(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, drafts, mapper, null, null, null, null, null, operators);
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, tools);

        JsonNode response = controller.exchange(request(91, "tools/call", Map.of(
                "name", "rg.dsl.reference.get",
                "arguments", Map.of("libraryRefs", List.of(), "topics", List.of("graph"),
                        "includeExamples", true))),
                modernHeaders("tools/call", "rg.dsl.reference.get")).getBody();

        assertThat(response.path("error").isMissingNode()).isTrue();
        JsonNode reference = response.path("result").path("structuredContent").path("data");
        assertThat(reference.path("schemaVersion").asText()).isEqualTo("rg.dslReference.v1");
        assertThat(reference.path("supportedRootKinds").get(0).asText()).isEqualTo("graph");
        assertThat(reference.path("authoringContextFingerprint").asText()).startsWith("sha256:");
        assertThat(reference.toString()).doesNotContain("urlTemplate", "diagnostics", "lowering");
    }

    @Test
    void servesTheBusinessLibraryOverviewThroughTheRealStrictMcpBoundary() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        OperatorLibraryRegistry libraries = mock(OperatorLibraryRegistry.class);
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        VisualOperatorCatalog operators = mock(VisualOperatorCatalog.class);
        when(operators.list(any(OperatorCatalogQuery.class))).thenReturn(List.of());
        ResourceGatewayAgentTddTools tools = new ResourceGatewayAgentTddTools(
                libraries, drafts, mapper, null, null, null, null, null, operators);
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, tools);

        JsonNode response = controller.exchange(request(97, "tools/call", Map.of(
                        "name", "rg.library.overview.get",
                        "arguments", Map.of("includeSamples", false))),
                modernHeaders("tools/call", "rg.library.overview.get")).getBody();

        assertThat(response.path("error").isMissingNode()).isTrue();
        JsonNode overview = response.at("/result/structuredContent/data");
        assertThat(overview.path("buildingBlocks").isArray()).isTrue();
        assertThat(overview.path("worldModel").isObject()).isTrue();
        assertThat(overview.path("samples")).isEmpty();
        assertThat(overview.path("snapshotFingerprint").asText()).startsWith("sha256:");
        verify(authenticator).authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ));
    }

    @Test
    void definesFeatureThroughStrictMcpInputAndOutputSchemas() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_DRAFT_WRITE)))
                .thenReturn(identity());
        SolutionAgentTools authoring = new SolutionAgentTools(
                new InMemoryAgentTddStateRepository(), mapper);
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true,
                        "data", authoring.defineFeature(arguments, identity),
                        "diagnostics", List.of()));

        JsonNode response = controller.exchange(request(92, "tools/call", Map.of(
                        "name", "rg.feature.define",
                        "arguments", Map.of(
                                "featureYaml", "responsibility.party: { output: { type: string }, "
                                        + "evaluationKind: API, determinism: DETERMINISTIC, "
                                        + "inputs: { orderId: string }, evaluationRef: resource:test#$.value, "
                                        + "businessDefinition: { semanticKey: ride.cancel.party, intent: Decide party, "
                                        + "domain: ride-cancellation, businessObject: ride-order, requiredContext: [], "
                                        + "resultDomain: { type: string }, asOf: CURRENT, unknownPolicy: HUMAN_REVIEW, "
                                        + "acquisitionOwner: PLATFORM, authoritySource: test, "
                                        + "freshness: { mode: CURRENT }, effect: READ } }",
                                "idempotencyKey", "feature-mcp-v1"))),
                modernHeaders("tools/call", "rg.feature.define")).getBody();

        assertThat(response.path("error").isMissingNode()).isTrue();
        assertThat(response.at("/result/structuredContent/data/featureId").asText())
                .isEqualTo("responsibility.party");
        assertThat(response.at("/result/structuredContent/data/contractFingerprint").asText())
                .startsWith("sha256:");
        verify(authenticator).authenticate(any(), eq(IntegrationOperation.AGENT_TDD_DRAFT_WRITE));
    }

    @Test
    void rejectsArgumentsThatDoNotMatchTheAdvertisedSchemaBeforeInvocation() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        McpToolControllerProbe invoker = new McpToolControllerProbe();
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator, invoker);

        JsonNode response = controller.exchange(request(13, "tools/call", Map.of(
                "name", "rg.simulate",
                "arguments", Map.of("toolRef", "ride:cancel", "libraryRefs", java.util.List.of("ride"),
                        "cases", Map.of("caseSetRef", "golden", "rows", java.util.List.of())))),
                modernHeaders("tools/call", "rg.simulate")).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32602);
        assertThat(response.path("error").path("message").asText())
                .isEqualTo("Tool arguments do not match the declared input schema");
        assertThat(invoker.called).isFalse();
    }

    @Test
    void rejectsImplementationResponseThatViolatesOutputSchemaWithoutReflectingPayload() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true, "data", Map.of("undeclared", "customer-secret"),
                        "diagnostics", java.util.List.of()));

        JsonNode response = controller.exchange(request(14, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(response.toString()).doesNotContain("customer-secret", "undeclared");
    }

    @Test
    void requiresSuccessDataAndHidesUnexpectedApplicationFailureMessages() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController missingData = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of("ok", true, "diagnostics", java.util.List.of()));
        JsonNode invalidEnvelope = missingData.exchange(request(15, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        McpProtocolController failing = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> {
                    throw new IllegalStateException("provider customer-secret response");
                });
        JsonNode hiddenFailure = failing.exchange(request(16, "tools/call", Map.of(
                        "name", "rg.library.list", "arguments", Map.of())),
                modernHeaders("tools/call", "rg.library.list")).getBody();

        assertThat(invalidEnvelope.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(hiddenFailure.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(hiddenFailure.toString()).doesNotContain("customer-secret", "provider");
    }

    @Test
    void acceptsRowsProducedByTheRealDecisionScenarioEnumerator() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_DRAFT_WRITE)))
                .thenReturn(identity());
        GraphDraft draft = decisionDraft();
        List<com.fasterxml.jackson.databind.node.ObjectNode> rows =
                new AgentTddDecisionScenarioEnumerator(mapper).enumerate(draft, mapper.valueToTree(Map.of(
                        "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 3)));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true,
                        "data", Map.of("caseSetRef", "cancel-boundaries", "revision", 1,
                                "rows", rows, "enumeratedCount", rows.size()),
                        "diagnostics", List.of()));

        JsonNode response = controller.exchange(request(17, "tools/call", Map.of(
                        "name", "rg.scenario.upsertCases", "arguments", Map.of(
                                "caseSetRef", "cancel-boundaries", "toolRef", "cancel-tool",
                                "rows", List.of(), "enumerateFrom", Map.of(
                                        "decisionTableRef", "policy", "mode", "combinatorial", "maxCases", 3),
                                "idempotencyKey", "enum-1"))),
                modernHeaders("tools/call", "rg.scenario.upsertCases")).getBody();

        assertThat(response.path("result").path("isError").asBoolean()).isFalse();
        assertThat(response.at("/result/structuredContent/data/rows/0/enumeration/enumerationMode").asText())
                .isEqualTo("combinatorial");
    }

    @Test
    void acceptsRecoveryRequiredReadinessThroughTheAdvertisedOutputSchema() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of(
                        "ok", true,
                        "data", Map.of(
                                "toolRef", "risk-tool", "state", "IMPLEMENTED", "publishable", false,
                                "attestation", Map.of(
                                        "status", "RECOVERY_REQUIRED", "reasonCode",
                                        "ATTESTATION_RECOVERY_REQUIRED", "environment", "test",
                                        "cases", List.of(), "dependencies", List.of(),
                                        "realExternalCalls", 0)),
                        "diagnostics", List.of()));

        JsonNode response = controller.exchange(request(22, "tools/call", Map.of(
                        "name", "rg.readiness.get", "arguments", Map.of("toolRef", "risk-tool"))),
                modernHeaders("tools/call", "rg.readiness.get")).getBody();

        assertThat(response.at("/result/structuredContent/data/attestation/status").asText())
                .isEqualTo("RECOVERY_REQUIRED");
        assertThat(response.path("error").isMissingNode()).isTrue();
    }

    @Test
    void mapsAuthenticationAndPurposeFailuresToStablePayloadFreeRpcErrors() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_FAILED", "credential customer-secret rejected",
                        "corr-secret", Map.of("token", "customer-secret"))))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.forbidden(
                        "RG.INTEGRATION.PURPOSE_FORBIDDEN", "purpose provider-secret rejected",
                        "corr-secret", Map.of("operation", "provider-secret"))));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of());

        ResponseEntity<JsonNode> unauthenticated = controller.exchange(
                request(18, "tools/list", Map.of()), modernHeaders("tools/list", null));
        ResponseEntity<JsonNode> forbidden = controller.exchange(
                request(19, "tools/list", Map.of()), modernHeaders("tools/list", null));

        assertThat(unauthenticated.getStatusCode().value()).isEqualTo(401);
        assertThat(unauthenticated.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNotBlank();
        assertThat(unauthenticated.getBody().at("/error/data/code").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(forbidden.getStatusCode().value()).isEqualTo(403);
        assertThat(forbidden.getBody().at("/error/data/code").asText()).isEqualTo("FORBIDDEN_PURPOSE");
        assertThat(unauthenticated.getBody().toString()).doesNotContain("customer-secret", "corr-secret", "token");
        assertThat(forbidden.getBody().toString()).doesNotContain(
                "provider-secret", "corr-secret", "\"operation\":");
    }

    @Test
    void foldsUnexpectedPreDispatchFailuresWithoutReflectingTheirMaterial() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IllegalStateException("identity-provider customer-secret"));
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> Map.of());

        JsonNode response = controller.exchange(
                request(20, "tools/list", Map.of()), modernHeaders("tools/list", null)).getBody();

        assertThat(response.at("/error/code").asInt()).isEqualTo(-32603);
        assertThat(response.at("/error/message").asText())
                .isEqualTo("MCP request failed inside the governed boundary");
        assertThat(response.toString()).doesNotContain("identity-provider", "customer-secret");
    }

    @Test
    void rejectsRateLimitedCallsBeforeToolDispatch() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(), eq(IntegrationOperation.AGENT_TDD_EXECUTE)))
                .thenReturn(identity());
        AtomicInteger invocations = new AtomicInteger();
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), authenticator,
                (name, arguments, identity) -> {
                    invocations.incrementAndGet();
                    return Map.of("ok", true, "data", Map.of(
                                    "goldenSetId", "sha256:golden", "side", "RED",
                                    "realExternalCalls", 0),
                            "diagnostics", List.of());
                }, new McpRequestLimiter(1, 1, 1, 1, System::nanoTime));
        JsonNode call = request(25, "tools/call", Map.of(
                "name", "rg.simulate", "arguments", Map.of(
                        "toolRef", "ride:cancel", "libraryRefs", List.of(),
                        "cases", Map.of("caseSetRef", "cancel-golden"))));

        JsonNode first = controller.exchange(call, modernHeaders("tools/call", "rg.simulate")).getBody();
        JsonNode limited = controller.exchange(call, modernHeaders("tools/call", "rg.simulate")).getBody();

        assertThat(first.path("error").isMissingNode()).isTrue();
        assertThat(limited.at("/error/code").asInt()).isEqualTo(-32029);
        assertThat(invocations).hasValue(1);
    }

    @Test
    void rejectsModernRequestWhenRoutingHeadersDisagreeWithBody() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());
        HttpHeaders headers = modernHeaders("tools/list", null);

        JsonNode response = controller.exchange(
                request(3, "tools/call", Map.of("name", "rg.capability.list", "arguments", Map.of())),
                headers).getBody();

        assertThat(response.path("id").asInt()).isEqualTo(3);
        assertThat(response.path("error").path("code").asInt()).isEqualTo(-32020);
    }

    @Test
    void initializeNegotiatesOnlyKnownVersionsAndPreservesRequestIdOnFailure() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());

        JsonNode modern = controller.exchange(request(11, "initialize", Map.of(
                "protocolVersion", McpProtocolController.MODERN_PROTOCOL_VERSION)), new HttpHeaders()).getBody();
        JsonNode codex = controller.exchange(request(21, "initialize", Map.of(
                "protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "codex-mcp-client", "version", "0.150.0-alpha.8"))),
                new HttpHeaders()).getBody();
        JsonNode unknown = controller.exchange(request(12, "initialize", Map.of(
                "protocolVersion", "2099-01-01")), new HttpHeaders()).getBody();

        assertThat(modern.path("result").path("protocolVersion").asText())
                .isEqualTo(McpProtocolController.MODERN_PROTOCOL_VERSION);
        assertThat(modern.path("result").path("serverInfo").path("version").asText())
                .isEqualTo("1.4.6");
        assertThat(modern.path("result").path("instructions").asText())
                .startsWith("For a business Solution request");
        assertThat(modern.path("result").path("instructions").asText())
                .contains("rg.library.overview.get", "rg.feature.handoff", "rg.engineering.handoff",
                        "Never ask the business user for YAML", "businessSemantics",
                        "never invent a bindingRef", "five-panel business review",
                        "rg.dsl.reference.get", "never ask the user to write DSL",
                        "blocking=true as authoritative", "three repair rounds",
                        "same blocking set appears twice", "never refetch and resubmit unchanged source",
                        "BUSINESS_CLARIFICATION_REQUIRED", "business-language clarification",
                        "bind the case set to the toolRef returned by compose");
        assertThat(codex.path("result").path("protocolVersion").asText())
                .isEqualTo(McpProtocolController.CODEX_PROTOCOL_VERSION);
        assertThat(codex.path("result").path("serverInfo").path("version").asText())
                .isEqualTo("1.4.6");
        assertThat(unknown.path("id").asInt()).isEqualTo(12);
        assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void initializeReturnsInstructionsForTheRequestedSurfaceOnly() {
        McpProtocolController controller = new McpProtocolController(
                mapper, new McpToolCatalog(), mock(IntegrationRequestAuthenticator.class),
                (name, arguments, identity) -> Map.of());
        HttpHeaders businessHeaders = new HttpHeaders();
        businessHeaders.set("X-RG-Surface", "BUSINESS_SOLUTION");

        JsonNode response = controller.exchange(request(13, "initialize", Map.of(
                "protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION)), businessHeaders).getBody();

        assertThat(response.at("/result/instructions").asText())
                .contains("rg.library.overview.get")
                .doesNotContain("rg.dsl.reference.get", "rg.tool.compose");
    }

    private JsonNode request(int id, String method, Map<String, ?> params) {
        return mapper.valueToTree(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
    }

    private static HttpHeaders modernHeaders(String method, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("MCP-Protocol-Version", McpProtocolController.MODERN_PROTOCOL_VERSION);
        headers.set("Mcp-Method", method);
        if (name != null) {
            headers.set("Mcp-Name", name);
        }
        return headers;
    }

    private static IntegrationRequestContext identity() {
        return identity("AGENT_TDD_EXECUTION");
    }

    private static IntegrationRequestContext identity(String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", purpose, "corr-1");
    }

    private static GraphDraft decisionDraft() {
        return new GraphDraft(GraphDraft.SCHEMA_VERSION, "cancel-tool", 1, "cancelTool",
                "tenant-a", "project-a", "test", GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(new GraphDraft.DraftNode(
                "policy", "bloge:decisionTable", "Policy", Map.of(), Map.of(
                "rules", List.of(Map.of("id", "R4", "conditions", Map.of(
                        "seconds", "seconds <= 120"), "output", Map.of("decision", "WAIVE_FULL")))), null)),
                List.of(), Map.of(), Map.of(), new GraphDraft.OutputSelection("policy", ""),
                Map.of(), Map.of(), GraphDraft.RevisionMetadata.empty());
    }

    private static final class McpToolControllerProbe implements McpToolInvoker {
        private boolean called;

        @Override
        public Object invoke(String name, JsonNode arguments, IntegrationRequestContext identity) {
            called = true;
            return Map.of("ok", true, "diagnostics", java.util.List.of());
        }
    }
}
