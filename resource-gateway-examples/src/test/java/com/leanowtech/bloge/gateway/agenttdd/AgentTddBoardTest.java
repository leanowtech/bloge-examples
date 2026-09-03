package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.leanowtech.bloge.gateway.integration.IntegrationOperation;
import com.leanowtech.bloge.gateway.integration.IntegrationProblem;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemException;
import com.leanowtech.bloge.gateway.integration.IntegrationProblemHandler;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestAuthenticator;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
                "gates", Map.of("bindingsComplete", true, "greenBaseline", true, "ownerSignoff", false),
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
        assertThat(board).containsEntry("payloadPolicy", "STRUCTURE_ONLY");
    }

    @Test
    void controllerAuthenticatesReadAndGovernedApprovalSeparately() {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        AgentTddBoardService board = mock(AgentTddBoardService.class);
        AgentTddReviewService reviews = mock(AgentTddReviewService.class);
        HttpHeaders headers = new HttpHeaders();
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_READ)).thenReturn(identity());
        when(authenticator.authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE)).thenReturn(identity());
        when(board.board(identity())).thenReturn(Map.of("tools", List.of()));
        when(reviews.approveOracle("golden-1", "g1", 4, "sha256:proposal", identity())).thenReturn(
                new AgentTddStoredAsset(scope(), AgentTddMutationService.CASE_SET, "golden-1", 5,
                        "sha256:test", mapper.createObjectNode(), java.time.Instant.EPOCH));
        when(reviews.approveToolSignoff("risk-tool", "ops-42", 7, "sha256:golden",
                "sha256:evidence", identity())).thenReturn(
                new AgentTddStoredAsset(scope(), AgentTddWorkflowService.SIGNOFF, "ops-42", 1,
                        "sha256:signoff", mapper.createObjectNode(), java.time.Instant.EPOCH));
        AgentTddBoardController controller = new AgentTddBoardController(authenticator, board, reviews);

        controller.board(headers);
        Map<String, Object> approved = controller.approveOracle(
                "golden-1", "g1", new AgentTddBoardController.RevisionRequest(
                        4, "sha256:proposal"), headers);
        Map<String, Object> signed = controller.approveSignoff(
                "risk-tool", "ops-42", new AgentTddBoardController.SignoffRequest(
                        7, "sha256:golden", "sha256:evidence"), headers);

        assertThat(approved).containsEntry("revision", 5L).containsEntry("status", "APPROVED");
        assertThat(signed).containsEntry("revision", 1L).containsEntry("status", "APPROVED");
        verify(authenticator).authenticate(headers, IntegrationOperation.AGENT_TDD_READ);
        verify(authenticator, times(2)).authenticate(headers, IntegrationOperation.AGENT_TDD_GOVERNED_WRITE);
    }

    @Test
    void controllerMapsAuthenticationFailureToTheStableProblemBoundary() throws Exception {
        IntegrationRequestAuthenticator authenticator = mock(IntegrationRequestAuthenticator.class);
        when(authenticator.authenticate(any(HttpHeaders.class), eq(IntegrationOperation.AGENT_TDD_READ)))
                .thenThrow(new IntegrationProblemException(IntegrationProblem.unauthorized(
                        "RG.INTEGRATION.AUTHENTICATION_REQUIRED", "Authentication is required.",
                        "corr-auth", Map.of())));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentTddBoardController(
                        authenticator, mock(AgentTddBoardService.class), mock(AgentTddReviewService.class)))
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
                            "输入 / 输出契约", "步骤", "场景表", "PUBLISH_SIGNOFF",
                            "/reviews/tools/", "signoffRef")
                    .doesNotContain("contenteditable", "libraryYaml", "tool.compose");
        }
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(identity());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_GOVERNANCE", "corr-1");
    }
}
