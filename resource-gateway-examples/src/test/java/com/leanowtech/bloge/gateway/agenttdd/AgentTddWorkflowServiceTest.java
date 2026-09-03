package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.codegen.DslGenerationResult;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.publication.InMemoryVisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.publication.VisualGraphPublicationRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.validation.VisualGraphActionReadiness;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionException;
import com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Proves durable evidence identity and fail-closed publication governance. */
class AgentTddWorkflowServiceTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private InMemoryAgentTddStateRepository states;
    private GraphDraftRepository drafts;
    private GraphDraft draft;
    private GraphNodeFixturePromotionService fixtures;
    private VisualGraphRunService runner;
    private InMemoryVisualGraphPublicationRepository publications;
    private AgentTddWorkflowService service;

    @BeforeEach
    void setUp() {
        states = new InMemoryAgentTddStateRepository();
        drafts = mock(GraphDraftRepository.class);
        draft = mock(GraphDraft.class);
        fixtures = mock(GraphNodeFixturePromotionService.class);
        runner = mock(VisualGraphRunService.class);
        publications = new InMemoryVisualGraphPublicationRepository();
        when(draft.draftId()).thenReturn("risk-tool");
        when(draft.tenantId()).thenReturn("tenant-a");
        when(draft.environment()).thenReturn("test");
        when(draft.revision()).thenReturn(4L);
        when(draft.graphName()).thenReturn("riskTool");
        when(draft.namespace()).thenReturn("project-a");
        when(draft.nodes()).thenReturn(List.of());
        when(draft.edges()).thenReturn(List.of());
        when(draft.operatorSnapshots()).thenReturn(Map.of());
        when(draft.operatorFingerprints()).thenReturn(Map.of());
        when(draft.visualLayout()).thenReturn(Map.of());
        when(draft.inputSchema()).thenReturn(SchemaEnvelope.opaque());
        when(draft.outputSchema()).thenReturn(SchemaEnvelope.opaque());
        when(drafts.find("risk-tool")).thenReturn(Optional.of(draft));
        service = new AgentTddWorkflowService(states, drafts, fixtures,
                runner, mock(VisualOperatorCatalog.class), publications, mapper);
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

        assertThat(recorded.get("evidenceRef")).asString()
                .startsWith("risk-tool:RED:sha256:golden:sha256:");
        assertThat(verdict).containsEntry("goldenSetId", "sha256:golden")
                .containsEntry("state", "IMPLEMENTING");
        assertThat(evidence).containsEntry("operation", "rg.simulate")
                .doesNotContainKeys("given", "output", "payload");
    }

    @Test
    void keepsDistinctEvidenceArtifactsWhenTheSameGoldenLineChanges() {
        Map<String, Object> failed = Map.of(
                "goldenSetId", "sha256:golden", "side", "RED",
                "cases", List.of(Map.of("caseId", "g1", "verdict", "RED_FAIL")),
                "realExternalCalls", 0);
        Map<String, Object> passed = Map.of(
                "goldenSetId", "sha256:golden", "side", "RED",
                "cases", List.of(Map.of("caseId", "g1", "verdict", "RED_PASS")),
                "realExternalCalls", 0);

        String failedRef = service.recordEvidence(
                "rg.simulate", json(Map.of("toolRef", "risk-tool")), failed, identity()).get("evidenceRef").toString();
        String passedRef = service.recordEvidence(
                "rg.simulate", json(Map.of("toolRef", "risk-tool")), passed, identity()).get("evidenceRef").toString();

        assertThat(failedRef).isNotEqualTo(passedRef);
        assertThat(states.list(scope(), AgentTddWorkflowService.EVIDENCE)).hasSize(2);
        assertThat(service.evidence(json(Map.of("evidenceRef", failedRef)), identity()).toString())
                .contains("RED_FAIL");
    }

    @Test
    void mergesRedAndGreenLayerCellsAndDerivesGoldenBusinessBacklog() {
        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:golden", "side", "RED",
                "byLayer", Map.of("contract", Map.of("pass", 0, "fail", 1)),
                "cases", List.of(Map.of("caseId", "g1", "category", "GOLDEN",
                        "layer", "contract", "verdict", "RED_FAIL", "oracleOwner", "risk-owner")),
                "realExternalCalls", 0), identity());
        JsonNode redVerdict = json(service.verdict(json(Map.of("toolRef", "risk-tool")), identity()));
        assertThat(redVerdict.at("/businessBacklog/0/caseId").asText()).isEqualTo("g1");
        assertThat(redVerdict.at("/businessBacklog/0/owner").asText()).isEqualTo("risk-owner");
        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:golden", "side", "GREEN",
                "byLayer", Map.of("contract", Map.of("pass", 1, "fail", 0)),
                "cases", List.of(Map.of("caseId", "g1", "category", "GOLDEN",
                        "layer", "contract", "verdict", "GREEN_PASS", "oracleOwner", "risk-owner")),
                "realExternalCalls", 0), identity());

        JsonNode verdict = json(service.verdict(json(Map.of("toolRef", "risk-tool")), identity()));

        assertThat(verdict.at("/byLayer/contract/red/fail").asInt()).isEqualTo(1);
        assertThat(verdict.at("/byLayer/contract/green/pass").asInt()).isEqualTo(1);
        assertThat(verdict.path("businessBacklog")).isEmpty();
        assertThat(verdict.at("/byLayer/unit/red/pass").asInt()).isZero();
        assertThat(verdict.at("/byLayer/smoke/green/fail").asInt()).isZero();
    }

    @Test
    void startsANewMatrixForANewGoldenSetAndKeepsThePriorLineQueryable() {
        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:line-a", "side", "GREEN",
                "byLayer", Map.of("contract", Map.of("pass", 1, "fail", 0)),
                "cases", List.of(), "realExternalCalls", 0), identity());
        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:line-b", "side", "RED",
                "byLayer", Map.of("contract", Map.of("pass", 0, "fail", 1)),
                "cases", List.of(), "realExternalCalls", 0), identity());

        JsonNode current = json(service.verdict(json(Map.of("toolRef", "risk-tool")), identity()));
        JsonNode archived = json(service.verdict(json(Map.of(
                "toolRef", "risk-tool", "goldenSetId", "sha256:line-a")), identity()));

        assertThat(current.path("goldenSetId").asText()).isEqualTo("sha256:line-b");
        assertThat(current.at("/byLayer/contract/green/pass").asInt()).isZero();
        assertThat(archived.path("goldenSetId").asText()).isEqualTo("sha256:line-a");
        assertThat(archived.at("/byLayer/contract/green/pass").asInt()).isEqualTo(1);
    }

    @Test
    void marksPassingActiveDurableCasesReadyWithoutChangingTheirLifecycle() {
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE",
                        "qualityState", "DESIGNED_NOT_RUN")))));
        long caseSetRevision = states.find(scope(), AgentTddMutationService.CASE_SET, "golden-1")
                .orElseThrow().revision();

        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:ready", "caseSetRef", "golden-1",
                "caseSetRevision", caseSetRevision, "side", "RED",
                "cases", List.of(Map.of("caseId", "g1", "verdict", "RED_PASS")),
                "realExternalCalls", 0), identity());

        JsonNode row = states.find(scope(), AgentTddMutationService.CASE_SET, "golden-1")
                .orElseThrow().data().path("rows").get(0);
        assertThat(row.path("lifecycle").asText()).isEqualTo("ACTIVE");
        assertThat(row.path("qualityState").asText()).isEqualTo("READY");
    }

    @Test
    void rejectsReadyTransitionWhenDurableCasesChangedAfterExecution() {
        AgentTddStoredAsset executed = states.save(
                scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                        "toolRef", "risk-tool", "rows", List.of(Map.of(
                                "caseId", "g1", "lifecycle", "ACTIVE",
                                "qualityState", "DESIGNED_NOT_RUN", "expect", "ALLOW")))));
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE",
                        "qualityState", "DESIGNED_NOT_RUN", "expect", "DENY")))));

        assertThatThrownBy(() -> service.recordEvidence(
                "rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                        "goldenSetId", "sha256:stale", "caseSetRef", "golden-1",
                        "caseSetRevision", executed.revision(), "side", "GREEN",
                        "cases", List.of(Map.of("caseId", "g1", "verdict", "GREEN_PASS")),
                        "realExternalCalls", 0), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        assertThat(states.list(scope(), AgentTddWorkflowService.EVIDENCE)).isEmpty();
        assertThat(states.find(scope(), AgentTddMutationService.CASE_SET, "golden-1")
                .orElseThrow().data().at("/rows/0/qualityState").asText())
                .isEqualTo("DESIGNED_NOT_RUN");
    }

    @Test
    void rollsBackReadyTransitionWhenAFollowingEvidenceWriteFails() {
        states = spy(new InMemoryAgentTddStateRepository());
        doAnswer(invocation -> {
            if (AgentTddWorkflowService.EVIDENCE.equals(invocation.getArgument(1))) {
                throw new IllegalStateException("simulated durable evidence failure");
            }
            return invocation.callRealMethod();
        }).when(states).save(any(), any(), any(), any());
        service = new AgentTddWorkflowService(states, drafts, fixtures,
                runner, mock(VisualOperatorCatalog.class), publications, mapper);
        AgentTddStoredAsset cases = states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1",
                json(Map.of("toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE",
                        "qualityState", "DESIGNED_NOT_RUN")))));

        assertThatThrownBy(() -> service.recordEvidence(
                "rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                        "goldenSetId", "sha256:rollback", "caseSetRef", "golden-1",
                        "caseSetRevision", cases.revision(), "side", "GREEN",
                        "cases", List.of(Map.of("caseId", "g1", "verdict", "GREEN_PASS")),
                        "realExternalCalls", 0), identity()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("evidence failure");
        assertThat(states.find(scope(), AgentTddMutationService.CASE_SET, "golden-1")
                .orElseThrow().data().at("/rows/0/qualityState").asText())
                .isEqualTo("DESIGNED_NOT_RUN");
        assertThat(states.list(scope(), AgentTddWorkflowService.EVIDENCE)).isEmpty();
    }

    @Test
    void locksExecutedRevisionEvenWhenNoReadyMutationIsNeeded() {
        states = spy(new InMemoryAgentTddStateRepository());
        AgentTddStoredAsset cases = states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1",
                json(Map.of("toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE", "qualityState", "READY")))));
        service = new AgentTddWorkflowService(states, drafts, fixtures,
                runner, mock(VisualOperatorCatalog.class), publications, mapper);

        service.recordEvidence("rg.simulate", json(Map.of("toolRef", "risk-tool")), Map.of(
                "goldenSetId", "sha256:locked", "caseSetRef", "golden-1",
                "caseSetRevision", cases.revision(), "side", "GREEN",
                "cases", List.of(Map.of("caseId", "g1", "verdict", "GREEN_FAIL")),
                "realExternalCalls", 0), identity());

        verify(states).lockRevision(scope(), AgentTddMutationService.CASE_SET,
                "golden-1", cases.revision());
        assertThat(states.find(scope(), AgentTddMutationService.CASE_SET, "golden-1")
                .orElseThrow().revision()).isEqualTo(cases.revision());
    }

    @Test
    void publishSpecIsPendingIdempotentAndRequiresExactRevisionForApproval() {
        JsonNode arguments = json(Map.of(
                "toolRef", "risk-tool", "idempotencyKey", "spec-1"));

        Map<String, Object> first = service.publishSpec(arguments, authorIdentity());
        Map<String, Object> replay = service.publishSpec(arguments, authorIdentity());
        AgentTddStoredAsset proposal = states.find(scope(), AgentTddWorkflowService.PUBLISH_SPEC,
                "risk-tool").orElseThrow();

        assertThat(first).isEqualTo(replay).containsEntry("proposalStatus", "PENDING");
        String proposalFingerprint = proposal.data().path("proposalFingerprint").asText();
        assertThatThrownBy(() -> new AgentTddReviewService(states).approvePublishSpec(
                "risk-tool", proposal.revision() + 1, proposalFingerprint, identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        AgentTddStoredAsset approved = new AgentTddReviewService(states).approvePublishSpec(
                "risk-tool", proposal.revision(), proposalFingerprint, identity());
        assertThat(approved.data().path("status").asText()).isEqualTo("APPROVED");
    }

    @Test
    void governanceRejectsWorkloadSignoffAndSelfApproval() {
        AgentTddReviewService review = new AgentTddReviewService(states);
        Map<String, Object> proposalResult = service.publishSpec(json(Map.of(
                "toolRef", "risk-tool", "idempotencyKey", "spec-separation")), identity());
        AgentTddStoredAsset proposal = states.find(scope(), AgentTddWorkflowService.PUBLISH_SPEC,
                "risk-tool").orElseThrow();

        assertThatThrownBy(() -> review.approveToolSignoff(
                "risk-tool", "workload-signoff", 4, "sha256:golden", "sha256:evidence",
                "sha256:implementation", authorIdentity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        assertThatThrownBy(() -> review.approvePublishSpec(
                "risk-tool", ((Number) proposalResult.get("revision")).longValue(),
                proposal.data().path("proposalFingerprint").asText(), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
    }

    @Test
    void executablePublishRejectsMissingGreenBaselineBeforeCallingCompiler() {
        assertThatThrownBy(() -> new AgentTddReviewService(states).approveToolSignoff(
                "risk-tool", "signoff-1", 4, "sha256:missing", "sha256:missing",
                "sha256:implementation", identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));

        assertThatThrownBy(() -> service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1", "idempotencyKey", "publish-1")),
                identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PUBLISH_GATE_NOT_MET"));
    }

    @Test
    void executablePublishCreatesImmutableArtifactAfterCurrentGreenBaselineAndSignoff() {
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE")))));
        String goldenSetId = AgentTddExecutionService.goldenSetId(mapper, "risk-tool", draft, List.of("g1"));
        String evidenceFingerprint = currentEvidenceFingerprint("golden-1", "GREEN");
        service.recordEvidence("rg.tool.baseline", json(Map.of("toolRef", "risk-tool")), Map.of(
                "status", "GO", "goldenSetId", goldenSetId, "side", "GREEN",
                "caseSetRef", "golden-1", "caseSetRevision", currentCaseSetRevision("golden-1"),
                "draftRevision", 4, "evidenceFingerprint", evidenceFingerprint,
                "businessFingerprintStable", true, "realExternalCalls", 0), identity());
        assertThatThrownBy(() -> service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1",
                "idempotencyKey", "publish-without-attestation")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PUBLISH_GATE_NOT_MET"));
        storeAttestation(goldenSetId, evidenceFingerprint);
        assertThatThrownBy(() -> new AgentTddReviewService(states).approveToolSignoff(
                "risk-tool", "signoff-wrong-implementation", 4, goldenSetId,
                evidenceFingerprint, "sha256:other-implementation", identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        new AgentTddReviewService(states).approveToolSignoff(
                "risk-tool", "signoff-1", 4, goldenSetId, evidenceFingerprint,
                "sha256:implementation", identity());
        VisualValidationResult validation = mock(VisualValidationResult.class);
        VisualGraphActionReadiness actionReadiness = mock(VisualGraphActionReadiness.class);
        when(validation.valid()).thenReturn(true);
        when(validation.actionReadiness()).thenReturn(actionReadiness);
        when(actionReadiness.publishExecutableNow()).thenReturn(true);
        when(runner.compileAgainst(any(GraphDraft.class), any(VisualOperatorCatalog.class)))
                .thenReturn(new DslGenerationResult(
                true, "graph riskTool {}", List.of(), validation));

        Map<String, Object> published = service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1",
                "idempotencyKey", "publish-1")), identity());

        assertThat(published).containsEntry("artifactKind", "EXECUTABLE")
                .containsEntry("goldenSetId", goldenSetId);
        assertThat(published.get("publicationId")).asString().isNotBlank();
        assertThat(publications.all()).singleElement().satisfies(publication -> {
            assertThat(publication.draftId()).isEqualTo("risk-tool");
            assertThat(publication.publicationMetadata().reason()).isEqualTo("signoff-1");
        });
    }

    @Test
    void executablePublishRejectsAStableRedBaselineEvenAfterBindingAndSignoff() {
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE", "expect", Map.of())))));
        String goldenSetId = AgentTddExecutionService.goldenSetId(mapper, "risk-tool", draft, List.of("g1"));
        String evidenceFingerprint = currentEvidenceFingerprint("golden-1", "RED");
        service.recordEvidence("rg.tool.baseline", json(Map.of("toolRef", "risk-tool")), Map.of(
                "status", "GO", "goldenSetId", goldenSetId, "caseSetRef", "golden-1",
                "caseSetRevision", currentCaseSetRevision("golden-1"),
                "side", "RED", "draftRevision", 4, "evidenceFingerprint", evidenceFingerprint,
                "businessFingerprintStable", true, "realExternalCalls", 0), identity());
        assertThatThrownBy(() -> new AgentTddReviewService(states).approveToolSignoff(
                "risk-tool", "signoff-1", 4, goldenSetId, evidenceFingerprint,
                "sha256:implementation", identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));

        assertThatThrownBy(() -> service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1",
                "idempotencyKey", "publish-red")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PUBLISH_GATE_NOT_MET"));
    }

    @Test
    void changingAnApprovedCaseInvalidatesGreenEvidenceAndOwnerSignoff() {
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE", "expect", Map.of("decision", "ALLOW"),
                        "stubs", Map.of())))));
        String goldenSetId = AgentTddExecutionService.goldenSetId(mapper, "risk-tool", draft, List.of("g1"));
        String evidenceFingerprint = currentEvidenceFingerprint("golden-1", "GREEN");
        service.recordEvidence("rg.tool.baseline", json(Map.of("toolRef", "risk-tool")), Map.of(
                "status", "GO", "goldenSetId", goldenSetId, "side", "GREEN",
                "caseSetRef", "golden-1", "caseSetRevision", currentCaseSetRevision("golden-1"),
                "draftRevision", 4,
                "evidenceFingerprint", evidenceFingerprint,
                "businessFingerprintStable", true, "realExternalCalls", 0), identity());
        storeAttestation(goldenSetId, evidenceFingerprint);
        new AgentTddReviewService(states).approveToolSignoff(
                "risk-tool", "signoff-1", 4, goldenSetId, evidenceFingerprint,
                "sha256:implementation", identity());
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE", "expect", Map.of("decision", "DENY"),
                        "stubs", Map.of())))));

        Map<String, Object> readiness = service.readiness(
                json(Map.of("toolRef", "risk-tool")), identity());

        assertThat(readiness).containsEntry("publishable", false);
        assertThat(readiness.get("remainingLimitations")).asList()
                .contains("GREEN_BASELINE_ABSENT", "OWNER_SIGNOFF_ABSENT");
        assertThatThrownBy(() -> service.publish(json(Map.of(
                "toolRef", "risk-tool", "signoffRef", "signoff-1",
                "idempotencyKey", "publish-stale")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("PUBLISH_GATE_NOT_MET"));
    }

    @Test
    void readinessProjectsTheCurrentFailedAttestationForHumanRecovery() {
        states.save(scope(), AgentTddMutationService.CASE_SET, "golden-1", json(Map.of(
                "toolRef", "risk-tool", "rows", List.of(Map.of(
                        "caseId", "g1", "lifecycle", "ACTIVE")))));
        String goldenSetId = AgentTddExecutionService.goldenSetId(mapper, "risk-tool", draft, List.of("g1"));
        String evidenceFingerprint = currentEvidenceFingerprint("golden-1", "GREEN");
        service.recordEvidence("rg.tool.baseline", json(Map.of("toolRef", "risk-tool")), Map.of(
                "status", "GO", "goldenSetId", goldenSetId, "side", "GREEN",
                "caseSetRef", "golden-1", "caseSetRevision", currentCaseSetRevision("golden-1"),
                "draftRevision", 4, "evidenceFingerprint", evidenceFingerprint,
                "businessFingerprintStable", true, "realExternalCalls", 0), identity());
        states.save(scope(), AgentTddAttestationService.ATTESTATION, "risk-tool", json(Map.ofEntries(
                Map.entry("toolRef", "risk-tool"), Map.entry("status", "FAILED"),
                Map.entry("reasonCode", "ATTESTATION_ORACLE_MISMATCH"), Map.entry("environment", "test"),
                Map.entry("goldenSetId", goldenSetId), Map.entry("evidenceFingerprint", evidenceFingerprint),
                Map.entry("draftRevision", 4), Map.entry("caseSetRef", "golden-1"),
                Map.entry("realExternalCalls", 1), Map.entry("cases", List.of()),
                Map.entry("dependencies", List.of()))));

        Map<String, Object> readiness = service.readiness(
                json(Map.of("toolRef", "risk-tool")), identity());

        assertThat(readiness.get("attestation")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("status", "FAILED")
                .containsEntry("reasonCode", "ATTESTATION_ORACLE_MISMATCH")
                .containsEntry("realExternalCalls", 1);
        assertThat(readiness.get("gates")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("runtimeAttestation", false);
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

    @Test
    void directSampleWrapperDerivesFixtureIdentityAndMapsSchemaFailures() {
        when(fixtures.provide(eq("resource:applicant"), eq("payload"), eq(Map.of("score", 760)),
                any(com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionRequest.class),
                argThat(context -> "CORRECTNESS_FIXTURE_MATERIAL_WRITE".equals(context.purpose())
                        && context.actorId().equals(identity().actorId())))).thenAnswer(invocation -> {
                    var request = (com.leanowtech.bloge.gateway.visualadapter.fixture
                            .GraphNodeFixturePromotionRequest) invocation.getArgument(3);
                    return new GraphNodeFixturePromotionService.PromotionResult(
                            request.fixtureAssetId(), 1, "DRAFT",
                            new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol
                                    .ExactAssetRef("RESOURCE", "applicant", 1, "sha256:" + "a".repeat(64)),
                            new com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol
                                    .ExactSchemaRef("applicant", 1, "sha256:" + "b".repeat(64)),
                            "governed", "SAMPLE");
                });
        JsonNode request = json(Map.of(
                "operatorRef", "resource:applicant", "outputPort", "payload",
                "sampleValue", Map.of("score", 760), "category", "INTERNAL",
                "retentionDays", 3, "redactPaths", List.of("/secret"),
                "idempotencyKey", "provide-1"));

        Map<String, Object> provided = service.provideFixture(request, identity());

        assertThat(provided).containsEntry("sourceKind", "SAMPLE").containsEntry("scope", scope());
        assertThat(provided.get("fixtureId")).asString().startsWith("provided-");
        assertThat(provided).doesNotContainKeys("sampleValue", "payload");

        assertThatThrownBy(() -> service.provideFixture(json(Map.of(
                "operatorRef", "resource:applicant", "outputPort", "payload",
                "sampleValue", Map.of("score", 761), "category", "INTERNAL",
                "retentionDays", 3, "redactPaths", List.of("/secret"),
                "idempotencyKey", "provide-1")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));

        when(fixtures.provide(eq("resource:applicant"), eq("payload"), eq(Map.of("score", "bad")),
                any(com.leanowtech.bloge.gateway.visualadapter.fixture.GraphNodeFixturePromotionRequest.class),
                argThat(context -> "CORRECTNESS_FIXTURE_MATERIAL_WRITE".equals(context.purpose()))))
                .thenThrow(new GraphNodeFixturePromotionException(
                        422, "RG.VISUAL.PROMOTION.OUTPUT_SCHEMA_INVALID", "Schema mismatch"));
        assertThatThrownBy(() -> service.provideFixture(json(Map.of(
                "operatorRef", "resource:applicant", "outputPort", "payload",
                "sampleValue", Map.of("score", "bad"), "category", "INTERNAL",
                "retentionDays", 3, "redactPaths", List.of(),
                "idempotencyKey", "provide-bad")), identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("SCHEMA_NONCONFORMANT"));
    }

    @Test
    void fixtureWrapperRejectsAnUnauthenticatedInternalPurposeBridge() {
        assertThatThrownBy(() -> service.provideFixture(json(Map.of(
                "operatorRef", "resource:applicant", "outputPort", "payload",
                "sampleValue", Map.of("score", 760), "category", "INTERNAL",
                "retentionDays", 3, "redactPaths", List.of(),
                "idempotencyKey", "provide-wrong-purpose")), authorIdentity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("FORBIDDEN_PURPOSE"));
        verifyNoInteractions(fixtures);
    }

    @Test
    void ownerSignoffReferenceIsImmutable() {
        AgentTddReviewService review = new AgentTddReviewService(states);
        storeReviewSubject(4, "sha256:golden", "sha256:evidence", "sha256:implementation");
        AgentTddStoredAsset first = review.approveToolSignoff(
                "risk-tool", "signoff-1", 4, "sha256:golden", "sha256:evidence",
                "sha256:implementation", identity());

        assertThatThrownBy(() -> review.approveToolSignoff(
                "risk-tool", "signoff-1", 4, "sha256:golden", "sha256:evidence",
                "sha256:implementation", identity()))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("GATE_REJECTED"));
        assertThat(states.find(scope(), AgentTddWorkflowService.SIGNOFF, "signoff-1")
                .orElseThrow()).isEqualTo(first);
    }

    private JsonNode json(Object value) {
        return mapper.valueToTree(value);
    }

    private String currentEvidenceFingerprint(String caseSetRef, String side) {
        List<JsonNode> rows = new java.util.ArrayList<>();
        states.find(scope(), AgentTddMutationService.CASE_SET, caseSetRef).orElseThrow()
                .data().path("rows").forEach(rows::add);
        return AgentTddExecutionService.evidenceFingerprint(mapper, "risk-tool", draft, rows, side);
    }

    private long currentCaseSetRevision(String caseSetRef) {
        return states.find(scope(), AgentTddMutationService.CASE_SET, caseSetRef)
                .orElseThrow().revision();
    }

    private void storeAttestation(String goldenSetId, String evidenceFingerprint) {
        Map<String, Object> evidence = new java.util.LinkedHashMap<>();
        evidence.put("toolRef", "risk-tool");
        evidence.put("status", "ATTESTED");
        evidence.put("environment", "test");
        evidence.put("goldenSetId", goldenSetId);
        evidence.put("evidenceFingerprint", evidenceFingerprint);
        evidence.put("draftRevision", 4);
        evidence.put("caseSetRef", "golden-1");
        evidence.put("implementationFingerprint", "sha256:implementation");
        evidence.put("cases", List.of());
        evidence.put("dependencies", List.of());
        evidence.put("realExternalCalls", 0);
        states.save(scope(), AgentTddAttestationService.ATTESTATION, "risk-tool", json(evidence));
    }

    private void storeReviewSubject(long draftRevision,
                                    String goldenSetId,
                                    String evidenceFingerprint,
                                    String implementationFingerprint) {
        states.save(scope(), AgentTddWorkflowService.VERDICT, "risk-tool", json(Map.of(
                "latest", Map.of("side", "GREEN", "status", "GO",
                        "draftRevision", draftRevision, "goldenSetId", goldenSetId,
                        "evidenceFingerprint", evidenceFingerprint))));
        states.save(scope(), AgentTddAttestationService.ATTESTATION, "risk-tool", json(Map.of(
                "toolRef", "risk-tool", "status", "ATTESTED", "draftRevision", draftRevision,
                "goldenSetId", goldenSetId, "evidenceFingerprint", evidenceFingerprint,
                "implementationFingerprint", implementationFingerprint)));
    }

    private static String scope() {
        return AgentTddMutationService.scopeKey(identity());
    }

    private static IntegrationRequestContext identity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "USER", "reviewer-1",
                "", "AGENT_TDD_GOVERNANCE", "corr-1", Set.of(), "CONFIDENTIAL", "");
    }

    private static IntegrationRequestContext authorIdentity() {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", "test", "sg", "WORKLOAD", "agent-1",
                "", "AGENT_TDD_AUTHORING", "corr-agent");
    }
}
