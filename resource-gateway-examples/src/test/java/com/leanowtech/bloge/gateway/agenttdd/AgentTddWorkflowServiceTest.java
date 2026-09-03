package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionException;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Proves durable evidence identity and fail-closed publication governance. */
class AgentTddWorkflowServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private InMemoryAgentTddStateRepository states;
    private GraphDraftRepository drafts;
    private GraphDraft draft;
    private GraphNodeFixturePromotionService fixtures;
    private AgentTddWorkflowService service;

    @BeforeEach
    void setUp() {
        states = new InMemoryAgentTddStateRepository();
        drafts = mock(GraphDraftRepository.class);
        draft = mock(GraphDraft.class);
        fixtures = mock(GraphNodeFixturePromotionService.class);
        when(draft.draftId()).thenReturn("risk-tool");
        when(draft.tenantId()).thenReturn("tenant-a");
        when(draft.environment()).thenReturn("test");
        when(draft.revision()).thenReturn(4L);
        when(draft.operatorSnapshots()).thenReturn(Map.of());
        when(drafts.find("risk-tool")).thenReturn(Optional.of(draft));
        service = new AgentTddWorkflowService(states, drafts, fixtures,
                mock(VisualGraphRunService.class), mock(VisualOperatorCatalog.class),
                mock(VisualGraphPublicationRepository.class), mapper);
    }

    @Test
    void recordsAndReadsOneGoldenIdentityLineWithoutBusinessPayload() {
        Map<String, Object> result = Map.of(
                "goldenSetId", "sha256:golden", "side", "RED",
                "cases", List.of(Map.of("caseId", "g1", "verdict", "RED_PASS")),
                "realExternalCalls", 0);

        Map<String, Object> recorded = service.recordEvidence(
                "rg.simulate", json(Map.of("toolRef", "risk-tool")), result, identity());
        Map<String, Object> verdict = service.verdict(
                json(Map.of("toolRef", "risk-tool")), identity());
        Map<String, Object> evidence = service.evidence(
                json(Map.of("evidenceRef", recorded.get("evidenceRef"))), identity());

        assertThat(recorded.get("evidenceRef")).isEqualTo("risk-tool:RED:sha256:golden");
        assertThat(verdict).containsEntry("goldenSetId", "sha256:golden")
                .containsEntry("state", "IMPLEMENTING");
        assertThat(evidence).containsEntry("operation", "rg.simulate")
                .doesNotContainKeys("given", "output", "payload");
    }

    @Test
    void publishSpecIsPendingIdempotentAndRequiresExactRevisionForApproval() {
        JsonNode arguments = json(Map.of(
                "toolRef", "risk-tool", "idempotencyKey", "spec-1"));

        Map<String, Object> first = service.publishSpec(arguments, identity());
        Map<String, Object> replay = service.publishSpec(arguments, identity());
        AgentTddStoredAsset proposal = states.find(scope(), AgentTddWorkflowService.PUBLISH_SPEC,
                "risk-tool").orElseThrow();

        assertThat(first).isEqualTo(replay).containsEntry("proposalStatus", "PENDING");
        assertThatThrownBy(() -> new AgentTddReviewService(states).approvePublishSpec(
                "risk-tool", proposal.revision() + 1, identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        AgentTddStoredAsset approved = new AgentTddReviewService(states).approvePublishSpec(
                "risk-tool", proposal.revision(), identity());
        assertThat(approved.data().path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void executablePublishRejectsMissingGreenBaselineBeforeCallingCompiler() {
        new AgentTddReviewService(states).approveToolSignoff("risk-tool", "signoff-1", identity());

        assertThatThrownBy(() -> service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1", "idempotencyKey", "publish-1")),
                identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PUBLISH_GATE_NOT_MET"));
    }

    @Test
    void fixtureWrapperMapsLegacyMultiOutputFailureToStableMcpCode() {
        when(fixtures.promote(any(String.class), any(String.class), any(String.class),
                any(com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionRequest.class),
                any(IntegrationRequestContext.class))).thenThrow(
                new GraphNodeFixturePromotionException(422,
                        "RG.VISUAL.PROMOTION.OUTPUT_SCHEMA_NON_UNIQUE", "Select a port"));

        assertThatThrownBy(() -> service.promoteFixture(json(Map.of(
                "draftId", "risk-tool", "nodeId", "facts", "outputPort", "payload",
                "fixtureId", "facts-1", "category", "INTERNAL", "retentionDays", 3,
                "redactPaths", List.of("$.payload.secret"), "idempotencyKey", "fixture-1")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("AMBIGUOUS_OUTPUT_PORT"));
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(identity());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_GOVERNED_WRITE", "corr-1");
    }
}
