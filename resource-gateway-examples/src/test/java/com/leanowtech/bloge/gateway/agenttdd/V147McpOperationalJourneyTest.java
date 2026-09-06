package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the v1.4.7 Codex operating path across platform Feature delivery and business Solution
 * correctness without granting protected material or real egress to the Agent-facing MCP surface.
 */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.authoring.local-schema-bootstrap.enabled=true",
                "gateway.integration.identity.environment-id=test",
                "gateway.testing.correctness.enabled=true",
                "gateway.testing.correctness.fixture-material.enabled=true",
                "gateway.testing.correctness.fixture-material.active-key-id=v147-operational",
                "gateway.testing.correctness.fixture-material.key-ring="
                        + "v147-operational=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "spring.datasource.url=jdbc:h2:mem:v147-mcp-operational;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class V147McpOperationalJourneyTest {
    private static final String AGENT_TOKEN = "bloge-aneke-demo-token";
    private static final String REVIEWER_TOKEN = "bloge-reviewer-demo-token";
    private static final String FEATURE_ENGINEER_TOKEN = "bloge-feature-engineer-demo-token";

    @Autowired private ObjectMapper mapper;
    @Autowired private TestRestTemplate http;
    private int requestId;

    @Test
    @Timeout(120)
    void codexDeliversControlledFeatureThenProvesProtectedBusinessSolution() {
        assertSurfaceSeparation();
        deliverControlledFeature();
        proveBusinessSolution();
    }

    /** Exercises compose, contract definition, protected suite execution, and engineering binding. */
    private void deliverControlledFeature() {
        String featureRef = "feature:eligibility-v147";
        String evaluationRef = featureRef;
        JsonNode composed = composeFeatureThroughAuthoringGate(featureRef, eligibilityDsl());
        assertThat(composed.at("/data/assetKind").asText()).isEqualTo("FEATURE");

        JsonNode overview = invokeBusiness("rg.library.overview.get",
                Map.of("includeSamples", false), "AGENT_TDD_READ");
        String authoringPatternsFingerprint = overview.at(
                "/data/authoringPatternsFingerprint").asText();
        JsonNode journey = invokeBusiness("rg.journey.start", Map.of(
                "intentKind", "CREATE_SOLUTION",
                "businessGoal", "为资格判断提供可验证的平台事实",
                "idempotencyKey", "journey-feature-eligibility-v147"), "AGENT_TDD_AUTHORING");
        String journeyRef = journey.at("/data/journeyRef").asText();

        JsonNode defined = invokeBusiness("rg.feature.define", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 1,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "featureYaml", """
                        feature:eligibility-v147:
                          output: { type: { fields: { eligible: boolean } } }
                          evaluationKind: DAG
                          determinism: DETERMINISTIC
                          inputs: { score: integer }
                          businessSemantics: 判断申请是否符合资格
                          display: { businessName: 资格判断, description: 根据评分判断资格 }
                          businessDefinition: { semanticKey: applicant.eligibility.v147, intent: 判断申请资格, domain: eligibility, businessObject: application, requiredContext: [], resultDomain: { type: object, eligible: boolean }, asOf: CURRENT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: PLATFORM, authoritySource: eligibility-policy, freshness: { mode: CURRENT }, effect: PURE }
                        """,
                "idempotencyKey", "define-feature-eligibility-v147"), "AGENT_TDD_AUTHORING");
        assertThat(defined.at("/data/speccing").asBoolean()).isTrue();

        JsonNode handoff = invokeBusiness("rg.feature.handoff", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 2,
                "featureRef", featureRef,
                "idempotencyKey", "handoff-feature-eligibility-v147"), "AGENT_TDD_AUTHORING");
        assertThat(handoff.at("/data/status").asText()).isEqualTo("OPEN");

        ResponseEntity<JsonNode> agentCannotFulfil = postAs(
                "/api/agent-tdd/feature-handoffs/" + featureRef + "/fulfil",
                Map.of("evaluationRef", evaluationRef), AGENT_TOKEN, "AGENT_TDD_FEATURE_ENG");
        assertThat(agentCannotFulfil.getStatusCode().value()).isEqualTo(403);

        JsonNode suite = invokePlatform(McpToolCatalog.FEATURE_SUITE_UPSERT, Map.of(
                "featureRef", featureRef,
                "evaluationRef", evaluationRef,
                "expectedRevision", 0,
                "libraryRefs", List.of(),
                "requiredCoverageTargets", List.of("node:policy"),
                "cases", List.of(Map.of(
                        "caseId", "eligible-score-720",
                        "intent", "评分达到门槛时符合资格",
                        "givenInputs", Map.of("score", 720),
                        "nodeBehaviors", List.of(),
                        "expectedOutput", Map.of("eligible", true),
                        "coverageTargets", List.of("node:policy")))), "AGENT_TDD_AUTHORING");
        assertThat(suite.at("/data/status").asText()).isEqualTo("DRAFT");
        assertPayloadFree(suite.at("/data"), "eligible-score-720", "720", "符合资格");

        JsonNode evidence = invokePlatform(McpToolCatalog.FEATURE_SUITE_RUN, Map.of(
                "featureRef", featureRef,
                "expectedRevision", suite.at("/data/revision").asLong()), "AGENT_TDD_EXECUTION");
        assertThat(evidence.at("/data/status").asText()).isEqualTo("PASSED");
        assertThat(evidence.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(evidence.at("/data/coverage/targetsCovered").asInt()).isEqualTo(1);
        assertPayloadFree(evidence.at("/data"), "eligible-score-720", "720", "符合资格");

        ResponseEntity<JsonNode> fulfilled = postAs(
                "/api/agent-tdd/feature-handoffs/" + featureRef + "/fulfil",
                Map.of("evaluationRef", evaluationRef,
                        "suiteEvidenceRef", evidence.at("/data/evidenceFingerprint").asText()),
                FEATURE_ENGINEER_TOKEN, "AGENT_TDD_FEATURE_ENG");
        assertThat(fulfilled.getStatusCode().is2xxSuccessful()).as(fulfilled.toString()).isTrue();
        assertThat(fulfilled.getBody().path("status").asText()).isEqualTo("VERIFIED");
        assertThat(fulfilled.getBody().path("verificationMode").asText())
                .isEqualTo("CONTROLLED_SUITE");
    }

    /** Exercises four-entity authoring, protected GOLDEN review, coverage, and GREEN baseline. */
    private void proveBusinessSolution() {
        String solutionRef = "sol:business-cancel-v147";
        String scenarioRef = "scn:business-cancel-v147";
        String instructionRef = "ins:business-uphold-v147";

        JsonNode overview = invokeBusiness("rg.library.overview.get",
                Map.of("includeSamples", false), "AGENT_TDD_READ");
        String authoringPatternsFingerprint = overview.at(
                "/data/authoringPatternsFingerprint").asText();
        assertThat(authoringPatternsFingerprint).startsWith("sha256:");

        JsonNode started = invokeBusiness("rg.journey.start", Map.of(
                "intentKind", "CREATE_SOLUTION",
                "businessGoal", "乘客超时取消时维持取消费并给出责任解释",
                "idempotencyKey", "journey-business-cancel-v147"), "AGENT_TDD_AUTHORING");
        String journeyRef = started.at("/data/journeyRef").asText();
        assertThat(started.at("/data/stage").asText()).isEqualTo("DEFINING_FEATURES");

        invokeBusiness("rg.feature.define", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 1,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "featureYaml", """
                        dispute.party.v147:
                          output: { type: { enum: [passenger, driver] } }
                          evaluationKind: USER_COMPONENT
                          determinism: INTERACTIVE
                          componentRef: cancellation-party-v147
                          businessSemantics: 取消责任方
                          display: { businessName: 取消责任方, description: 判断取消责任, aliases: [取消归责] }
                          businessDefinition: { semanticKey: ride.cancel.party.v147, intent: 判断取消责任, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: enum, values: [passenger, driver] }, asOf: CANCELLATION_OCCURRED_AT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: USER, freshness: { mode: AS_OF_EVENT }, effect: PURE }
                        """,
                "idempotencyKey", "feature-business-cancel-v147"), "AGENT_TDD_AUTHORING");
        invokeBusiness("rg.scenario.define", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 2,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "scenarioYaml", """
                        %s:
                          inputs: [party]
                          hitPolicy: unique
                          rules:
                            - ruleId: PASSENGER_LATE
                              when: { party: { eq: passenger } }
                              outlet: { kind: INSTRUCTION, ref: '%s', bind: { party: party } }
                          otherwise: { kind: TERMINAL, terminalKind: ESCALATE }
                          display: { businessName: 取消费争议判定, description: 根据取消责任选择处置 }
                          businessDefinition: { semanticKey: ride.cancel.decision.v147, intent: 判定取消费争议处置, domain: ride-cancellation, businessObject: ride-order, inputFactKeys: [ride.cancel.party.v147], decisionPolicy: UNIQUE, outletSemanticKeys: [ride.cancel.uphold.v147, ride.cancel.manual-review.v147], otherwisePolicy: ESCALATE }
                        """.formatted(scenarioRef, instructionRef),
                "libraryRefs", List.of(),
                "idempotencyKey", "scenario-business-cancel-v147"), "AGENT_TDD_AUTHORING");
        invokeBusiness("rg.instruction.define", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 3,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "instructionYaml", """
                        %s:
                          inputs: { party: string }
                          output:
                            result: { type: { fields: { decision: { enum: [UPHELD] } } } }
                            reasoning: required
                          effect: READ
                          businessSemantics: 维持取消费
                          display: { businessName: 维持取消费, description: 维持乘客取消费并解释原因 }
                          businessDefinition: { semanticKey: ride.cancel.uphold.v147, intent: 维持乘客取消费并解释原因, domain: ride-cancellation, businessObject: ride-order, requiredFactKeys: [ride.cancel.party.v147], resultDomain: { type: object, decision: { enum: [UPHELD] } }, reasoningPolicy: REQUIRED, effect: READ, failurePolicy: ESCALATE, writeGovernanceClass: NONE }
                        """.formatted(instructionRef),
                "idempotencyKey", "instruction-business-cancel-v147"), "AGENT_TDD_AUTHORING");

        JsonNode composing = invokeBusiness("rg.journey.next", Map.of(
                "journeyRef", journeyRef,
                "expectedRevision", 4), "AGENT_TDD_READ");
        String solutionContextFingerprint = composing.at(
                "/data/solutionContextFingerprint").asText();
        assertThat(composing.at("/data/stage").asText()).isEqualTo("COMPOSING");

        invokeBusiness("rg.solution.compose", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 4,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "solutionContextFingerprint", solutionContextFingerprint,
                "solutionYaml", """
                        %s:
                          problem: 处理乘客超时取消费争议。
                          inputs: { party: dispute.party.v147 }
                          scenarioTree: { root: '%s' }
                          instructions: ['%s']
                          golden: 'caseSet:business-cancel-v147'
                          display: { businessName: 取消费争议处理, description: 处理乘客超时取消费争议 }
                          businessDefinition: { semanticKey: ride.cancel.solution.v147, intent: 处理乘客超时取消费争议, domain: ride-cancellation, businessObject: ride-order, problemClass: CANCELLATION_FEE_DISPUTE, requiredFactKeys: [ride.cancel.party.v147], scenarioSemanticKey: ride.cancel.decision.v147, dispositionSemanticKeys: [ride.cancel.uphold.v147, ride.cancel.manual-review.v147], runtimeUse: GOVERNED_DECISION }
                        """.formatted(solutionRef, scenarioRef, instructionRef),
                "idempotencyKey", "compose-business-cancel-v147"), "AGENT_TDD_AUTHORING");

        JsonNode proposed = invokeBusiness("rg.solution.golden.propose", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 5,
                "solutionRef", solutionRef,
                "cases", List.of(Map.of(
                        "caseId", "late-cancel-v147",
                        "businessIntent", "乘客超时取消由乘客承担",
                        "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                        "dependencyAssumptions", List.of(Map.of(
                                "capabilityName", "维持取消费",
                                "outcome", "RETURNS",
                                "value", Map.of("result", Map.of("decision", "UPHELD"),
                                        "reasoning", "责任在乘客"))),
                        "expectedOutcome", Map.of("result", Map.of("decision", "UPHELD"),
                                "reasoningClass", "责任在乘客"),
                        "oracleOwner", "customer-experience-owner")),
                "idempotencyKey", "golden-business-cancel-v147"), "AGENT_TDD_AUTHORING");
        String caseSetRef = proposed.at("/data/caseSetRef").asText();
        assertPayloadFree(proposed.at("/data"), "passenger", "UPHELD", "责任在乘客");

        ResponseEntity<JsonNode> material = getAs(
                "/api/solution/golden-review/" + solutionRef
                        + "/cases/late-cancel-v147/material?journeyRef=" + journeyRef,
                REVIEWER_TOKEN, "SOLUTION_GOLDEN_REVIEW");
        assertThat(material.getStatusCode().is2xxSuccessful()).as(material.toString()).isTrue();
        assertThat(material.getHeaders().getCacheControl()).contains("no-store", "private");
        assertThat(material.getBody().path("businessIntent").asText())
                .isEqualTo("乘客超时取消由乘客承担");
        assertThat(material.getBody().at("/givenFacts/0/value").asText()).isEqualTo("passenger");
        assertThat(material.getBody().at("/expectedOutcome/result/decision").asText())
                .isEqualTo("UPHELD");
        assertThat(material.getBody().has("stubs")).isFalse();

        JsonNode approvalMaterial = getAs(
                "/api/agent-tdd/reviews/oracles/" + caseSetRef
                        + "/late-cancel-v147?expectedRevision=1",
                REVIEWER_TOKEN, "AGENT_TDD_GOVERNANCE").getBody();
        ResponseEntity<JsonNode> approved = postAs(
                "/api/agent-tdd/reviews/oracles/" + caseSetRef
                        + "/late-cancel-v147/approve",
                Map.of("expectedRevision", 1,
                        "proposalFingerprint", approvalMaterial.path("proposalFingerprint").asText()),
                REVIEWER_TOKEN, "AGENT_TDD_GOVERNANCE");
        assertThat(approved.getStatusCode().is2xxSuccessful()).as(approved.toString()).isTrue();
        assertThat(approved.getBody().path("status").asText()).isEqualTo("APPROVED");

        JsonNode coverage = invokeBusiness(McpToolCatalog.SOLUTION_COVERAGE,
                Map.of("solutionRef", solutionRef), "AGENT_TDD_READ");
        assertThat(coverage.at("/data/obligations")).isNotEmpty();
        assertThat(coverage.at("/data/obligations")).allSatisfy(obligation -> {
            assertThat(fieldNames(obligation)).containsExactlyInAnyOrder(
                    "obligationFingerprint", "dimension", "risk", "covered");
            assertThat(obligation.path("obligationFingerprint").asText()).startsWith("sha256:");
        });
        assertThat(coverage.at("/data/summary/covered").asInt()).isGreaterThanOrEqualTo(1);
        assertPayloadFree(coverage.at("/data"), "PASSENGER_LATE", "late-cancel-v147",
                "passenger", "UPHELD", "责任在乘客");

        JsonNode testing = invokeBusiness("rg.journey.next", Map.of(
                "journeyRef", journeyRef,
                "expectedRevision", 6), "AGENT_TDD_READ");
        assertThat(testing.at("/data/stage").asText()).isEqualTo("TESTING");
        JsonNode green = invokeBusiness("rg.solution.baseline", Map.of(
                "journeyRef", journeyRef,
                "expectedJourneyRevision", 6,
                "solutionRef", solutionRef,
                "side", "GREEN"), "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(green.at("/data/egressPolicy").asText()).isEqualTo("DENY_ALL");
    }

    private JsonNode composeFeatureThroughAuthoringGate(String featureRef, String dsl) {
        JsonNode reference = invokePlatform("rg.dsl.reference.get",
                Map.of("libraryRefs", List.of(), "includeExamples", true), "AGENT_TDD_READ");
        String contextFingerprint = reference.at("/data/authoringContextFingerprint").asText();
        Map<String, Object> request = Map.of(
                "source", Map.of("sourceId", featureRef + ".bloge", "dsl", dsl),
                "libraryRefs", List.of(),
                "authoringContextFingerprint", contextFingerprint);
        JsonNode preview = invokePlatform("rg.dsl.preview", request, "AGENT_TDD_READ");
        assertThat(preview.at("/data/accepted").asBoolean()).as(preview.toPrettyString()).isTrue();
        JsonNode gate = invokePlatform("rg.gate.check", request, "AGENT_TDD_READ");
        assertThat(gate.at("/data/accepted").asBoolean()).as(gate.toPrettyString()).isTrue();
        return invokePlatform("rg.feature.compose", Map.of(
                "featureRef", featureRef,
                "graph", Map.of("sourceId", featureRef + ".bloge", "dsl", dsl),
                "libraryRefs", List.of(),
                "authoringContextFingerprint", contextFingerprint,
                "authoringReceiptFingerprint", gate.at("/data/authoringReceiptFingerprint").asText(),
                "idempotencyKey", "compose-feature-eligibility-v147"), "AGENT_TDD_AUTHORING");
    }

    private void assertSurfaceSeparation() {
        assertThat(toolNames("PLATFORM_AUTHORING", "AGENT_TDD_AUTHORING"))
                .contains(McpToolCatalog.FEATURE_SUITE_UPSERT)
                .doesNotContain(McpToolCatalog.SOLUTION_COVERAGE);
        assertThat(toolNames("PLATFORM_AUTHORING", "AGENT_TDD_EXECUTION"))
                .contains(McpToolCatalog.FEATURE_SUITE_RUN)
                .doesNotContain(McpToolCatalog.SOLUTION_COVERAGE);
        assertThat(toolNames("PLATFORM_AUTHORING", "AGENT_TDD_READ"))
                .contains(McpToolCatalog.FEATURE_SUITE_GET)
                .doesNotContain(McpToolCatalog.SOLUTION_COVERAGE);
        assertThat(toolNames("BUSINESS_SOLUTION", "AGENT_TDD_READ"))
                .contains(McpToolCatalog.SOLUTION_COVERAGE)
                .doesNotContain(McpToolCatalog.FEATURE_SUITE_UPSERT,
                        McpToolCatalog.FEATURE_SUITE_RUN, McpToolCatalog.FEATURE_SUITE_GET);
    }

    private Set<String> toolNames(String surface, String purpose) {
        HttpHeaders headers = headers(AGENT_TOKEN, purpose);
        headers.set("X-RG-Surface", surface);
        JsonNode initialize = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "codex", "version", "0.150.0"))));
        ResponseEntity<JsonNode> initialized = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(initialize, headers), JsonNode.class);
        assertThat(initialized.getStatusCode().is2xxSuccessful()).as(initialized.toString()).isTrue();
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        JsonNode list = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "tools/list",
                "params", Map.of()));
        ResponseEntity<JsonNode> listed = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(list, headers), JsonNode.class);
        assertThat(listed.getStatusCode().is2xxSuccessful()).as(listed.toString()).isTrue();
        return StreamSupport.stream(listed.getBody().at("/result/tools").spliterator(), false)
                .map(tool -> tool.path("name").asText())
                .collect(Collectors.toSet());
    }

    private JsonNode invokePlatform(String name, Object arguments, String purpose) {
        return invoke(name, arguments, purpose, "PLATFORM_AUTHORING");
    }

    private JsonNode invokeBusiness(String name, Object arguments, String purpose) {
        return invoke(name, arguments, purpose, "BUSINESS_SOLUTION");
    }

    private JsonNode invoke(String name, Object arguments, String purpose, String surface) {
        JsonNode request = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "tools/call",
                "params", Map.of("name", name, "arguments", arguments)));
        HttpHeaders headers = headers(AGENT_TOKEN, purpose);
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        if (surface != null) headers.set("X-RG-Surface", surface);
        ResponseEntity<JsonNode> response = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        JsonNode result = response.getBody().at("/result/structuredContent");
        assertThat(result.path("ok").asBoolean()).as(result.toPrettyString()).isTrue();
        return result;
    }

    private ResponseEntity<JsonNode> getAs(String path, String token, String purpose) {
        return http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(token, purpose)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> postAs(
            String path, Object body, String token, String purpose) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(mapper.valueToTree(body), headers(token, purpose)), JsonNode.class);
    }

    private static HttpHeaders headers(String token, String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Purpose", purpose);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private static Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(
                        ((Iterable<String>) () -> node.fieldNames()).spliterator(), false)
                .collect(Collectors.toSet());
    }

    private static void assertPayloadFree(JsonNode node, String... protectedValues) {
        assertThat(node.toString()).doesNotContain(protectedValues);
    }

    private static String eligibilityDsl() {
        return """
                graph controlledEligibility {
                  input { score: Int }
                  decision_table policy(score = ctx.score) hit=first -> { eligible: Boolean } {
                    rule (score: score >= 700) -> { eligible: true }
                    otherwise -> { eligible: false }
                  }
                  transform response { eligible = policy.output.eligible }
                }
                """;
    }
}
