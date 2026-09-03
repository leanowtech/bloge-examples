package com.leanowtech.bloge.gateway.agenttdd;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.integration.IntegrationRequestContext;
import com.leanowtech.bloge.gateway.resource.ParameterMapping;
import com.leanowtech.bloge.gateway.resource.ResourceDescriptor;
import com.leanowtech.bloge.gateway.resource.ResourceRegistry;
import com.leanowtech.bloge.gateway.resource.ResponseProtocol;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunService;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Proves platform authority, sandbox admission, descriptor freshness, and retry idempotency. */
class AgentTddAttestationServiceTest {
    private final InMemoryAgentTddStateRepository states = new InMemoryAgentTddStateRepository();
    private final GraphDraftRepository drafts = mock(GraphDraftRepository.class);
    private final VisualOperatorCatalog catalog = mock(VisualOperatorCatalog.class);
    private final ResourceRegistry resources = mock(ResourceRegistry.class);
    private final VisualGraphRunService runner = mock(VisualGraphRunService.class);
    private final AgentTddAttestationService service = new AgentTddAttestationService(
            states, drafts, catalog, resources, runner,
            new AgentTddEgressHostPolicy("localhost"), new ObjectMapper());

    @Test
    void rejectsAnAgentEvenWhenItClaimsTheAttestationPurpose() {
        IntegrationRequestContext agent = identity("test", "WORKLOAD", "AGENT_TDD_ATTEST");

        assertThatThrownBy(() -> service.attest(Map.of("toolRef", "risk-tool"), agent))
                .isInstanceOfSatisfying(AgentTddToolException.class, failure ->
                        assertThat(failure.code()).isEqualTo("FORBIDDEN_PURPOSE"));
        verifyNoInteractions(drafts, catalog, resources, runner);
    }

    @Test
    void productionFailsClosedBeforeGraphOrResourceResolution() {
        Map<String, Object> evidence = service.attest(Map.of(
                "toolRef", "risk-tool", "side", "GREEN", "status", "GO",
                "evidenceFingerprint", "sha256:green"),
                identity("prod", "PLATFORM", "AGENT_TDD_ATTEST"));

        assertThat(evidence).containsEntry("status", "FAILED")
                .containsEntry("reasonCode", "ATTESTATION_ENVIRONMENT_NOT_ALLOWED")
                .containsEntry("environment", "prod")
                .doesNotContainKeys("given", "expect", "output", "payload");
        verifyNoInteractions(drafts, catalog, resources, runner);
    }

    @Test
    void rejectsAWriteEffectBeforeCallingTheRuntime() {
        Map<String, Object> green = greenFor(resourceOperator("WRITE_EXTERNAL"),
                descriptor("POST", "https://localhost/write"));

        Map<String, Object> evidence = service.attest(
                green, identity("test", "PLATFORM", "AGENT_TDD_ATTEST"));

        assertThat(evidence).containsEntry("status", "FAILED")
                .containsEntry("reasonCode", "WRITE_EFFECT_NOT_ALLOWED")
                .containsEntry("realExternalCalls", 0);
        verifyNoInteractions(runner);
    }

    @Test
    void rejectsAReadOutsideTheExactHostAllowlistBeforeCallingTheRuntime() {
        Map<String, Object> green = greenFor(resourceOperator("READ_EXTERNAL"),
                descriptor("GET", "https://outside.example.test/read"));

        Map<String, Object> evidence = service.attest(
                green, identity("test", "PLATFORM", "AGENT_TDD_ATTEST"));

        assertThat(evidence).containsEntry("status", "FAILED")
                .containsEntry("reasonCode", "EGRESS_NOT_ALLOWED")
                .containsEntry("realExternalCalls", 0);
        verifyNoInteractions(runner);
    }

    @Test
    void replaysOneAutomaticAttestationWithoutRepeatingTheRealRead() {
        Map<String, Object> green = greenFor(resourceOperator("READ_EXTERNAL"),
                descriptor("GET", "https://localhost/read"));
        VisualGraphRunResponse response = mock(VisualGraphRunResponse.class);
        when(response.success()).thenReturn(true);
        when(response.output()).thenReturn(Map.of());
        when(response.nodeAttempts()).thenReturn(Map.of(
                "dependency", List.of(mock(VisualNodeExecutionAttempt.class))));
        when(runner.runAgainst(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<String, ResourceDescriptor>anyMap()))
                .thenReturn(response);

        Map<String, Object> first = service.attest(
                green, identity("test", "PLATFORM", "AGENT_TDD_ATTEST"));
        Map<String, Object> replay = service.attest(
                green, identity("test", "PLATFORM", "AGENT_TDD_ATTEST"));

        assertThat(first).containsEntry("status", "ATTESTED").isEqualTo(replay);
        verify(runner, times(1)).runAgainst(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<String, ResourceDescriptor>anyMap());

        when(resources.resolve("write-service")).thenReturn(
                descriptor("GET", "https://localhost/replaced"));
        assertThat(service.isCurrent(new ObjectMapper().valueToTree(first),
                identity("test", "WORKLOAD", "AGENT_TDD_READ"))).isFalse();
    }

    @Test
    void eachHumanRecoveryAfterFailureCreatesOneNewControlledAttempt() {
        Map<String, Object> green = greenFor(resourceOperator("READ_EXTERNAL"),
                descriptor("GET", "https://localhost/read"));
        VisualGraphRunResponse response = mock(VisualGraphRunResponse.class);
        when(response.success()).thenReturn(false);
        when(response.output()).thenReturn(Map.of());
        when(response.nodeAttempts()).thenReturn(Map.of(
                "dependency", List.of(mock(VisualNodeExecutionAttempt.class))));
        when(runner.runAgainst(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<String, ResourceDescriptor>anyMap()))
                .thenReturn(response);

        Map<String, Object> automatic = service.attest(
                green, identity("test", "PLATFORM", "AGENT_TDD_ATTEST"));
        Map<String, Object> firstRecovery = service.rerun(
                "risk-tool", identity("test", "USER", "AGENT_TDD_GOVERNANCE"));
        Map<String, Object> secondRecovery = service.rerun(
                "risk-tool", identity("test", "USER", "AGENT_TDD_GOVERNANCE"));

        assertThat(automatic).containsEntry("status", "FAILED");
        assertThat(firstRecovery).containsEntry("status", "FAILED");
        assertThat(secondRecovery).containsEntry("status", "FAILED");
        verify(runner, times(3)).runAgainst(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.eq(""), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<String, ResourceDescriptor>anyMap());
    }

    private Map<String, Object> greenFor(OperatorDefinition operator, ResourceDescriptor descriptor) {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "dependency", operator.operatorRef(), "dependency", Map.of(), Map.of(), null);
        GraphDraft draft = new GraphDraft(
                GraphDraft.SCHEMA_VERSION, "risk-tool", 1, "riskTool",
                "tenant-a", "project-a", "test", GraphDraft.STATUS_DRAFT,
                SchemaEnvelope.opaque(), SchemaEnvelope.opaque(), List.of(node), List.of(),
                Map.of(), Map.of(), new GraphDraft.OutputSelection("dependency", ""),
                Map.of("dependency", operator.fingerprint()), Map.of("dependency", operator),
                GraphDraft.RevisionMetadata.empty());
        when(drafts.find("risk-tool")).thenReturn(Optional.of(draft));
        when(resources.contains("write-service")).thenReturn(true);
        when(resources.resolve("write-service")).thenReturn(descriptor);
        var row = new ObjectMapper().valueToTree(Map.of(
                "caseId", "g1", "lifecycle", "ACTIVE", "given", Map.of(),
                "stubs", Map.of(), "expect", Map.of()));
        states.save(AgentTddMutationService.scopeKey(identity("test", "PLATFORM", "AGENT_TDD_ATTEST")),
                AgentTddMutationService.CASE_SET, "golden-1", new ObjectMapper().valueToTree(Map.of(
                        "toolRef", "risk-tool", "rows", List.of(row))));
        List<com.fasterxml.jackson.databind.JsonNode> rows = List.of(row);
        Map<String, Object> green = Map.of(
                "toolRef", "risk-tool", "status", "GO", "side", "GREEN",
                "caseSetRef", "golden-1", "draftRevision", 1,
                "goldenSetId", AgentTddExecutionService.goldenSetId(
                        new ObjectMapper(), "risk-tool", draft, List.of("g1")),
                "evidenceFingerprint", AgentTddExecutionService.evidenceFingerprint(
                        new ObjectMapper(), "risk-tool", draft, rows, "GREEN"));
        var verdict = new ObjectMapper().createObjectNode();
        verdict.set("latest", new ObjectMapper().valueToTree(green));
        states.save(AgentTddMutationService.scopeKey(identity("test", "PLATFORM", "AGENT_TDD_ATTEST")),
                AgentTddWorkflowService.VERDICT, "risk-tool", verdict);
        return green;
    }

    private static OperatorDefinition resourceOperator(String effect) {
        return new OperatorDefinition(
                "bloge.visualOperator.v1", "resource:write-service", "1.0.0",
                new OperatorDefinition.Display("Resource", "", List.of("resource-read")),
                new OperatorDefinition.Source(
                        "resource-descriptor", "write-service", "GET", "/resource", true),
                new OperatorDefinition.Ports(List.of(), List.of()), SchemaEnvelope.opaque(),
                new OperatorDefinition.Capabilities(effect, "IDEMPOTENT", false, false, false),
                new OperatorDefinition.Lowering(
                        "resource-descriptor", "httpResource", Map.of("resourceId", "write-service")),
                List.of());
    }

    private static ResourceDescriptor descriptor(String method, String url) {
        return new ResourceDescriptor("write-service", url, method, Map.of(), null,
                Duration.ofSeconds(1), ParameterMapping.empty(),
                new ResponseProtocol.HttpStatus(), null);
    }

    private static IntegrationRequestContext identity(String environment,
                                                       String actorType,
                                                       String purpose) {
        return new IntegrationRequestContext(
                "tenant-a", "org-a", "project-a", environment, "sg", actorType,
                "principal-1", "", purpose, "corr-1");
    }
}
