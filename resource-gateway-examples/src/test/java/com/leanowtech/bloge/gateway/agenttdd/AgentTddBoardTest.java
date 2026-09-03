package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies the structure-only board, reviewed revision fence, and shipped browser entry point. */
class AgentTddBoardTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void boardProjectsScopedReadinessAndPendingOracleWithoutProposedPayload() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        GraphDraft draft = mock(GraphDraft.class);
        when(draft.draftId()).thenReturn("risk-tool");
        when(draft.tenantId()).thenReturn("tenant-a");
        when(draft.namespace()).thenReturn("project-a");
        when(draft.environment()).thenReturn("test");
        when(draft.visualLayout()).thenReturn(Map.of("agentTdd", Map.of("assetKind", "TOOL")));
        when(draft.graphName()).thenReturn("riskTool");
        when(draft.inputSchema()).thenReturn(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.object(
                Map.of("amount", Map.of("type", "number")), List.of("amount")));
        when(draft.outputSchema()).thenReturn(com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.object(
                Map.of("decision", Map.of("type", "string")), List.of("decision")));
        when(draft.nodes()).thenReturn(List.of(new GraphDraft.DraftNode(
                "decide", "decision_table", "Decide waiver", Map.of(), Map.of(), null)));
        when(draft.edges()).thenReturn(List.of(new GraphDraft.DraftEdge(
                "input-to-decide", "data", new GraphDraft.Endpoint("input", "amount", ""),
                new GraphDraft.Endpoint("decide", "amount", ""))));
        when(drafts.all()).thenReturn(List.of(draft));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        ObjectNode caseSet = mapper.createObjectNode();
        caseSet.put("toolRef", "risk-tool");
        ObjectNode row = caseSet.putArray("rows").addObject();
        row.put("caseId", "g1");
        row.put("category", "GOLDEN");
        row.put("lifecycle", "DRAFT");
        row.put("qualityState", "DESIGNED_NOT_RUN");
        row.putObject("proposedOracle").put("status", "PENDING").put("oracleOwner", "cx-ops")
                .put("proposedBy", "codex-agent").put("proposalFingerprint", "sha256:proposal")
                .putObject("expect").put("decision", "WAIVE");
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", caseSet);
        ObjectNode verdict = mapper.createObjectNode();
        verdict.putObject("latest")
                .put("side", "GREEN")
                .put("status", "GO")
                .put("draftRevision", 7)
                .put("goldenSetId", "sha256:golden")
                .put("evidenceFingerprint", "sha256:evidence");
        states.save(scope(), AgentTddWorkflowService.VERDICT, "risk-tool", verdict);
        AgentTddWorkflowService workflow = mock(AgentTddWorkflowService.class);
        when(workflow.readiness(any(), eq(identity()))).thenReturn(Map.of(
                "toolRef", "risk-tool", "state", "IMPLEMENTED", "goldenSetId", "sha256:golden",
                "publishable", false,
                "gates", Map.of("bindingsComplete", true, "greenBaseline", true,
                        "runtimeAttestation", true, "ownerSignoff", false),
                "attestation", Map.of("implementationFingerprint", "sha256:implementation"),
                "remainingLimitations", List.of("OWNER_SIGNOFF_ABSENT")));

        Map<String, Object> board = new AgentTddBoardService(drafts, states, workflow, mapper).board(identity());

        assertThat((List<?>) board.get("tools")).singleElement().satisfies(value -> {
            Map<?, ?> tool = (Map<?, ?>) value;
            assertThat(tool.get("contract").toString()).contains("amount", "decision", "required=true");
            assertThat(tool.get("structure").toString()).contains("Decide waiver", "decision_table", "input");
            assertThat(tool.get("caseTable").toString()).contains("g1", "GOLDEN", "DRAFT")
                    .doesNotContain("expect", "given", "stubs", "WAIVE");
        });
        List<?> pendingReviews = (List<?>) board.get("pendingReviews");
        assertThat(pendingReviews.stream()
                .map(value -> ((Map<?, ?>) value).get("kind").toString()).toList())
                .containsExactly("ORACLE", "PUBLISH_SIGNOFF");
        assertThat(pendingReviews).allSatisfy(value -> {
            Map<?, ?> review = (Map<?, ?>) value;
            assertThat(review.containsKey("expect")).isFalse();
            assertThat(review.containsKey("given")).isFalse();
            assertThat(review.containsKey("stubs")).isFalse();
        });
        Map<?, ?> signoff = pendingReviews.stream()
                .map(value -> (Map<?, ?>) value)
                .filter(value -> "PUBLISH_SIGNOFF".equals(value.get("kind")))
                .findFirst().orElseThrow();
        assertThat(signoff.get("draftRevision")).isEqualTo(7L);
        assertThat(signoff.get("goldenSetId")).isEqualTo("sha256:golden");
        assertThat(signoff.get("evidenceFingerprint")).isEqualTo("sha256:evidence");
        assertThat(signoff.get("implementationFingerprint")).isEqualTo("sha256:implementation");
        assertThat(board).containsEntry("payloadPolicy", "STRUCTURE_ONLY");
    }

    @Test
    void boardDerivesTheFiveActJourneyStageAndNextBusinessAction() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        List<GraphDraft> toolDrafts = List.of(
                toolDraft("01-resources", "SPECCING"),
                toolDraft("02-orchestration", "IMPLEMENTING"),
                toolDraft("03-golden", "IMPLEMENTING"),
                toolDraft("04-green", "IMPLEMENTED"),
                toolDraft("05-publishable", "IMPLEMENTED"));
        when(drafts.all()).thenReturn(toolDrafts);
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        ObjectNode caseSet = mapper.createObjectNode().put("toolRef", "03-golden");
        caseSet.putArray("rows").addObject()
                .put("caseId", "pending-golden")
                .put("lifecycle", "DRAFT")
                .putObject("proposedOracle").put("status", "PENDING");
        states.save(scope(), AgentTddMutationService.CASE_SET, "journey-cases", caseSet);
        AgentTddWorkflowService workflow = mock(AgentTddWorkflowService.class);
        Map<String, Map<String, Object>> readiness = Map.of(
                "01-resources", readiness("01-resources", "SPECCING", false, false),
                "02-orchestration", readiness("02-orchestration", "IMPLEMENTING", false, false),
                "03-golden", readiness("03-golden", "IMPLEMENTING", false, false),
                "04-green", readiness("04-green", "IMPLEMENTED", true, false),
                "05-publishable", readiness("05-publishable", "IMPLEMENTED", true, true));
        when(workflow.readiness(any(), eq(identity()))).thenAnswer(invocation -> readiness.get(
                ((com.fasterxml.jackson.databind.JsonNode) invocation.getArgument(0))
                        .path("toolRef").asText()));

        Map<String, Map<?, ?>> byTool = new LinkedHashMap<>();
        List<?> projectedTools = (List<?>) new AgentTddBoardService(drafts, states, workflow, mapper)
                .board(identity()).get("tools");
        projectedTools.forEach(value -> {
                    Map<?, ?> tool = (Map<?, ?>) value;
                    byTool.put(tool.get("toolRef").toString(), (Map<?, ?>) tool.get("journey"));
                });

        assertJourney(byTool.get("01-resources"), "RESOURCES", 1, "BIND_OR_FIXTURE");
        assertJourney(byTool.get("02-orchestration"), "ORCHESTRATION", 2, "ADD_GOLDEN");
        assertJourney(byTool.get("03-golden"), "GOLDEN", 3, "APPROVE_GOLDEN");
        assertJourney(byTool.get("04-green"), "PUBLISH", 4, "AWAIT_ATTEST_OR_SIGNOFF");
        assertJourney(byTool.get("05-publishable"), "PUBLISH", 4, "SIGNOFF_OR_PUBLISH");
        assertThat(projectedTools).allSatisfy(value -> {
            Map<?, ?> tool = (Map<?, ?>) value;
            assertThat((List<?>) tool.get("ruleMatrices")).isEmpty();
            assertThat(tool.get("flowSummary")).isEqualTo("接收输入事实 → 产出结果");
        });
    }

    @Test
    void boardProjectsBusinessRuleMatrixAndDeterministicFactCoverage() {
        GraphDraftRepository drafts = mock(GraphDraftRepository.class);
        GraphDraft draft = toolDraft("dispute-tool", "IMPLEMENTING");
        Map<String, Object> decisionConfig = Map.of(
                "hitPolicy", "unique",
                "conditionColumns", List.of(
                        Map.of("id", "party", "label", "责任方"),
                        Map.of("id", "severity", "label", "严重度")),
                "outputColumns", List.of(Map.of("id", "decision", "label", "处置")),
                "rules", List.of(
                        Map.of("id", "R1", "conditions", Map.of(
                                        "party", "party in {\"driver\",\"passenger\"}",
                                        "severity", "severity in {\"low\",\"medium\",\"high\"}"),
                                "output", Map.of("decision", "REVIEW")),
                        Map.of("id", "R2", "otherwise", true,
                                "outputs", Map.of("decision", "ESCALATE_HUMAN"))));
        when(draft.nodes()).thenReturn(List.of(
                new GraphDraft.DraftNode(
                        "facts", "resource:dispute.getFacts", "查询责任方", Map.of(), Map.of(), null),
                new GraphDraft.DraftNode(
                        "disputePolicy", "bloge:decisionTable", "Dispute policy",
                        Map.of(), decisionConfig, null)));
        when(draft.edges()).thenReturn(List.of(new GraphDraft.DraftEdge(
                "facts-policy", "data", new GraphDraft.Endpoint("facts", "payload", ""),
                new GraphDraft.Endpoint("disputePolicy", "inputs", "party"))));
        when(draft.operatorSnapshots()).thenReturn(Map.of("facts", readOperator()));
        when(drafts.all()).thenReturn(List.of(draft));
        InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
        ObjectNode caseSet = mapper.createObjectNode().put("toolRef", "dispute-tool");
        caseSet.putArray("rows").addObject().put("caseId", "g-driver-high")
                .put("category", "GOLDEN").put("lifecycle", "ACTIVE")
                .putObject("given").put("party", "driver").put("severity", "high");
        caseSet.withArray("rows").addObject().put("caseId", "g-passenger-low")
                .put("category", "GOLDEN").put("lifecycle", "ACTIVE")
                .putObject("given").put("party", "passenger").put("severity", "low");
        states.save(scope(), AgentTddMutationService.CASE_SET, "dispute-golden", caseSet);
        AgentTddWorkflowService workflow = mock(AgentTddWorkflowService.class);
        when(workflow.readiness(any(), eq(identity()))).thenReturn(
                readiness("dispute-tool", "IMPLEMENTING", false, false));

        Map<?, ?> tool = (Map<?, ?>) ((List<?>) new AgentTddBoardService(drafts, states, workflow, mapper)
                .board(identity()).get("tools")).getFirst();

        Map<?, ?> matrix = (Map<?, ?>) ((List<?>) tool.get("ruleMatrices")).getFirst();
        assertThat(matrix.get("nodeId")).isEqualTo("disputePolicy");
        assertThat(matrix.get("hitPolicy")).isEqualTo("unique");
        assertThat(matrix.get("conditionColumns").toString()).contains("责任方", "严重度");
        assertThat(matrix.get("rules").toString()).contains("party", "driver", "REVIEW");
        assertThat(matrix.get("otherwise").toString()).contains("ESCALATE_HUMAN");
        assertThat(tool.get("flowSummary").toString())
                .startsWith("取『查询责任方』事实 → 按『Dispute policy』规则表判定")
                .endsWith("产出结果");

        Map<?, ?> coverage = (Map<?, ?>) tool.get("factCoverage");
        assertThat(coverage.get("totalCount")).isEqualTo(6L);
        assertThat(coverage.get("coveredCount")).isEqualTo(2L);
        assertThat((List<?>) coverage.get("blindSpots")).hasSize(4);
        assertThat(coverage.get("blindSpots").toString())
                .contains("driver", "low", "medium", "passenger", "high");
    }

    @Test
    void controllerAuthenticatesReadAndGovernedApprovalSeparately() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        AgentTddBoardService board = mock(AgentTddBoardService.class);
        AgentTddLibraryOverviewService overview = mock(AgentTddLibraryOverviewService.class);
        AgentTddReviewService reviews = mock(AgentTddReviewService.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_READ)).thenReturn(identity());
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE)).thenReturn(identity());
        when(board.board(identity())).thenReturn(Map.of("tools", List.of()));
        when(overview.overview(identity())).thenReturn(Map.of(
                "buildingBlocks", List.of(), "worldModel", Map.of()));
        when(reviews.approveOracle("golden-1", "g1", 4, "sha256:proposal", identity())).thenReturn(
                new AgentTddStoredAsset(scope(), AgentTddMutationService.CASE_SET, "golden-1", 5,
                        "sha256:test", mapper.createObjectNode(), java.time.Instant.EPOCH));
        when(reviews.approveToolSignoff("risk-tool", "ops-42", 7, "sha256:golden",
                "sha256:evidence", "sha256:implementation", identity())).thenReturn(
                new AgentTddStoredAsset(scope(), AgentTddWorkflowService.SIGNOFF, "ops-42", 1,
                        "sha256:signoff", mapper.createObjectNode(), java.time.Instant.EPOCH));
        AgentTddBoardController controller = new AgentTddBoardController(
                authenticator, board, overview, reviews);

        controller.board(headers);
        Map<String, Object> libraryOverview = controller.libraryOverview(headers).getBody();
        Map<String, Object> approved = controller.approveOracle(
                "golden-1", "g1", new AgentTddBoardController.RevisionRequest(
                        4, "sha256:proposal"), headers);
        Map<String, Object> signed = controller.approveSignoff(
                "risk-tool", "ops-42", new AgentTddBoardController.SignoffRequest(
                        7, "sha256:golden", "sha256:evidence", "sha256:implementation"), headers);

        assertThat(approved).containsEntry("revision", 5L).containsEntry("status", "APPROVED");
        assertThat(signed).containsEntry("revision", 1L).containsEntry("status", "APPROVED");
        assertThat(libraryOverview).containsKey("buildingBlocks").containsKey("worldModel");
        verify(authenticator, times(2)).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
        verify(authenticator, times(2)).authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
    }

    @Test
    void libraryOverviewIsAnAuthenticatedNonCacheableHttpProjection() throws Exception {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        AgentTddLibraryOverviewService overview = mock(AgentTddLibraryOverviewService.class);
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenReturn(identity());
        when(overview.overview(identity())).thenReturn(Map.of(
                "buildingBlocks", List.of(Map.of(
                        "ref", "bloge:decisionTable", "kind", "BASE", "title", "按规则表判定")),
                "worldModel", Map.of("types", List.of(), "operations", List.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentTddBoardController(
                        authenticator, mock(AgentTddBoardService.class), overview,
                        mock(AgentTddReviewService.class)))
                .build();

        mvc.perform(get("/api/agent-tdd/library-overview")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer agent-token")
                        .header("X-Purpose", "AGENT_TDD_READ"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.buildingBlocks[0].title").value("按规则表判定"))
                .andExpect(jsonPath("$.worldModel.operations").isArray());
    }

    @Test
    void controllerMapsAuthenticationFailureToTheStableProblemBoundary() throws Exception {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED", "Authentication is required.",
                        "corr-auth", Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentTddBoardController(
                        authenticator, mock(AgentTddBoardService.class),
                        mock(AgentTddLibraryOverviewService.class), mock(AgentTddReviewService.class)))
                .setControllerAdvice(new IntegrationProblemHandler())
                .build();

        mvc.perform(get("/api/agent-tdd/board"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"resource-gateway-integration\""))
                .andExpect(jsonPath("$.code").value("RG.INTEGRATION.AUTHENTICATION_REQUIRED"));
    }

    @Test
    void staticBoardContainsNoAuthoringEditorAndDeclaresStructureOnlyPolicy() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/static/agent-tdd.html")) {
            assertThat(input).isNotNull();
            String html = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(html).contains("Agent TDD 看板", "STRUCTURE_ONLY", "expectedRevision",
                            "五幕业务旅程", "业务主线 · 各工具进行到哪一幕",
                            "第1幕 · 你的世界观与可用积木", "buildingBlocks", "worldModel",
                            "/api/agent-tdd/library-overview", "仅契约", "已接入",
                            "第2幕 · 已提供样例", "providedFixtures", "sourceKind",
                            "NEXT_ACTION_LABELS", "journey-dot", "输入 / 输出契约",
                            "renderRuleMatrix", "覆盖 · 已覆盖", "查看事实组合盲区",
                            "展开查看技术结构", "场景表", "PUBLISH_SIGNOFF",
                            "/reviews/tools/", "signoffRef", "实景验证",
                            "data-attestation-rerun", "/attestations/")
                    .doesNotContain("contenteditable", "libraryYaml", "tool.compose");
        }
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(identity());
    }

    private static GraphDraft toolDraft(String ref, String state) {
        GraphDraft draft = mock(GraphDraft.class);
        when(draft.draftId()).thenReturn(ref);
        when(draft.tenantId()).thenReturn("tenant-a");
        when(draft.namespace()).thenReturn("project-a");
        when(draft.environment()).thenReturn("test");
        when(draft.status()).thenReturn(state);
        when(draft.graphName()).thenReturn(ref);
        when(draft.visualLayout()).thenReturn(Map.of("agentTdd", Map.of("assetKind", "TOOL")));
        when(draft.nodes()).thenReturn(List.of());
        when(draft.edges()).thenReturn(List.of());
        return draft;
    }

    private static OperatorDefinition readOperator() {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", "resource:dispute.getFacts", "1.0.0",
                new OperatorDefinition.Display("查询责任方", "", List.of("resource-read")),
                new OperatorDefinition.Source(
                        "resource-descriptor", "dispute.getFacts", "GET", "/facts", true),
                new OperatorDefinition.Ports(List.of(), List.of()),
                com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities("READ_EXTERNAL", "IDEMPOTENT", false, false, false),
                new OperatorDefinition.Lowering(
                        "resource-descriptor", "httpResource", Map.of("resourceId", "dispute.getFacts")),
                List.of());
    }

    private static Map<String, Object> readiness(String toolRef,
                                                  String state,
                                                  boolean green,
                                                  boolean publishable) {
        return Map.of(
                "toolRef", toolRef,
                "state", state,
                "publishable", publishable,
                "gates", Map.of("greenBaseline", green, "ownerSignoff", publishable),
                "remainingLimitations", publishable ? List.of() : List.of("NEXT_GATE_ABSENT"));
    }

    private static void assertJourney(Map<?, ?> journey,
                                      String stage,
                                      int stageIndex,
                                      String nextAction) {
        assertThat(journey).isNotNull();
        assertThat(journey.get("stage")).isEqualTo(stage);
        assertThat(journey.get("stageIndex")).isEqualTo(stageIndex);
        assertThat(journey.get("nextAction")).isEqualTo(nextAction);
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_GOVERNANCE", "corr-1");
    }
}
