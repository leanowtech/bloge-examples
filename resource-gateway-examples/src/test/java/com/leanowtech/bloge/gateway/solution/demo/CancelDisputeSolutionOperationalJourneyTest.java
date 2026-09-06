package com.leanowtech.bloge.gateway.solution.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.agenttdd.McpProtocolController;
import com.leanowtech.bloge.gateway.agenttdd.AgentTddStateRepository;
import com.leanowtech.bloge.gateway.agenttdd.EngineeringHandoffService;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Certifies the v1.4.5 cancellation-dispute journey through real MCP HTTP, human browser review,
 * deterministic Feature adapters, controlled writes, reconciliation, publication and live reuse.
 *
 * <p>The only downstream systems are the explicitly enabled in-memory demo ledger. The GREEN
 * baseline therefore proves zero egress while the later controlled-write phase still exercises
 * the production reservation and reconciliation boundaries.</p>
 */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.authoring.local-schema-bootstrap.enabled=true",
                "gateway.agent-tdd.cancel-dispute-demo.enabled=true",
                "gateway.integration.identity.environment-id=test",
                "spring.datasource.url=jdbc:h2:mem:cancel-dispute-v145;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
class CancelDisputeSolutionOperationalJourneyTest {
    private static final String SOLUTION = "sol:cancel-dispute-v145";
    private static final String SCENARIO = "scn:cancel-dispute-v145";
    private static final String CASE_SET = "caseSet:cancel-dispute-v145";
    private static final String AGENT_TOKEN = "bloge-aneke-demo-token";
    private static final String REVIEWER_TOKEN = "bloge-reviewer-demo-token";
    private static final String FEATURE_ENGINEER_TOKEN = "bloge-feature-engineer-demo-token";
    private static final String INSTRUCTION_ENGINEER_TOKEN = "bloge-instruction-engineer-demo-token";

    @Autowired private ObjectMapper mapper;
    @Autowired private TestRestTemplate http;
    @Autowired private AgentTddStateRepository states;
    @Autowired private CancelDisputeDemoLedger ledger;
    @LocalServerPort private int port;
    private int requestId;

    @Test
    @Timeout(120)
    void businessIntentReachesPublishedRuntimeAndOperatingFeedbackWithoutNetworkEgress() throws Exception {
        negotiateCodexLifecycle();
        defineFeatures();

        JsonNode partyHandoff = invoke("rg.feature.handoff", Map.of(
                "featureRef", "responsibility.party.v145",
                "idempotencyKey", "handoff-party-v145"), "AGENT_TDD_AUTHORING");
        JsonNode freeHandoff = invoke("rg.feature.handoff", Map.of(
                "featureRef", "cancel.withinFree.v145",
                "idempotencyKey", "handoff-free-v145"), "AGENT_TDD_AUTHORING");
        assertThat(partyHandoff.at("/data/status").asText()).isEqualTo("OPEN");
        assertThat(freeHandoff.at("/data/status").asText()).isEqualTo("OPEN");

        ResponseEntity<JsonNode> agentCannotFulfil = postAs(
                "/api/agent-tdd/feature-handoffs/responsibility.party.v145/fulfil",
                Map.of("evaluationRef", RideResponsibilityBackend.EVALUATION_REF,
                        "fixtureInputs", Map.of("orderId", "O-FREE-NONE")),
                AGENT_TOKEN, "AGENT_TDD_FEATURE_ENG");
        assertThat(agentCannotFulfil.getStatusCode().value()).isEqualTo(403);

        JsonNode fulfilledParty = fulfil("responsibility.party.v145",
                RideResponsibilityBackend.EVALUATION_REF);
        JsonNode fulfilledFree = fulfil("cancel.withinFree.v145",
                CancelWithinFreeBackend.EVALUATION_REF);
        assertThat(fulfilledParty.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(fulfilledFree.path("status").asText()).isEqualTo("VERIFIED");

        defineInstructions();
        invoke("rg.scenario.define", Map.of(
                "scenarioYaml", scenarioYaml(), "libraryRefs", List.of(),
                "idempotencyKey", "scenario-cancel-v145"), "AGENT_TDD_AUTHORING");
        JsonNode composed = invoke("rg.solution.compose", Map.of(
                "solutionYaml", solutionYaml(),
                "authoringContextFingerprint", "sha256:cancel-v145-business-context",
                "idempotencyKey", "compose-cancel-v145"), "AGENT_TDD_AUTHORING");
        assertThat(composed.at("/data/speccing").asBoolean()).isTrue();

        JsonNode engineering = invoke("rg.engineering.handoff", Map.of(
                "solutionRef", SOLUTION, "idempotencyKey", "engineering-cancel-v145"),
                "AGENT_TDD_AUTHORING");
        assertThat(engineering.at("/data/items")).hasSize(2);
        ResponseEntity<JsonNode> agentCannotBind = postAs(
                "/api/agent-tdd/engineering-handoffs/" + SOLUTION
                        + "/instructions/ins:refund-waive-full-v145/fulfil",
                Map.of("bindingRef", "demo:refund-waive-full-v1"),
                AGENT_TOKEN, "AGENT_TDD_INSTRUCTION_ENG");
        assertThat(agentCannotBind.getStatusCode().value()).isEqualTo(403);
        assertThat(fulfilInstruction(
                "ins:refund-waive-full-v145", "demo:refund-waive-full-v1")
                .path("status").asText()).isEqualTo("OPEN");
        assertThat(fulfilInstruction(
                "ins:escalate-human-v145", "demo:escalate-human-ticket-v1")
                .path("status").asText()).isEqualTo("IMPLEMENTED");

        JsonNode cases = invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", CASE_SET, "toolRef", SOLUTION, "rows", goldenCases(),
                "idempotencyKey", "cases-cancel-v145"), "AGENT_TDD_AUTHORING");
        long revision = cases.at("/data/revision").asLong();
        for (String caseId : List.of("G1-free-none", "G2-driver", "G3-passenger", "G4-platform")) {
            JsonNode detail = reviewGet("/api/agent-tdd/reviews/oracles/" + CASE_SET + "/" + caseId
                    + "?expectedRevision=" + revision);
            assertThat(detail.has("intent")).isTrue();
            assertThat(detail.has("given")).isTrue();
            assertThat(detail.has("stubs")).isTrue();
            assertThat(detail.has("expect")).isTrue();
            assertThat(detail.has("businessIntent")).isFalse();
            JsonNode approved = reviewPost("/api/agent-tdd/reviews/oracles/" + CASE_SET + "/"
                    + caseId + "/approve", Map.of("expectedRevision", revision,
                    "proposalFingerprint", detail.path("proposalFingerprint").asText()));
            revision = approved.path("revision").asLong();
        }

        JsonNode green = invoke("rg.solution.baseline", Map.of(
                "solutionRef", SOLUTION, "caseSetRef", CASE_SET, "side", "GREEN"),
                "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(green.at("/data/writeReconciliation/status").asText()).isEqualTo("RECONCILED");
        assertThat(green.at("/data/writeReconciliation/writeCount").asInt()).isEqualTo(2);
        assertThat(green.at("/data/cases")).allSatisfy(row ->
                assertThat(row.path("verdict").asText()).isEqualTo("GREEN_PASS"));

        JsonNode proposal = invoke("rg.solution.commit", Map.of(
                "solutionRef", SOLUTION,
                "authoringReceiptFingerprint", composed.at("/data/authoringReceiptFingerprint").asText(),
                "idempotencyKey", "commit-cancel-v145"), "AGENT_TDD_AUTHORING");
        assertThat(proposal.at("/data/proposalStatus").asText()).isEqualTo("PENDING");

        assertThat(ledger.refund("O-FREE-NONE")).containsEntry("decision", "WAIVED");
        assertThat(ledger.ticket("O-PLATFORM")).containsEntry("decision", "ESCALATED");
        assertThat(states.find("tenant-a|knowledge-governance|tool-studio|test|local",
                EngineeringHandoffService.HANDOFF, SOLUTION))
                .hasValueSatisfying(asset -> assertThat(asset.data().path("status").asText())
                        .isEqualTo("CLOSED"));

        signOffAfterInspectingFiveBusinessPanelsInChrome();
        JsonNode readiness = invoke("rg.solution.readiness", Map.of("solutionRef", SOLUTION));
        assertThat(readiness.at("/data/publishable").asBoolean()).isTrue();
        JsonNode published = invoke("rg.solution.publish", Map.of(
                "solutionRef", SOLUTION, "signoffRef", "change-cancel-v145",
                "idempotencyKey", "publish-cancel-v145"), "AGENT_TDD_GOVERNANCE");
        assertThat(published.at("/data/artifactKind").asText()).isEqualTo("SOLUTION");

        JsonNode party = evaluate("responsibility.party.v145", "O-FREE-NONE");
        JsonNode withinFree = evaluate("cancel.withinFree.v145", "O-FREE-NONE");
        Map<String, Object> envelopes = new LinkedHashMap<>();
        envelopes.put("party", evaluatedEnvelope(party, "O-FREE-NONE"));
        envelopes.put("withinFree", evaluatedEnvelope(withinFree, "O-FREE-NONE"));
        envelopes.put("orderId", Map.of("value", "O-FREE-NONE", "source", "USER"));
        JsonNode invoked = invoke("rg.solution.invoke", Map.of(
                "solutionRef", SOLUTION, "inputs", envelopes,
                "idempotencyKey", "invoke-cancel-v145"), "AGENT_TDD_EXECUTION");
        assertThat(invoked.at("/data/result/decision").asText()).isEqualTo("WAIVED");
        assertThat(invoked.at("/data/verifiedFeatureCount").asInt()).isEqualTo(2);

        JsonNode replay = invoke("rg.solution.invoke", Map.of(
                "solutionRef", SOLUTION, "inputs", envelopes,
                "idempotencyKey", "invoke-cancel-v145"), "AGENT_TDD_EXECUTION");
        assertThat(replay.at("/data/publicationId")).isEqualTo(invoked.at("/data/publicationId"));
        JsonNode performance = invoke("rg.solution.performance", Map.of("solutionRef", SOLUTION));
        assertThat(performance.at("/data/totalInvocations").asInt()).isEqualTo(1);
        assertThat(performance.at("/data/hitDistribution/0/ruleId").asText()).isEqualTo("R1");
        assertThat(performance.toString()).doesNotContain("O-FREE-NONE", "WAIVED", "reasoning");

        Map<String, Object> tampered = new LinkedHashMap<>(envelopes);
        tampered.put("party", Map.of("value", "driver", "inputs", Map.of("orderId", "O-FREE-NONE"),
                "evaluationToken", party.at("/data/evaluationToken").asText()));
        JsonNode rejected = invokeFailure("rg.solution.invoke", Map.of(
                "solutionRef", SOLUTION, "inputs", tampered,
                "idempotencyKey", "invoke-cancel-v145-tampered"), "AGENT_TDD_EXECUTION");
        assertThat(rejected.at("/error/code").asText()).isEqualTo("FEATURE_TOKEN_INVALID");
    }

    private void defineFeatures() {
        invoke("rg.feature.define", Map.of("featureYaml", """
                responsibility.party.v145:
                  output: { type: { enum: [none, driver, passenger, platform] } }
                  evaluationKind: API
                  determinism: DETERMINISTIC
                  inputs: { orderId: string }
                  businessSemantics: 谁应承担本次取消费用
                  display: { businessName: 取消责任方, description: 判断本次取消责任, aliases: [取消归责] }
                  businessDefinition: { semanticKey: ride.cancel.party, intent: 判断取消责任, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: enum }, asOf: CANCELLATION_OCCURRED_AT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: PLATFORM, authoritySource: responsibility-center, freshness: { mode: AS_OF_EVENT }, effect: READ }
                """, "idempotencyKey", "feature-party-v145"), "AGENT_TDD_AUTHORING");
        invoke("rg.feature.define", Map.of("featureYaml", """
                cancel.withinFree.v145:
                  output: { type: boolean }
                  evaluationKind: DAG
                  determinism: DETERMINISTIC
                  inputs: { orderId: string }
                  businessSemantics: 当前订单是否仍在免费取消时段内
                  display: { businessName: 免费取消时段, description: 判断当前订单是否在免费取消时段内 }
                  businessDefinition: { semanticKey: ride.cancel.within-free, intent: 判断免费取消时段, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: boolean }, asOf: CANCELLATION_OCCURRED_AT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: PLATFORM, authoritySource: order-policy, freshness: { mode: AS_OF_EVENT }, effect: READ }
                """, "idempotencyKey", "feature-free-v145"), "AGENT_TDD_AUTHORING");
        invoke("rg.feature.define", Map.of("featureYaml", """
                dispute.order.v145:
                  output: { type: string }
                  evaluationKind: USER_COMPONENT
                  determinism: INTERACTIVE
                  componentRef: order-picker-v1
                  businessSemantics: 客服当前选择的争议订单
                  display: { businessName: 争议订单, description: 选择当前需要处理的争议订单 }
                  businessDefinition: { semanticKey: ride.dispute.order, intent: 选择争议订单, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: string }, asOf: CURRENT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: USER, freshness: { mode: CURRENT }, effect: PURE }
                """, "idempotencyKey", "feature-order-v145"), "AGENT_TDD_AUTHORING");
    }

    private void defineInstructions() {
        defineInstruction("ins:refund-waive-full-v145", "WAIVED", "refund-service", "recon:refund-v1",
                "", "instruction-refund-v145-design");
        defineInstruction("ins:escalate-human-v145", "ESCALATED", "ticket-service", "recon:ticket-v1",
                "", "instruction-ticket-v145-design");
        invoke("rg.instruction.define", Map.of("instructionYaml", """
                ins:uphold-v145:
                  inputs: { orderId: string }
                  output:
                    result: { type: { fields: { decision: { enum: [UPHELD] } } } }
                    reasoning: required
                  businessSemantics: 维持原收费
                  effect: READ
                  bindingRef: 'demo:uphold-v1'
                """,
                "idempotencyKey", "instruction-uphold-v145-library-binding"),
                "AGENT_TDD_AUTHORING");
    }

    private void defineInstruction(String ref, String decision, String downstream,
                                   String adapter, String bindingRef, String key) {
        String binding = bindingRef.isBlank() ? "" : "  bindingRef: '" + bindingRef + "'\n";
        invoke("rg.instruction.define", Map.of("instructionYaml", """
                %s:
                  inputs: { orderId: string }
                  output:
                    result: { type: { fields: { decision: { enum: [%s] } } } }
                    reasoning: required
                  businessSemantics: %s
                  effect: WRITE
                %s  writeGovernance:
                    downstreamSystem: %s
                    reconciliationKey: orderId
                    reconciliationAdapterRef: %s
                """.formatted(ref, decision,
                "WAIVED".equals(decision) ? "全额免除取消费" : "转人工复核",
                binding, downstream, adapter),
                "idempotencyKey", key), "AGENT_TDD_AUTHORING");
    }

    private static String scenarioYaml() {
        return """
                scn:cancel-dispute-v145:
                  inputs: [party, withinFree]
                  hitPolicy: unique
                  rules:
                    - ruleId: R1
                      when: { party: { eq: none }, withinFree: { eq: true } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:refund-waive-full-v145', bind: { orderId: orderId } }
                    - ruleId: R2
                      when: { party: { eq: driver } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:uphold-v145', bind: { orderId: orderId } }
                    - ruleId: R3
                      when: { party: { eq: passenger } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:uphold-v145', bind: { orderId: orderId } }
                    - ruleId: R4
                      when: { party: { eq: platform } }
                      outlet: { kind: INSTRUCTION, ref: 'ins:escalate-human-v145', bind: { orderId: orderId } }
                  otherwise: { kind: INSTRUCTION, ref: 'ins:escalate-human-v145', bind: { orderId: orderId } }
                """;
    }

    private static String solutionYaml() {
        return """
                sol:cancel-dispute-v145:
                  problem: 一致处理取消费纠纷，并把平台责任退款与例外升级分开。
                  inputs:
                    party: responsibility.party.v145
                    withinFree: cancel.withinFree.v145
                    orderId: dispute.order.v145
                  scenarioTree: { root: 'scn:cancel-dispute-v145' }
                  instructions: ['ins:refund-waive-full-v145', 'ins:uphold-v145', 'ins:escalate-human-v145']
                  golden: 'caseSet:cancel-dispute-v145'
                """;
    }

    private static List<Map<String, Object>> goldenCases() {
        return List.of(
                golden("G1-free-none", "none", true, "O-FREE-NONE", "WAIVED", "免费时段内且无人担责时全额免除"),
                golden("G2-driver", "driver", false, "O-DRIVER", "UPHELD", "司机责任时维持原收费"),
                golden("G3-passenger", "passenger", false, "O-PASSENGER", "UPHELD", "乘客责任时维持原收费"),
                golden("G4-platform", "platform", false, "O-PLATFORM", "ESCALATED", "平台责任争议转人工复核"));
    }

    private static Map<String, Object> golden(String id, String party, boolean free,
                                               String orderId, String decision, String intent) {
        Map<String, Object> stubs = "UPHELD".equals(decision)
                ? Map.of("ins:uphold-v145", Map.of(
                "behavior", "RETURN", "value", Map.of(
                        "result", Map.of("decision", "UPHELD"),
                        "reasoning", "受控标本要求维持原收费")))
                : Map.of();
        return Map.of("caseId", id, "category", "GOLDEN", "layer", "integration",
                "given", Map.of("party", party, "withinFree", free, "orderId", orderId),
                "stubs", stubs, "expect", Map.of("result", Map.of("decision", decision)),
                "intent", intent, "oracleOwner", "customer-experience-owner");
    }

    private JsonNode fulfil(String featureRef, String evaluationRef) {
        ResponseEntity<JsonNode> response = postAs("/api/agent-tdd/feature-handoffs/" + featureRef
                + "/fulfil", Map.of("evaluationRef", evaluationRef,
                "fixtureInputs", Map.of("orderId", "O-FREE-NONE")),
                FEATURE_ENGINEER_TOKEN, "AGENT_TDD_FEATURE_ENG");
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private JsonNode fulfilInstruction(String instructionRef, String bindingRef) {
        ResponseEntity<JsonNode> response = postAs(
                "/api/agent-tdd/engineering-handoffs/" + SOLUTION + "/instructions/"
                        + instructionRef + "/fulfil",
                Map.of("bindingRef", bindingRef), INSTRUCTION_ENGINEER_TOKEN,
                "AGENT_TDD_INSTRUCTION_ENG");
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private JsonNode evaluate(String featureRef, String orderId) {
        return invoke("rg.feature.evaluate", Map.of(
                "featureRef", featureRef, "inputs", Map.of("orderId", orderId)),
                "AGENT_TDD_EXECUTION");
    }

    private static Map<String, Object> evaluatedEnvelope(JsonNode evaluation, String orderId) {
        return Map.of("value", evaluation.at("/data/value"), "inputs", Map.of("orderId", orderId),
                "evaluationToken", evaluation.at("/data/evaluationToken").asText());
    }

    private void signOffAfterInspectingFiveBusinessPanelsInChrome() throws Exception {
        Path driver = chromeDriver();
        assertThat(driver).as("ChromeDriver is mandatory for the v1.4.5 browser acceptance").isNotNull();
        System.setProperty("webdriver.chrome.driver", driver.toString());
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--disable-dev-shm-usage",
                "--no-sandbox", "--remote-debugging-pipe", "--window-size=1280,1000");
        Path macChrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        if (Files.isExecutable(macChrome)) options.setBinary(macChrome.toString());
        ChromeDriver browser = new ChromeDriver(options);
        try {
            WebDriverWait wait = new WebDriverWait(browser, Duration.ofSeconds(15));
            browser.get("http://localhost:" + port + "/agent-tdd.html");
            wait.until(ExpectedConditions.elementToBeClickable(By.id("token"))).sendKeys(REVIEWER_TOKEN);
            browser.findElement(By.id("load")).click();
            By boardButton = By.cssSelector("button[data-solution-board='" + SOLUTION + "']");
            wait.until(ExpectedConditions.elementToBeClickable(boardButton)).click();
            wait.until(ExpectedConditions.attributeToBe(By.id("businessReview"), "open", "true"));
            assertThat(browser.findElement(By.id("businessReviewBody")).getText())
                    .contains("规则矩阵", "处置清单", "判断依据", "红绿验证板", "发布卡")
                    .contains("谁应承担本次取消费用", "G1-free-none", "全额免除");
            browser.findElement(By.id("closeBusinessReview")).click();

            By signoff = By.xpath("//div[contains(@class,'review') and contains(.,'" + SOLUTION + "')]/button");
            wait.until(ExpectedConditions.elementToBeClickable(signoff)).click();
            Alert detail = wait.until(ExpectedConditions.alertIsPresent());
            assertThat(detail.getText()).contains("取消费纠纷", "implementationFingerprint");
            detail.accept();
            Alert reference = wait.until(ExpectedConditions.alertIsPresent());
            reference.sendKeys("change-cancel-v145");
            reference.accept();
            wait.until(ExpectedConditions.invisibilityOfElementLocated(signoff));
        } finally {
            browser.quit();
        }
    }

    private void negotiateCodexLifecycle() {
        HttpHeaders headers = headers(AGENT_TOKEN, "AGENT_TDD_READ");
        JsonNode initialized = exchange(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "initialize", "params", Map.of(
                "protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION,
                "capabilities", Map.of(), "clientInfo", Map.of("name", "codex", "version", "0.150"))),
                headers).getBody();
        assertThat(initialized.at("/result/protocolVersion").asText())
                .isEqualTo(McpProtocolController.CODEX_PROTOCOL_VERSION);
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        ResponseEntity<JsonNode> notification = exchange(Map.of("jsonrpc", "2.0",
                "method", "notifications/initialized", "params", Map.of()), headers);
        assertThat(notification.getStatusCode().value()).isEqualTo(202);
        JsonNode tools = exchange(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "tools/list", "params", Map.of()), headers).getBody();
        assertThat(tools.at("/result/tools")).anySatisfy(tool ->
                assertThat(tool.path("name").asText()).isEqualTo("rg.feature.handoff"));
        assertThat(tools.at("/result/tools").toString()).doesNotContain("fulfil", "FEATURE_ENG");
    }

    private JsonNode invoke(String name, Object arguments) {
        return invoke(name, arguments, "AGENT_TDD_READ");
    }

    private JsonNode invoke(String name, Object arguments, String purpose) {
        JsonNode response = invokeRaw(name, arguments, purpose);
        JsonNode structured = response.at("/result/structuredContent");
        assertThat(structured.path("ok").asBoolean()).as(response.toPrettyString()).isTrue();
        return structured;
    }

    private JsonNode invokeFailure(String name, Object arguments, String purpose) {
        JsonNode response = invokeRaw(name, arguments, purpose);
        JsonNode structured = response.at("/result/structuredContent");
        assertThat(structured.path("ok").asBoolean()).as(response.toPrettyString()).isFalse();
        return structured;
    }

    private JsonNode invokeRaw(String name, Object arguments, String purpose) {
        HttpHeaders headers = headers(AGENT_TOKEN, purpose);
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        ResponseEntity<JsonNode> response = exchange(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)), headers);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchange(Object body, HttpHeaders headers) {
        return http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(mapper.valueToTree(body), headers), JsonNode.class);
    }

    private JsonNode reviewGet(String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers(REVIEWER_TOKEN, "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private JsonNode reviewPost(String path, Object body) {
        ResponseEntity<JsonNode> response = postAs(path, body, REVIEWER_TOKEN, "AGENT_TDD_GOVERNANCE");
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private ResponseEntity<JsonNode> postAs(String path, Object body, String token, String purpose) {
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

    private static Path chromeDriver() throws Exception {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank() && Files.isExecutable(Path.of(configured))) return Path.of(configured);
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "selenium", "chromedriver");
        if (!Files.isDirectory(cache)) return null;
        try (var paths = Files.find(cache, 4, (path, attributes) ->
                attributes.isRegularFile() && "chromedriver".equals(path.getFileName().toString())
                        && Files.isExecutable(path))) {
            return paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
        }
    }
}
