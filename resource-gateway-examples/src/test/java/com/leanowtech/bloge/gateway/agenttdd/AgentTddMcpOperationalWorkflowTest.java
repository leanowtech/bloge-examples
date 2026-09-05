package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.ResourceGatewayApplication;
import com.leanowtech.bloge.gateway.gateway.GatewayProperties;
import com.leanowtech.bloge.gateway.gateway.ResourceDescriptorBootstrap;
import com.leanowtech.bloge.gateway.resource.WritableResourceRegistry;
import com.leanowtech.bloge.gateway.solution.InstructionDispatchChannel;
import com.leanowtech.bloge.gateway.solution.ReconciliationAdapter;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.validation.VisualSchemaCompatibility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openqa.selenium.Alert;
import org.openqa.selenium.By;
import org.openqa.selenium.Dimension;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the documented A0-A5 Codex MCP journey against real HTTP, browser, and Spring services. */
@SpringBootTest(
        classes = ResourceGatewayApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "gateway.seed-descriptors=false",
                "gateway.authoring.local-schema-bootstrap.enabled=true",
                "gateway.integration.identity.environment-id=test",
                "gateway.testing.correctness.enabled=true",
                "gateway.testing.correctness.fixture-material.enabled=true",
                "gateway.testing.correctness.fixture-material.active-key-id=agent-tdd-test-v1",
                "gateway.testing.correctness.fixture-material.key-ring="
                        + "agent-tdd-test-v1=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
                "spring.datasource.url=jdbc:h2:mem:agent-tdd-mcp-ops;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
        })
@Import(AgentTddMcpOperationalWorkflowTest.SolutionRuntimeTestConfiguration.class)
class AgentTddMcpOperationalWorkflowTest {
    private static final String TOOL_REF = "codex-profile-ops-test";
    private static final String CASE_SET_REF = "codex-profile-cases-test";
    private static final String RESOURCE_ID = "codex-profile-service.getProfile-test";
    private static final String LIBRARY_ID = "codex-profile-ops-library-test";
    private static final String LIBRARY_OPERATOR_REF = "codex-profile:read";

    @Autowired
    private WritableResourceRegistry resources;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private TestRestTemplate http;

    @LocalServerPort
    private int port;
    private int requestId;

    @BeforeEach
    void seedRuntimeApiCatalog() {
        GatewayProperties properties = new GatewayProperties();
        properties.setBaseUrl("http://localhost:" + port + "/demo-upstream");
        properties.setSeedDescriptors(true);
        new ResourceDescriptorBootstrap(resources, properties).seedDescriptors();
    }

    @Test
    void composesApprovesRunsLogicalGreenAutomaticallyAttestsSignsAndPublishes() {
        negotiateCodexLifecycle();

        JsonNode initialOverview = agentGet("/api/agent-tdd/library-overview");
        assertThat(initialOverview.path("buildingBlocks")).anySatisfy(block ->
                assertThat(block.path("ref").asText()).isEqualTo("bloge:decisionTable"));

        JsonNode declared = invoke("rg.resource.declare", Map.of(
                "resourceId", RESOURCE_ID,
                "method", "GET",
                "urlTemplate", "http://localhost:" + port
                        + "/demo-upstream/api/users/{userId}/profile",
                "payloadSchema", profileEnvelopeSchema(),
                "idempotencyKey", "declare-profile-ops-test"), "AGENT_TDD_AUTHORING");
        assertThat(declared.at("/data/registered").asBoolean()).isTrue();

        JsonNode capabilities = invoke("rg.capability.list", Map.of("kind", "API"), "AGENT_TDD_READ");
        JsonNode profile = java.util.stream.StreamSupport.stream(
                        capabilities.at("/data/capabilities").spliterator(), false)
                .filter(value -> value.path("ref").asText().equals("resource:" + RESOURCE_ID))
                .findFirst().orElseThrow();
        String bindingRef = profile.path("ref").asText();
        JsonNode contract = invoke("rg.contract.get", Map.of("assetRef", bindingRef), "AGENT_TDD_READ");
        assertThat(contract.at("/data/bindingRef").asText()).isEqualTo(bindingRef);

        invoke("rg.library.upsert", Map.of(
                "libraryYaml", profileLibrary(bindingRef),
                "idempotencyKey", "library-profile-ops-test"), "AGENT_TDD_AUTHORING");
        JsonNode logicalContract = invoke(
                "rg.contract.get", Map.of("assetRef", LIBRARY_OPERATOR_REF), "AGENT_TDD_READ");
        assertSemanticallyEquivalentSchemas(
                logicalContract.at("/data/inputs/0/schema"), contract.at("/data/inputs/0/schema"));
        assertSemanticallyEquivalentSchemas(
                logicalContract.at("/data/outputs/0/schema"), contract.at("/data/outputs/0/schema"));
        assertThat(logicalContract.at("/data/effect"))
                .isEqualTo(contract.at("/data/effect"));
        JsonNode provided = invoke("rg.fixture.provide", Map.of(
                "operatorRef", LIBRARY_OPERATOR_REF,
                "outputPort", "payload",
                "sampleValue", profileEnvelope("u-sample"),
                "category", "INTERNAL",
                "retentionDays", 3,
                "redactPaths", List.of("/data/email"),
                "idempotencyKey", "provide-profile-sample-test"), "AGENT_TDD_GOVERNANCE");
        assertThat(provided.at("/data/sourceKind").asText()).isEqualTo("SAMPLE");
        assertThat(provided.at("/data").toString()).doesNotContain("Alice", "premium", "email");

        JsonNode populatedOverview = agentGet("/api/agent-tdd/library-overview");
        assertThat(populatedOverview.at("/worldModel/operations")).anySatisfy(operation -> {
            assertThat(operation.path("ref").asText()).isEqualTo(LIBRARY_OPERATOR_REF);
            assertThat(operation.path("bound").asBoolean()).isTrue();
        });
        assertThat(populatedOverview.path("samples")).anySatisfy(sample ->
                assertThat(sample.path("sourceKind").asText()).isEqualTo("SAMPLE"));

        JsonNode composed = composeThroughAuthoringGate(
                TOOL_REF, policyGraph(), List.of(LIBRARY_ID), "compose-profile-policy-test");
        assertThat(composed.at("/data/revision").asLong()).isEqualTo(1);
        assertThat(composed.at("/data/authoringReceiptFingerprint").asText()).startsWith("sha256:");

        JsonNode composedCard = toolCard(agentGet("/api/agent-tdd/board"), TOOL_REF);
        assertThat(composedCard.at("/journey/stage").asText()).isEqualTo("ORCHESTRATION");
        assertThat(composedCard.at("/ruleMatrices/0/rules/0/outputs/decision").asText())
                .isEqualTo("PRIORITY");
        assertThat(composedCard.path("flowSummary").asText())
                .contains("事实", "规则表", "产出");

        invoke("rg.tool.setInstruction", Map.of(
                "toolRef", TOOL_REF,
                "instruction", Map.of(
                        "name", "codexProfileOps",
                        "title", "Customer profile lookup",
                        "description", "Reads a governed customer profile and applies service priority.",
                        "whenToUse", "Use for an operator-requested customer service priority.",
                        "inputs", List.of(Map.of("name", "userId", "type", "string", "required", true)),
                        "outputs", Map.of("name", "string", "decision", "string"),
                        "errors", List.of(Map.of("code", "PROFILE_UNAVAILABLE"))),
                "idempotencyKey", "instruction-profile-ops-test"), "AGENT_TDD_AUTHORING");
        JsonNode cases = invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", CASE_SET_REF,
                "toolRef", TOOL_REF,
                "rows", List.of(Map.of(
                        "caseId", "profile-premium",
                        "category", "GOLDEN",
                        "layer", "contract",
                        "given", Map.of("userId", "u-100", "tier", "premium"),
                        "stubs", Map.of("profile", Map.of("payload", profileEnvelope("u-100"))),
                        "expect", Map.of("name", "Alice", "decision", "PRIORITY"),
                        "intent", "Prioritize a premium customer using the governed profile",
                        "oracleOwner", "profile-ops")),
                "idempotencyKey", "cases-profile-ops-test"), "AGENT_TDD_AUTHORING");
        JsonNode oracleReview = reviewGet("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF
                + "/profile-premium?expectedRevision=" + cases.at("/data/revision").asLong());
        assertThat(oracleReview.path("intent").asText()).contains("premium customer");
        assertAgentCannotApprove("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF
                + "/profile-premium/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", oracleReview.path("proposalFingerprint").asText()));
        reviewPost("/api/agent-tdd/reviews/oracles/" + CASE_SET_REF + "/profile-premium/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", oracleReview.path("proposalFingerprint").asText()));

        JsonNode goldenCard = toolCard(agentGet("/api/agent-tdd/board"), TOOL_REF);
        assertThat(goldenCard.at("/journey/stage").asText()).isEqualTo("GOLDEN");
        assertThat(goldenCard.at("/factCoverage/dimensions")).anySatisfy(dimension ->
                assertThat(dimension.path("column").asText()).isEqualTo("tier"));
        assertThat(goldenCard.at("/factCoverage/coveredCount").asInt()).isPositive();

        JsonNode red = invoke("rg.simulate", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(LIBRARY_ID), "side", "RED",
                "cases", Map.of("caseSetRef", CASE_SET_REF)), "AGENT_TDD_EXECUTION");
        assertThat(red.at("/data/cases/0/verdict").asText())
                .as(red.toPrettyString()).isEqualTo("RED_PASS");
        assertThat(red.at("/data/realExternalCalls").asInt()).isZero();

        JsonNode green = invoke("rg.tool.baseline", Map.of(
                "toolRef", TOOL_REF, "libraryRefs", List.of(LIBRARY_ID),
                "caseSetRef", CASE_SET_REF, "side", "GREEN", "rounds", 2), "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/businessFingerprintStable").asBoolean()).isTrue();
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(green.at("/data/attestation/status").asText())
                .as(green.toPrettyString()).isEqualTo("ATTESTED");
        assertThat(green.at("/data/attestation/realExternalCalls").asInt()).isEqualTo(1);
        assertThat(green.at("/data/attestation/cases/0/oracleHeld").asBoolean()).isTrue();
        assertThat(green.at("/data/attestation/dependencies/0/realCallCount").asInt()).isEqualTo(1);
        JsonNode attestation = green.at("/data/attestation");
        assertThat(attestation.findValues("given")).isEmpty();
        assertThat(attestation.findValues("expect")).isEmpty();
        assertThat(attestation.findValues("output")).isEmpty();
        assertThat(attestation.findValues("payload")).isEmpty();
        assertThat(attestation.toString()).doesNotContain("u-100", "Alice", "alice@example.com");

        JsonNode attestedCard = toolCard(agentGet("/api/agent-tdd/board"), TOOL_REF);
        assertThat(attestedCard.at("/journey/stage").asText()).isEqualTo("PUBLISH");
        assertThat(attestedCard.at("/gates/runtimeAttestation").asBoolean()).isTrue();

        reviewPost("/api/agent-tdd/reviews/tools/" + TOOL_REF
                + "/signoffs/ops-review-test/approve", Map.of(
                "draftRevision", green.at("/data/draftRevision").asLong(),
                "goldenSetId", green.at("/data/goldenSetId").asText(),
                "evidenceFingerprint", green.at("/data/evidenceFingerprint").asText(),
                "implementationFingerprint",
                green.at("/data/attestation/implementationFingerprint").asText()));
        assertThat(invoke("rg.readiness.get", Map.of("toolRef", TOOL_REF))
                .at("/data/publishable").asBoolean()).isTrue();

        JsonNode published = invoke("rg.tool.publish", Map.of(
                "toolRef", TOOL_REF,
                "signoffRef", "ops-review-test",
                "idempotencyKey", "publish-profile-ops-test"), "AGENT_TDD_GOVERNANCE");
        assertThat(published.at("/data/artifactKind").asText()).isEqualTo("EXECUTABLE");
    }

    @Test
    void realResponseThatViolatesTheApprovedOracleKeepsPublicationClosed() throws Exception {
        String toolRef = "codex-profile-attestation-failure-test";
        String caseSetRef = "codex-profile-attestation-failure-cases";
        JsonNode capabilities = invoke("rg.capability.list", Map.of("kind", "API"), "AGENT_TDD_READ");
        String bindingRef = java.util.stream.StreamSupport.stream(
                        capabilities.at("/data/capabilities").spliterator(), false)
                .map(value -> value.path("ref").asText())
                .filter(value -> value.contains("user-service.getProfile"))
                .findFirst().orElseThrow();
        composeThroughAuthoringGate(
                toolRef, graph(bindingRef), List.of(), "compose-attestation-failure");
        JsonNode cases = invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", caseSetRef, "toolRef", toolRef,
                "rows", List.of(Map.of(
                        "caseId", "profile-mismatch", "category", "GOLDEN", "layer", "contract",
                        "given", Map.of("userId", "u-mismatch"),
                        "stubs", Map.of("profile", Map.of("payload", Map.of(
                                "userId", "u-mismatch", "name", "Bob", "tier", "premium"))),
                        "expect", Map.of("name", "Bob", "tier", "premium"),
                        "intent", "Reject a sandbox response that contradicts the approved Oracle",
                        "oracleOwner", "profile-ops")),
                "idempotencyKey", "cases-attestation-failure"), "AGENT_TDD_AUTHORING");
        JsonNode review = reviewGet("/api/agent-tdd/reviews/oracles/" + caseSetRef
                + "/profile-mismatch?expectedRevision=" + cases.at("/data/revision").asLong());
        reviewPost("/api/agent-tdd/reviews/oracles/" + caseSetRef + "/profile-mismatch/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", review.path("proposalFingerprint").asText()));

        JsonNode baseline = invoke("rg.tool.baseline", Map.of(
                "toolRef", toolRef, "libraryRefs", List.of(), "caseSetRef", caseSetRef,
                "side", "GREEN", "rounds", 1), "AGENT_TDD_EXECUTION");

        assertThat(baseline.at("/data/status").asText()).isEqualTo("GO");
        assertThat(baseline.at("/data/attestation/status").asText()).isEqualTo("FAILED");
        assertThat(baseline.at("/data/attestation/reasonCode").asText())
                .isEqualTo("ATTESTATION_ORACLE_MISMATCH");
        assertThat(baseline.at("/data/attestation/realExternalCalls").asInt()).isEqualTo(1);
        assertThat(baseline.at("/data/remainingLimitations").toString())
                .contains("RUNTIME_ENV_NOT_ATTESTED", "LIVE_INTEGRATION_NOT_ATTESTED");
        JsonNode readiness = invoke("rg.readiness.get", Map.of("toolRef", toolRef), "AGENT_TDD_READ");
        assertThat(readiness.at("/data/publishable").asBoolean()).isFalse();
        assertThat(readiness.at("/data/gates/runtimeAttestation").asBoolean()).isFalse();

        String rerunPath = "/api/agent-tdd/attestations/" + toolRef + "/rerun";
        ResponseEntity<JsonNode> rejected = postAs(
                rerunPath, null, "bloge-aneke-demo-token", "AGENT_TDD_GOVERNANCE");
        assertThat(rejected.getStatusCode().value()).isEqualTo(409);
        assertThat(rejected.getBody().at("/error/code").asText()).isEqualTo("FORBIDDEN_PURPOSE");

        WebDriver browser = newChromeDriverOrSkip();
        try {
            WebDriverWait wait = new WebDriverWait(browser, Duration.ofSeconds(12));
            browser.get("http://localhost:" + port + "/agent-tdd.html");
            wait.until(ExpectedConditions.elementToBeClickable(By.id("token")))
                    .sendKeys("bloge-reviewer-demo-token");
            browser.findElement(By.id("load")).click();
            By retryButton = By.xpath("//article[contains(@class,'tool') and contains(.,'"
                    + toolRef + "')]//button[@data-attestation-rerun]");
            WebElement retry = wait.until(ExpectedConditions.elementToBeClickable(retryButton));
            retry.click();
            wait.until(ExpectedConditions.alertIsPresent()).accept();
            wait.until(ExpectedConditions.stalenessOf(retry));
            wait.until(ExpectedConditions.elementToBeClickable(retryButton));
        } finally {
            browser.quit();
        }
        JsonNode recoveredCard = toolCard(agentGet("/api/agent-tdd/board"), toolRef);
        assertThat(recoveredCard.at("/attestation/status").asText()).isEqualTo("FAILED");
        assertThat(recoveredCard.at("/attestation/reasonCode").asText())
                .isEqualTo("ATTESTATION_ORACLE_MISMATCH");
        assertThat(recoveredCard.at("/attestation/realExternalCalls").asInt()).isEqualTo(1);
        assertThat(recoveredCard.path("attestation").toString())
                .doesNotContain("u-mismatch", "Alice", "Bob", "payload", "output");
    }

    @Test
    @Timeout(45)
    void humanReviewerOpensExactOracleDetailAndApprovesItThroughTheBrowser() throws Exception {
        String toolRef = "codex-wallet-browser-test";
        String caseSetRef = "codex-wallet-browser-cases-test";
        JsonNode capabilities = invoke("rg.capability.list", Map.of("kind", "API"), "AGENT_TDD_READ");
        String bindingRef = java.util.stream.StreamSupport.stream(
                        capabilities.at("/data/capabilities").spliterator(), false)
                .map(value -> value.path("ref").asText())
                .filter(value -> value.contains("wallet-service.getBalance"))
                .findFirst().orElseThrow();
        composeThroughAuthoringGate(
                toolRef, walletGraph(bindingRef), List.of(), "compose-wallet-browser-test");
        invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", caseSetRef,
                "toolRef", toolRef,
                "rows", List.of(Map.of(
                        "caseId", "wallet-browser-usd",
                        "category", "GOLDEN",
                        "layer", "contract",
                        "given", Map.of("userId", "browser-u-100"),
                        "stubs", Map.of("wallet", Map.of(
                                "payload", Map.of("amount", 125, "currency", "USD"))),
                        "expect", Map.of("amount", 125, "currency", "USD"),
                        "intent", "Browser reviewer confirms the exact governed wallet balance",
                        "oracleOwner", "wallet-browser-ops")),
                "idempotencyKey", "cases-wallet-browser-test"), "AGENT_TDD_AUTHORING");

        WebDriver browser = newChromeDriverOrSkip();
        try {
            browser.manage().window().setSize(new Dimension(1280, 900));
            WebDriverWait wait = new WebDriverWait(browser, Duration.ofSeconds(12));
            browser.get("http://localhost:" + port + "/agent-tdd.html");
            wait.until(ExpectedConditions.elementToBeClickable(By.id("token")))
                    .sendKeys("bloge-reviewer-demo-token");
            browser.findElement(By.id("load")).click();
            By reviewButton = By.xpath("//div[contains(@class,'review') and contains(.,'"
                    + caseSetRef + "')]/button");
            wait.until(ExpectedConditions.elementToBeClickable(reviewButton)).click();

            Alert confirmation = wait.until(ExpectedConditions.alertIsPresent());
            assertThat(confirmation.getText())
                    .contains("Browser reviewer confirms the exact governed wallet balance")
                    .contains("browser-u-100")
                    .contains("wallet-browser-ops")
                    .contains("proposedBy")
                    .contains("aneke-sync");
            confirmation.accept();
            wait.until(ExpectedConditions.textToBePresentInElementLocated(By.id("reviewCount"), "0"));
        } finally {
            browser.quit();
        }

        JsonNode cases = invoke("rg.scenario.listCases", Map.of("caseSetRef", caseSetRef),
                "AGENT_TDD_READ");
        assertThat(cases.at("/data/rows/0/lifecycle").asText()).isEqualTo("ACTIVE");
    }

    @Test
    @Timeout(30)
    void browserRendersRuleMatrixAndReflectsTheProjectedRuleOutput() throws Exception {
        WebDriver browser = newChromeDriverOrSkip();
        try {
            browser.get("http://localhost:" + port + "/agent-tdd.html");
            JavascriptExecutor javascript = (JavascriptExecutor) browser;
            Map<String, Object> matrix = Map.of(
                    "nodeId", "policy", "label", "纠纷规则", "hitPolicy", "unique",
                    "conditionColumns", List.of(Map.of("id", "party", "label", "责任方")),
                    "outputColumns", List.of(Map.of("id", "decision", "label", "处置")),
                    "rules", List.of(Map.of("id", "R1", "conditions", Map.of("party", "= driver"),
                            "outputs", Map.of("decision", "REVIEW"))),
                    "otherwise", Map.of("decision", "ESCALATE_HUMAN"));

            String original = (String) javascript.executeScript(
                    "return renderRuleMatrix(arguments[0]);", matrix);
            Map<String, Object> changed = new java.util.LinkedHashMap<>(matrix);
            changed.put("rules", List.of(Map.of("id", "R1", "conditions", Map.of("party", "= driver"),
                    "outputs", Map.of("decision", "APPROVE"))));
            String revised = (String) javascript.executeScript(
                    "return renderRuleMatrix(arguments[0]);", changed);

            @SuppressWarnings("unchecked")
            List<Number> journeyCounts = (List<Number>) javascript.executeScript("""
                    const host = document.createElement('div');
                    host.innerHTML = renderTool(arguments[0]);
                    document.body.appendChild(host);
                    return [host.querySelectorAll('.journey-dot').length,
                            host.querySelectorAll('.journey-dot.active').length];
                    """, Map.of(
                    "toolRef", "journey-dom-test",
                    "state", "IMPLEMENTING",
                    "journey", Map.of("stageIndex", 2, "nextAction", "ADD_GOLDEN")));

            assertThat(original).contains("责任方", "处置", "REVIEW", "ESCALATE_HUMAN");
            assertThat(revised).contains("APPROVE").doesNotContain(">REVIEW<");
            assertThat(journeyCounts).extracting(Number::intValue).containsExactly(5, 3);
        } finally {
            browser.quit();
        }
    }

    @Test
    @Timeout(45)
    void solutionJourneyRunsThroughRealMcpWriteReconciliationAndBrowserSignoff() throws Exception {
        String solutionRef = "sol:cancel-browser-ops";
        String scenarioRef = "scn:cancel-browser-ops";
        String instructionRef = "ins:waive-browser-ops";
        String caseSetRef = "caseSet:cancel-browser-ops";
        negotiateCodexLifecycle();

        JsonNode initial = invoke("rg.solution.performance", Map.of("solutionRef", solutionRef));
        assertThat(initial.at("/data/totalCases").asInt()).isZero();

        invoke("rg.feature.define", Map.of(
                "featureYaml", """
                        dispute.party.browser:
                          output: { type: { enum: [none, driver] } }
                          evaluationKind: USER_COMPONENT
                          determinism: INTERACTIVE
                          componentRef: party-picker-v1
                          businessDefinition: { semanticKey: ride.cancel.party.browser, intent: 判断取消责任, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: enum }, asOf: CANCELLATION_OCCURRED_AT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: USER, freshness: { mode: AS_OF_EVENT }, effect: PURE }
                        """,
                "idempotencyKey", "feature-party-browser-ops"), "AGENT_TDD_AUTHORING");
        invoke("rg.feature.define", Map.of(
                "featureYaml", """
                        dispute.order.browser:
                          output: { type: string }
                          evaluationKind: USER_COMPONENT
                          determinism: INTERACTIVE
                          componentRef: order-picker-v1
                          businessDefinition: { semanticKey: ride.dispute.order.browser, intent: 选择争议订单, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: string }, asOf: CURRENT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: USER, freshness: { mode: CURRENT }, effect: PURE }
                        """,
                "idempotencyKey", "feature-order-browser-ops"), "AGENT_TDD_AUTHORING");
        invoke("rg.instruction.define", Map.of(
                "instructionYaml", solutionInstruction(instructionRef, ""),
                "idempotencyKey", "instruction-design-browser-ops"), "AGENT_TDD_AUTHORING");
        invoke("rg.scenario.define", Map.of(
                "scenarioYaml", solutionScenario(scenarioRef, instructionRef),
                "libraryRefs", List.of(),
                "idempotencyKey", "scenario-browser-ops"), "AGENT_TDD_AUTHORING");
        JsonNode composed = invoke("rg.solution.compose", Map.of(
                "solutionYaml", solutionContract(solutionRef, scenarioRef, instructionRef, caseSetRef),
                "authoringContextFingerprint", "sha256:solution-browser-context",
                "idempotencyKey", "solution-compose-browser-ops"), "AGENT_TDD_AUTHORING");
        assertThat(composed.at("/data/speccing").asBoolean()).isTrue();
        assertThat(composed.at("/data/authoringReceiptFingerprint").asText()).startsWith("sha256:");

        JsonNode handoff = invoke("rg.engineering.handoff", Map.of(
                "solutionRef", solutionRef, "idempotencyKey", "handoff-browser-ops"),
                "AGENT_TDD_AUTHORING");
        assertThat(handoff.at("/data/items/0/state").asText()).isEqualTo("DESIGN_ONLY");

        ResponseEntity<JsonNode> implemented = postAs(
                "/api/agent-tdd/engineering-handoffs/" + solutionRef + "/instructions/"
                        + instructionRef + "/fulfil",
                Map.of("bindingRef", "operator:refund-browser-v1"),
                "bloge-instruction-engineer-demo-token", "AGENT_TDD_INSTRUCTION_ENG");
        assertThat(implemented.getStatusCode().is2xxSuccessful()).as(implemented.toString()).isTrue();
        assertThat(implemented.getBody().path("status").asText()).isEqualTo("IMPLEMENTED");
        JsonNode cases = invoke("rg.scenario.upsertCases", Map.of(
                "caseSetRef", caseSetRef,
                "toolRef", solutionRef,
                "rows", List.of(Map.of(
                        "caseId", "cancel-none-browser",
                        "category", "GOLDEN",
                        "layer", "integration",
                        "given", Map.of("party", "none", "orderId", "O-browser-1"),
                        "stubs", Map.of(),
                        "expect", Map.of("result", Map.of("decision", "WAIVED")),
                        "intent", "无责乘客应免除取消费，并能按订单核对退款结果",
                        "oracleOwner", "customer-experience-owner")),
                "idempotencyKey", "cases-solution-browser-ops"), "AGENT_TDD_AUTHORING");
        JsonNode oracle = reviewGet("/api/agent-tdd/reviews/oracles/" + caseSetRef
                + "/cancel-none-browser?expectedRevision=" + cases.at("/data/revision").asLong());
        reviewPost("/api/agent-tdd/reviews/oracles/" + caseSetRef
                + "/cancel-none-browser/approve", Map.of(
                "expectedRevision", cases.at("/data/revision").asLong(),
                "proposalFingerprint", oracle.path("proposalFingerprint").asText()));

        JsonNode green = invoke("rg.solution.baseline", Map.of(
                "solutionRef", solutionRef, "caseSetRef", caseSetRef, "side", "GREEN"),
                "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(green.at("/data/writeReconciliation/status").asText()).isEqualTo("RECONCILED");
        assertThat(green.at("/data/writeReconciliation/writeCount").asInt()).isEqualTo(1);
        JsonNode proposal = invoke("rg.solution.commit", Map.of(
                "solutionRef", solutionRef,
                "authoringReceiptFingerprint", composed.at("/data/authoringReceiptFingerprint").asText(),
                "idempotencyKey", "commit-solution-browser-ops"), "AGENT_TDD_AUTHORING");
        assertThat(proposal.at("/data/proposalStatus").asText()).isEqualTo("PENDING");

        JsonNode board = agentGet("/api/agent-tdd/board");
        assertThat(solutionCard(board, solutionRef).path("problem").asText())
                .isEqualTo("Resolve a cancellation dispute consistently.");
        assertThat(board.path("pendingReviews")).anySatisfy(review -> {
            assertThat(review.path("kind").asText()).isEqualTo("SOLUTION_SIGNOFF");
            assertThat(review.path("implementationFingerprint").asText()).startsWith("sha256:");
        });

        WebDriver browser = newChromeDriverOrSkip();
        try {
            WebDriverWait wait = new WebDriverWait(browser, Duration.ofSeconds(12));
            browser.get("http://localhost:" + port + "/agent-tdd.html");
            wait.until(ExpectedConditions.elementToBeClickable(By.id("token")))
                    .sendKeys("bloge-reviewer-demo-token");
            browser.findElement(By.id("load")).click();
            By button = By.xpath("//div[contains(@class,'review') and contains(.,'"
                    + solutionRef + "')]/button");
            wait.until(ExpectedConditions.elementToBeClickable(button)).click();
            Alert confirmation = wait.until(ExpectedConditions.alertIsPresent());
            assertThat(confirmation.getText())
                    .contains("Resolve a cancellation dispute consistently")
                    .contains("writeReconciliation")
                    .contains("implementationFingerprint");
            confirmation.accept();
            Alert signoffPrompt = wait.until(ExpectedConditions.alertIsPresent());
            signoffPrompt.sendKeys("solution-change-browser-1");
            signoffPrompt.accept();
            wait.until(ExpectedConditions.stalenessOf(
                    browser.findElement(By.xpath("//div[contains(@class,'review') and contains(.,'"
                            + solutionRef + "')]"))));
        } finally {
            browser.quit();
        }

        JsonNode readiness = invoke("rg.solution.readiness", Map.of("solutionRef", solutionRef));
        assertThat(readiness.at("/data/publishable").asBoolean()).isTrue();
        assertThat(readiness.at("/data/gates/writeReconciled").asBoolean()).isTrue();
        JsonNode published = invoke("rg.solution.publish", Map.of(
                "solutionRef", solutionRef,
                "signoffRef", "solution-change-browser-1",
                "idempotencyKey", "publish-solution-browser-ops"), "AGENT_TDD_GOVERNANCE");
        assertThat(published.at("/data/artifactKind").asText()).isEqualTo("SOLUTION");
        assertThat(invoke("rg.solution.performance", Map.of("solutionRef", solutionRef))
                .at("/data/totalCases").asInt()).isEqualTo(1);
    }

    @Test
    void businessSurfaceRunsProtectedGoldenThroughTheRealMcpLifecycle() {
        String solutionRef = "sol:business-cancel-ops";
        String scenarioRef = "scn:business-cancel-ops";
        String instructionRef = "ins:business-uphold-ops";
        negotiateBusinessCodexLifecycle();

        JsonNode overview = invokeBusiness("rg.library.overview.get",
                Map.of("includeSamples", false), "AGENT_TDD_READ");
        String authoringPatternsFingerprint = overview.at(
                "/data/authoringPatternsFingerprint").asText();
        assertThat(authoringPatternsFingerprint).startsWith("sha256:");

        JsonNode started = invokeBusiness("rg.journey.start", Map.of(
                "intentKind", "CREATE_SOLUTION",
                "businessGoal", "乘客超时取消时维持取消费并给出责任解释",
                "idempotencyKey", "journey-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        String journeyRef = started.at("/data/journeyRef").asText();
        assertThat(started.at("/data/stage").asText()).isEqualTo("DISCOVERING");

        JsonNode stale = callBusiness("rg.feature.define", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 1,
                "authoringPatternsFingerprint", "sha256:" + "0".repeat(64),
                "featureYaml", "not persisted", "idempotencyKey", "stale-template"),
                "AGENT_TDD_AUTHORING");
        assertThat(stale.path("ok").asBoolean()).isFalse();
        assertThat(stale.at("/error/code").asText()).isEqualTo("CAPABILITY_CONTEXT_STALE");
        assertThat(stale.toString()).doesNotContain(authoringPatternsFingerprint);

        invokeBusiness("rg.feature.define", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 1,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "featureYaml", """
                        dispute.party.business:
                          output: { type: { enum: [passenger, driver] } }
                          evaluationKind: USER_COMPONENT
                          determinism: INTERACTIVE
                          componentRef: cancellation-party-v1
                          businessSemantics: 取消责任方
                          businessDefinition: { semanticKey: ride.cancel.party.business, intent: 判断取消责任, domain: ride-cancellation, businessObject: ride-order, requiredContext: [], resultDomain: { type: enum, values: [passenger, driver] }, asOf: CANCELLATION_OCCURRED_AT, unknownPolicy: REQUIRE_HUMAN_REVIEW, acquisitionOwner: USER, freshness: { mode: AS_OF_EVENT }, effect: PURE }
                        """,
                "idempotencyKey", "feature-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        invokeBusiness("rg.scenario.define", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 2,
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
                          businessDefinition: { semanticKey: ride.cancel.decision.business, intent: 判定取消费争议处置, domain: ride-cancellation, businessObject: ride-order, inputFactKeys: [ride.cancel.party.business], decisionPolicy: UNIQUE, outletSemanticKeys: [ride.cancel.uphold.business, ride.cancel.manual-review.business], otherwisePolicy: ESCALATE }
                        """.formatted(scenarioRef, instructionRef),
                "libraryRefs", List.of(),
                "idempotencyKey", "scenario-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        invokeBusiness("rg.instruction.define", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 3,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "instructionYaml", """
                        %s:
                          inputs: { party: string }
                          output:
                            result: { type: { fields: { decision: { enum: [UPHELD] } } } }
                            reasoning: required
                          effect: READ
                          businessSemantics: 维持取消费
                          businessDefinition: { semanticKey: ride.cancel.uphold.business, intent: 维持乘客取消费并解释原因, domain: ride-cancellation, businessObject: ride-order, requiredFactKeys: [ride.cancel.party.business], resultDomain: { type: object, decision: { enum: [UPHELD] } }, reasoningPolicy: REQUIRED, effect: READ, failurePolicy: ESCALATE, writeGovernanceClass: NONE }
                        """.formatted(instructionRef),
                "idempotencyKey", "instruction-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        JsonNode composing = invokeBusiness("rg.journey.next", Map.of(
                "journeyRef", journeyRef, "expectedRevision", 4), "AGENT_TDD_READ");
        String contextFingerprint = composing.at("/data/solutionContextFingerprint").asText();
        assertThat(composing.at("/data/stage").asText()).isEqualTo("COMPOSING");

        invokeBusiness("rg.solution.compose", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 4,
                "authoringPatternsFingerprint", authoringPatternsFingerprint,
                "solutionContextFingerprint", contextFingerprint,
                "solutionYaml", """
                        %s:
                          problem: 处理乘客超时取消费争议。
                          inputs: { party: dispute.party.business }
                          scenarioTree: { root: '%s' }
                          instructions: ['%s']
                          golden: 'caseSet:business-cancel-ops'
                          businessDefinition: { semanticKey: ride.cancel.solution.business, intent: 处理乘客超时取消费争议, domain: ride-cancellation, businessObject: ride-order, problemClass: CANCELLATION_FEE_DISPUTE, requiredFactKeys: [ride.cancel.party.business], scenarioSemanticKey: ride.cancel.decision.business, dispositionSemanticKeys: [ride.cancel.uphold.business, ride.cancel.manual-review.business], runtimeUse: GOVERNED_DECISION }
                        """.formatted(solutionRef, scenarioRef, instructionRef),
                "idempotencyKey", "compose-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        JsonNode proposed = invokeBusiness("rg.solution.golden.propose", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 5,
                "solutionRef", solutionRef,
                "cases", List.of(Map.of(
                        "caseId", "late-cancel",
                        "businessIntent", "乘客超时取消由乘客承担",
                        "givenFacts", List.of(Map.of("factName", "取消责任方", "value", "passenger")),
                        "dependencyAssumptions", List.of(Map.of(
                                "capabilityName", "维持取消费", "outcome", "RETURNS",
                                "value", Map.of("result", Map.of("decision", "UPHELD"),
                                        "reasoning", "责任在乘客"))),
                        "expectedOutcome", Map.of("result", Map.of("decision", "UPHELD"),
                                "reasoningClass", "责任在乘客"),
                        "oracleOwner", "customer-experience-owner")),
                "idempotencyKey", "golden-business-cancel-ops"), "AGENT_TDD_AUTHORING");
        String caseSetRef = proposed.at("/data/caseSetRef").asText();
        assertThat(proposed.at("/data").toString())
                .doesNotContain("passenger", "UPHELD", "责任在乘客");

        JsonNode review = reviewGet("/api/agent-tdd/reviews/oracles/" + caseSetRef
                + "/late-cancel?expectedRevision=1");
        assertThat(review.path("intent").asText()).isEqualTo("乘客超时取消由乘客承担");
        reviewPost("/api/agent-tdd/reviews/oracles/" + caseSetRef + "/late-cancel/approve", Map.of(
                "expectedRevision", 1,
                "proposalFingerprint", review.path("proposalFingerprint").asText()));

        JsonNode testing = invokeBusiness("rg.journey.next", Map.of(
                "journeyRef", journeyRef, "expectedRevision", 6), "AGENT_TDD_READ");
        assertThat(testing.at("/data/stage").asText()).isEqualTo("TESTING");
        JsonNode green = invokeBusiness("rg.solution.baseline", Map.of(
                "journeyRef", journeyRef, "expectedJourneyRevision", 6,
                "solutionRef", solutionRef, "side", "GREEN"), "AGENT_TDD_EXECUTION");
        assertThat(green.at("/data/status").asText()).isEqualTo("GO");
        assertThat(green.at("/data/realExternalCalls").asInt()).isZero();
        assertThat(green.at("/data/cases/0/verdict").asText()).isEqualTo("GREEN_PASS");
        assertThat(invokeBusiness("rg.journey.next", Map.of(
                "journeyRef", journeyRef, "expectedRevision", 7), "AGENT_TDD_READ")
                .at("/data/stage").asText()).isEqualTo("WAITING_SIGNOFF");
    }

    /**
     * Exercises the same initialize, initialized notification and tool discovery sequence emitted
     * by a current Codex Streamable-HTTP client before it can call an Agent TDD tool.
     */
    private void negotiateCodexLifecycle() {
        HttpHeaders initializeHeaders = headers("bloge-aneke-demo-token", "AGENT_TDD_READ");
        JsonNode initializeRequest = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0",
                "id", ++requestId,
                "method", "initialize",
                "params", Map.of(
                        "protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "codex", "version", "0.150.0"))));
        ResponseEntity<JsonNode> initialized = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(initializeRequest, initializeHeaders), JsonNode.class);
        assertThat(initialized.getStatusCode().value()).isEqualTo(200);
        assertThat(initialized.getBody().at("/result/protocolVersion").asText())
                .isEqualTo(McpProtocolController.CODEX_PROTOCOL_VERSION);
        assertThat(initialized.getBody().at("/result/serverInfo/name").asText())
                .isEqualTo("bloge-resource-gateway");

        initializeHeaders.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        JsonNode notification = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "method", "notifications/initialized", "params", Map.of()));
        ResponseEntity<JsonNode> acknowledged = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(notification, initializeHeaders), JsonNode.class);
        assertThat(acknowledged.getStatusCode().value()).isEqualTo(202);
        assertThat(acknowledged.getBody()).isNull();

        JsonNode listRequest = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "tools/list", "params", Map.of()));
        ResponseEntity<JsonNode> listed = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(listRequest, initializeHeaders), JsonNode.class);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(listed.getBody().at("/result/tools")).anySatisfy(tool ->
                assertThat(tool.path("name").asText()).isEqualTo("rg.capability.list"));
        assertThat(listed.getBody().at("/result/tools")).anySatisfy(tool ->
                assertThat(tool.path("name").asText()).isEqualTo("rg.tool.publish"));
    }

    private void negotiateBusinessCodexLifecycle() {
        HttpHeaders initializeHeaders = headers("bloge-aneke-demo-token", "AGENT_TDD_READ");
        initializeHeaders.set("X-RG-Surface", "BUSINESS_SOLUTION");
        JsonNode initializeRequest = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "initialize",
                "params", Map.of("protocolVersion", McpProtocolController.CODEX_PROTOCOL_VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "codex", "version", "0.150.0"))));
        ResponseEntity<JsonNode> initialized = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(initializeRequest, initializeHeaders), JsonNode.class);
        assertThat(initialized.getStatusCode().value()).isEqualTo(200);
        assertThat(initialized.getBody().at("/result/instructions").asText())
                .contains("business language", "rg.journey.next", "authoringPatternsFingerprint")
                .doesNotContain("rg.dsl.preview", "rg.tool.compose");
        initializeHeaders.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        JsonNode listed = mapper.valueToTree(Map.of(
                "jsonrpc", "2.0", "id", ++requestId, "method", "tools/list", "params", Map.of()));
        ResponseEntity<JsonNode> response = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(listed, initializeHeaders), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().at("/result/tools")).anySatisfy(tool ->
                assertThat(tool.path("name").asText()).isEqualTo("rg.journey.next"));
        assertThat(response.getBody().at("/result/tools")).noneSatisfy(tool ->
                assertThat(tool.path("name").asText()).isEqualTo("rg.dsl.preview"));
    }

    private JsonNode invoke(String name, Object arguments) {
        return invoke(name, arguments, "AGENT_TDD_READ");
    }

    private JsonNode invoke(String name, Object arguments, String purpose) {
        JsonNode request = mapper.valueToTree(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
        HttpHeaders headers = headers("bloge-aneke-demo-token", purpose);
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        ResponseEntity<JsonNode> response = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        JsonNode result = response.getBody().at("/result/structuredContent");
        assertThat(result.path("ok").asBoolean()).as(response.getBody().toPrettyString()).isTrue();
        return result;
    }

    private JsonNode invokeBusiness(String name, Object arguments, String purpose) {
        JsonNode result = callBusiness(name, arguments, purpose);
        assertThat(result.path("ok").asBoolean()).as(result.toPrettyString()).isTrue();
        return result;
    }

    private JsonNode callBusiness(String name, Object arguments, String purpose) {
        JsonNode request = mapper.valueToTree(Map.of("jsonrpc", "2.0", "id", ++requestId,
                "method", "tools/call", "params", Map.of("name", name, "arguments", arguments)));
        HttpHeaders headers = headers("bloge-aneke-demo-token", purpose);
        headers.set("X-RG-Surface", "BUSINESS_SOLUTION");
        headers.set("MCP-Protocol-Version", McpProtocolController.CODEX_PROTOCOL_VERSION);
        ResponseEntity<JsonNode> response = http.exchange("/mcp", HttpMethod.POST,
                new HttpEntity<>(request, headers), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody().at("/result/structuredContent");
    }

    /** Follows the Codex authoring contract: reference, preview, gate, then exact receipt promotion. */
    private JsonNode composeThroughAuthoringGate(String toolRef,
                                                 String dsl,
                                                 List<String> libraryRefs,
                                                 String idempotencyKey) {
        JsonNode reference = invoke("rg.dsl.reference.get",
                Map.of("libraryRefs", libraryRefs, "includeExamples", true), "AGENT_TDD_READ");
        String contextFingerprint = reference.at("/data/authoringContextFingerprint").asText();
        Map<String, Object> authoringRequest = Map.of(
                "source", Map.of("sourceId", toolRef + ".bloge", "dsl", dsl),
                "libraryRefs", libraryRefs,
                "authoringContextFingerprint", contextFingerprint);
        JsonNode preview = invoke("rg.dsl.preview", authoringRequest, "AGENT_TDD_READ");
        assertThat(preview.at("/data/accepted").asBoolean()).as(preview.toPrettyString()).isTrue();
        JsonNode gate = invoke("rg.gate.check", authoringRequest, "AGENT_TDD_READ");
        assertThat(gate.at("/data/accepted").asBoolean()).as(gate.toPrettyString()).isTrue();
        assertThat(gate.at("/data/authoringReceiptFingerprint"))
                .isEqualTo(preview.at("/data/authoringReceiptFingerprint"));
        return invoke("rg.tool.compose", Map.of(
                "toolRef", toolRef,
                "graph", Map.of("sourceId", toolRef + ".bloge", "dsl", dsl),
                "libraryRefs", libraryRefs,
                "authoringContextFingerprint", contextFingerprint,
                "authoringReceiptFingerprint", gate.at("/data/authoringReceiptFingerprint").asText(),
                "idempotencyKey", idempotencyKey), "AGENT_TDD_AUTHORING");
    }

    private JsonNode reviewGet(String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers("bloge-reviewer-demo-token", "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private JsonNode agentGet(String path) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.GET,
                new HttpEntity<>(headers("bloge-aneke-demo-token", "AGENT_TDD_READ")), JsonNode.class);
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private static JsonNode toolCard(JsonNode board, String toolRef) {
        return java.util.stream.StreamSupport.stream(board.path("tools").spliterator(), false)
                .filter(card -> toolRef.equals(card.path("toolRef").asText()))
                .findFirst().orElseThrow();
    }

    private static JsonNode solutionCard(JsonNode board, String solutionRef) {
        return java.util.stream.StreamSupport.stream(board.path("solutions").spliterator(), false)
                .filter(card -> solutionRef.equals(card.path("solutionRef").asText()))
                .findFirst().orElseThrow();
    }

    private static String solutionInstruction(String instructionRef, String bindingRef) {
        String binding = bindingRef.isBlank() ? "" : "  bindingRef: '" + bindingRef + "'\n";
        return """
                %s:
                  inputs: { orderId: string }
                  output:
                    result: { type: { fields: { decision: { enum: [WAIVED] } } } }
                    reasoning: required
                  effect: WRITE
                %s  writeGovernance:
                    downstreamSystem: refund-service
                    reconciliationKey: orderId
                    reconciliationAdapterRef: recon:refund-browser-v1
                """.formatted(instructionRef, binding);
    }

    private static String solutionScenario(String scenarioRef, String instructionRef) {
        return """
                %s:
                  inputs: [party]
                  hitPolicy: unique
                  rules:
                    - ruleId: R1
                      when: { party: { eq: none } }
                      outlet: { kind: INSTRUCTION, ref: '%s', bind: { orderId: orderId } }
                  otherwise: { kind: TERMINAL, terminalKind: ESCALATE }
                """.formatted(scenarioRef, instructionRef);
    }

    private static String solutionContract(
            String solutionRef, String scenarioRef, String instructionRef, String caseSetRef) {
        return """
                %s:
                  problem: Resolve a cancellation dispute consistently.
                  inputs:
                    party: dispute.party.browser
                    orderId: dispute.order.browser
                  scenarioTree: { root: '%s' }
                  instructions: ['%s']
                  golden: '%s'
                """.formatted(solutionRef, scenarioRef, instructionRef, caseSetRef);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SolutionRuntimeTestConfiguration {
        @Bean
        InstructionDispatchChannel solutionInstructionChannel() {
            return (instruction, values, context) -> Map.of(
                    "result", Map.of("decision", "WAIVED"), "reasoning", "matched rule R1");
        }

        @Bean
        ReconciliationAdapter solutionReconciliationAdapter() {
            return new ReconciliationAdapter() {
                @Override public String adapterRef() { return "recon:refund-browser-v1"; }
                @Override public String downstreamSystem() { return "refund-service"; }
                @Override public ObservedEffect observe(String reconciliationKey, JsonNode inputs) {
                    return new ObservedEffect(reconciliationKey, Map.of("decision", "WAIVED"));
                }
            };
        }
    }

    private void assertSemanticallyEquivalentSchemas(JsonNode authoredNode, JsonNode runtimeNode) {
        SchemaEnvelope authored = mapper.convertValue(authoredNode, SchemaEnvelope.class);
        SchemaEnvelope runtime = mapper.convertValue(runtimeNode, SchemaEnvelope.class);
        assertThat(VisualSchemaCompatibility.schemasCompatible(authored.schema(), runtime.schema())
                && VisualSchemaCompatibility.schemasCompatible(runtime.schema(), authored.schema()))
                .as("authored=%s runtime=%s", authoredNode, runtimeNode)
                .isTrue();
    }

    private void reviewPost(String path, Object body) {
        assertThat(reviewAction(path, body).path("status").asText()).isEqualTo("APPROVED");
    }

    private JsonNode reviewAction(String path, Object body) {
        ResponseEntity<JsonNode> response = postAs(
                path, body, "bloge-reviewer-demo-token", "AGENT_TDD_GOVERNANCE");
        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.toString()).isTrue();
        return response.getBody();
    }

    private ResponseEntity<JsonNode> postAs(String path,
                                            Object body,
                                            String token,
                                            String purpose) {
        return http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(body == null ? null : mapper.valueToTree(body), headers(token, purpose)),
                JsonNode.class);
    }

    private void assertAgentCannotApprove(String path, Object body) {
        ResponseEntity<JsonNode> response = http.exchange(path, HttpMethod.POST,
                new HttpEntity<>(mapper.valueToTree(body),
                        headers("bloge-aneke-demo-token", "AGENT_TDD_GOVERNANCE")), JsonNode.class);
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().at("/error/code").asText()).isEqualTo("GATE_REJECTED");
    }

    private static HttpHeaders headers(String token, String purpose) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.set("X-Purpose", purpose);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** Starts an actual local Chrome session while keeping offline builds deterministic. */
    private static WebDriver newChromeDriverOrSkip() throws Exception {
        Path chromeDriver = configuredChromeDriver();
        Assumptions.assumeTrue(chromeDriver != null,
                "ChromeDriver executable is unavailable for the Agent TDD board acceptance");
        System.setProperty("webdriver.chrome.driver", chromeDriver.toString());
        ChromeOptions options = new ChromeOptions();
        options.addArguments("--headless=new", "--disable-gpu", "--disable-dev-shm-usage",
                "--no-sandbox", "--remote-debugging-pipe", "--window-size=1280,900");
        Path macChrome = Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome");
        if (Files.isExecutable(macChrome)) {
            options.setBinary(macChrome.toString());
        }
        return new ChromeDriver(options);
    }

    private static Path configuredChromeDriver() throws Exception {
        String configured = System.getProperty("webdriver.chrome.driver", "").trim();
        if (!configured.isBlank() && Files.isExecutable(Path.of(configured))) {
            return Path.of(configured);
        }
        Path cache = Path.of(System.getProperty("user.home"), ".cache", "selenium", "chromedriver");
        if (!Files.isDirectory(cache)) return null;
        try (var paths = Files.find(cache, 4, (path, attributes) ->
                attributes.isRegularFile() && "chromedriver".equals(path.getFileName().toString())
                        && Files.isExecutable(path))) {
            return paths.sorted(Comparator.reverseOrder()).findFirst().orElse(null);
        }
    }

    private static String graph(String bindingRef) {
        return """
                graph codexProfileOps {
                  input { userId: String }
                  node profile : "%s" {
                    input { params = { userId: ctx.userId } }
                  }
                  transform response {
                    name = profile.output.payload.name
                    tier = profile.output.payload.tier
                  }
                }
                """.formatted(bindingRef);
    }

    private static String walletGraph(String bindingRef) {
        return """
                graph codexWalletBalance {
                  input { userId: String }
                  node wallet : "%s" {
                    input { params = { userId: ctx.userId } }
                  }
                  transform response {
                    amount = wallet.output.payload.amount
                    currency = wallet.output.payload.currency
                  }
                }
                """.formatted(bindingRef);
    }

    private static String policyGraph() {
        return """
                graph codexProfilePriority {
                  input { userId: String }
                  node profile : "codex-profile:read" {
                    input { params = { userId: ctx.userId } }
                  }
                  decision_table policy(
                    tier = profile.output.payload.data.tier
                  ) hit=first -> { decision: String } {
                    rule (tier: tier == "premium") -> { decision: "PRIORITY" }
                    otherwise -> { decision: "STANDARD" }
                  }
                  transform response {
                    name = profile.output.payload.data.name
                    decision = policy.output.decision
                  }
                }
                """;
    }

    private static String profileLibrary(String bindingRef) {
        return """
                schemaVersion: bloge.visualLibraryAuthoring.v1
                library: { id: codex-profile-ops-library-test, name: Profile operations, version: 1.0.0, owner: profile-ops }
                defaults: { operatorVersion: 1.0.0, namespace: codex-profile }
                types:
                  ProfileParams:
                    fields: { userId: string }
                  ProfileData:
                    fields: { userId: string, name: string, email: string, tier: string }
                  ProfileEnvelope:
                    fields: { code: integer, message: string, data: ProfileData }
                operators:
                  codex-profile:read:
                    name: Customer profile
                    archetype: resource-read
                    requiresSecrets: false
                    input: { params: ProfileParams }
                    output: { payload: ProfileEnvelope }
                    runtime: { bindingRef: "%s" }
                """.formatted(bindingRef);
    }

    private static Map<String, Object> profileEnvelope(String userId) {
        return Map.of(
                "code", 0,
                "message", "ok",
                "data", Map.of(
                        "userId", userId,
                        "name", "Alice",
                        "email", "alice@example.com",
                        "tier", "premium"));
    }

    private static Map<String, Object> profileEnvelopeSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "code", Map.of("type", "integer"),
                        "message", Map.of("type", "string"),
                        "data", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "userId", Map.of("type", "string"),
                                        "name", Map.of("type", "string"),
                                        "email", Map.of("type", "string"),
                                        "tier", Map.of("type", "string")),
                                "required", List.of("userId", "name", "email", "tier"),
                                "additionalProperties", false)),
                "required", List.of("code", "message", "data"),
                "additionalProperties", false);
    }

}
