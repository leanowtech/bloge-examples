package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.catalog.OperatorCatalogQuery;
import com.leanowtech.bloge.gateway.visual.catalog.OperatorDefinition;
import com.leanowtech.bloge.gateway.visual.catalog.VisualOperatorCatalog;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraftRepository;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import com.leanowtech.bloge.gateway.visual.runtime.InMemoryVisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRepository;
import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunResponse;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolStudioIntegrationServiceTest {

    @Test
    void capabilitiesAdvertiseOnlyImplementedIntegrationFeatures() {
        ToolStudioIntegrationService service = service(null, null, null, null);

        IntegrationEnvelope<IntegrationCapabilities> envelope = service.capabilities();

        assertThat(envelope.protocol()).isEqualTo(ToolStudioResourceGatewayProtocol.NAME);
        assertThat(envelope.protocolVersion()).isEqualTo(ToolStudioResourceGatewayProtocol.VERSION);
        assertThat(envelope.payloadKind()).isEqualTo("CAPABILITIES");
        assertThat(envelope.payload().features())
                .containsEntry("draftExportDependencyProfile", true)
                .containsEntry("runEvidenceBundle", true)
                .containsEntry("payloadReplay", true)
                .containsEntry("governanceGateFeedback", false)
                .containsEntry("eventCursor", false)
                .containsEntry("webhook", false);
        assertThat(envelope.payload().endpoints())
                .extracting(IntegrationCapabilities.Endpoint::path)
                .containsExactlyInAnyOrder(
                        "/api/integration/capabilities",
                        "/api/integration/drafts/{draftId}/export",
                        "/api/integration/runs/{runId}/evidence",
                        "/api/integration/runs/{runId}/replay"
                );
    }

    @Test
    void exportsTenantBoundSnapshotWithDeterministicDependencyMetadata() {
        OperatorDefinition operator = operator();
        GraphDraft draft = draft(operator);
        GraphDraftRepository repository = mock(GraphDraftRepository.class);
        when(repository.findRevision("draft-1", 7)).thenReturn(Optional.of(draft));
        GraphDraftValidator validator = mock(GraphDraftValidator.class);
        when(validator.validate(draft)).thenReturn(new VisualValidationResult(true, List.of()));
        ToolStudioIntegrationService service = service(repository, validator, catalog(operator), null);
        IntegrationRequestContext context = new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-1"
        );

        IntegrationEnvelope<GraphDraftIntegrationBundle> first = service.exportDraft("draft-1", 7, context);
        IntegrationEnvelope<GraphDraftIntegrationBundle> second = service.exportDraft("draft-1", 7, context);

        assertThat(first.payloadKind()).isEqualTo("GRAPH_DRAFT_INTEGRATION_BUNDLE");
        assertThat(first.payload().draft()).isEqualTo(draft);
        assertThat(first.payload().draftFingerprint()).startsWith("sha256:");
        assertThat(first.payload().draftFingerprint()).isEqualTo(second.payload().draftFingerprint());
        assertThat(first.payload().dependencyProfile().operatorDependencies()).singleElement().satisfies(dependency -> {
            assertThat(dependency.nodeId()).isEqualTo("eligibility");
            assertThat(dependency.operatorRef()).isEqualTo("risk:eligibility");
            assertThat(dependency.operatorLibraryId()).isEqualTo("risk-policy");
            assertThat(dependency.operatorFingerprint()).isEqualTo(operator.fingerprint());
            assertThat(dependency.schemaFingerprint()).startsWith("sha256:");
        });
        assertThat(first.payload().dependencyProfile().graphContract().inputSchemaFingerprint()).startsWith("sha256:");
        assertThat(first.payload().dependencyProfile().graphContract().outputSchemaFingerprint()).startsWith("sha256:");
        assertThat(first.payloadFingerprint()).isEqualTo(second.payloadFingerprint());
    }

    @Test
    void rejectsCrossTenantAndCrossEnvironmentDraftExport() {
        OperatorDefinition operator = operator();
        GraphDraft draft = draft(operator);
        GraphDraftRepository repository = mock(GraphDraftRepository.class);
        when(repository.find("draft-1")).thenReturn(Optional.of(draft));
        ToolStudioIntegrationService service = service(repository, mock(GraphDraftValidator.class), catalog(operator), null);

        org.assertj.core.api.ThrowableAssert.ThrowingCallable crossTenant = () -> service.exportDraft(
                "draft-1", 0, new IntegrationRequestContext(
                        "tenant-b", "knowledge-governance", "tool-studio", "prod", "ap-southeast-1",
                        "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-2"));
        org.assertj.core.api.ThrowableAssert.ThrowingCallable crossEnvironment = () -> service.exportDraft(
                "draft-1", 0, new IntegrationRequestContext(
                        "tenant-a", "knowledge-governance", "tool-studio", "staging", "ap-southeast-1",
                        "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", "corr-3"));

        assertThatThrownBy(crossTenant).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().status()).isEqualTo(404));
        assertThatThrownBy(crossEnvironment).isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> assertThat(((IntegrationProblemException) failure).problem().status()).isEqualTo(404));
    }

    @Test
    void exportsEvidenceWithStandardizedNodeAndEdgeFacts() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision",
                Map.of("decision", "APPROVE", "token", "raw-token"),
                Map.of(
                        "eligibility", Map.of("eligible", true),
                        "decision", Map.of("decision", "APPROVE", "token", "raw-token")
                ),
                Map.of("eligibility", "SUCCESS", "decision", "SUCCESS"),
                42,
                Map.of("eligibility", 12L, "decision", 8L),
                List.of(), List.of(), null, null, "graph customerKnowledgeTool {}"
        );
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(
                draft,
                Map.of("customerId", "c-1", "password", "secret", "email", "person@example.com"),
                response
        ));
        ToolStudioIntegrationService service = service(null, null, null, runs);
        IntegrationRequestContext context = integrationContext("corr-evidence");

        IntegrationEnvelope<RunEvidenceBundle> envelope = service.runEvidence(stored.runId(), context);

        assertThat(envelope.payloadKind()).isEqualTo("RUN_EVIDENCE_BUNDLE");
        assertThat(envelope.payload().runId()).isEqualTo(stored.runId());
        assertThat(envelope.payload().fingerprints().draftFingerprint()).startsWith("sha256:");
        assertThat(envelope.payload().execution().status()).isEqualTo("SUCCESS");
        assertThat(envelope.payload().nodes())
                .extracting(RunEvidenceBundle.NodeEvidence::status)
                .containsOnly("SUCCESS");
        assertThat(envelope.payload().edges()).singleElement().satisfies(edge -> {
            assertThat(edge.edgeId()).isEqualTo("eligibility-to-decision");
            assertThat(edge.status()).isEqualTo("SUCCESS");
        });
        assertThat(envelope.payload().manifest().complete()).isTrue();
        assertThat(envelope.payload().manifest().manifestHash()).startsWith("sha256:");
        assertThat(envelope.payload().retention().payloadPolicy()).isEqualTo("SANITIZED");
    }

    @Test
    void replaysSanitizedPayloadWithoutExposingSecrets() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision",
                Map.of("decision", "APPROVE", "token", "raw-token"),
                Map.of("decision", Map.of("decision", "APPROVE", "token", "raw-token")),
                Map.of("decision", "SUCCESS"), 25,
                List.of(), List.of(), null, null, "graph customerKnowledgeTool {}"
        );
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(
                draft,
                Map.of("customerId", "c-1", "password", "secret", "email", "person@example.com"),
                response
        ));
        ToolStudioIntegrationService service = service(null, null, null, runs);

        IntegrationEnvelope<PayloadReplayBundle> envelope = service.replay(stored.runId(), integrationContext("corr-replay"));

        assertThat(envelope.payloadKind()).isEqualTo("PAYLOAD_REPLAY_BUNDLE");
        assertThat(envelope.payload().payloadPolicy().mode()).isEqualTo("SANITIZED");
        assertThat(envelope.payload().payloadPolicy().rawAvailable()).isFalse();
        assertThat(envelope.payload().context())
                .containsEntry("customerId", "c-1")
                .containsEntry("password", "[REDACTED]")
                .containsEntry("email", "[REDACTED]");
        assertThat(envelope.payload().output()).isInstanceOfSatisfying(Map.class, output ->
                assertThat(output).containsEntry("token", "[REDACTED]"));
        assertThat(envelope.payload().nodes()).anySatisfy(node -> {
            assertThat(node.nodeId()).isEqualTo("decision");
            assertThat(node.outputAvailable()).isTrue();
            assertThat(node.inputAvailable()).isFalse();
        });
        assertThat(envelope.payload().redaction().redactedCount()).isGreaterThanOrEqualTo(3);
    }

    private static ToolStudioIntegrationService service(GraphDraftRepository repository,
                                                        GraphDraftValidator validator,
                                                        VisualOperatorCatalog catalog,
                                                        VisualGraphRunRepository runs) {
        return new ToolStudioIntegrationService(repository, validator, catalog, runs);
    }

    private static IntegrationRequestContext integrationContext(String correlationId) {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke-sync", "", "GOVERNANCE_EVIDENCE_INGESTION", correlationId
        );
    }

    private static VisualOperatorCatalog catalog(OperatorDefinition operator) {
        return new VisualOperatorCatalog() {
            @Override
            public List<OperatorDefinition> list(OperatorCatalogQuery query) {
                return List.of(operator);
            }

            @Override
            public Optional<OperatorDefinition> find(String operatorRef) {
                return operator.operatorRef().equals(operatorRef) ? Optional.of(operator) : Optional.empty();
            }
        };
    }

    private static OperatorDefinition operator() {
        return new OperatorDefinition(
                "", "risk:eligibility", "1.0.0",
                new OperatorDefinition.Display("Eligibility", "", List.of("risk")),
                new OperatorDefinition.Source("user-library", "", "", "", false, "risk-policy"),
                new OperatorDefinition.Ports(List.of(), List.of()),
                SchemaEnvelope.opaque(),
                OperatorDefinition.Capabilities.pure(),
                new OperatorDefinition.Lowering("native", "risk:eligibility", Map.of()),
                List.of()
        );
    }

    private static GraphDraft draft(OperatorDefinition operator) {
        GraphDraft.DraftNode node = new GraphDraft.DraftNode(
                "eligibility", operator.operatorRef(), "Eligibility", Map.of(), Map.of(),
                new GraphDraft.Position(100, 100)
        );
        SchemaEnvelope inputSchema = new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("customerId", Map.of("type", "string"))
        ));
        SchemaEnvelope outputSchema = new SchemaEnvelope("json-schema", "2020-12", Map.of(
                "type", "object",
                "properties", Map.of("eligible", Map.of("type", "boolean"))
        ));
        return new GraphDraft(
                "", "draft-1", 7, "customerKnowledgeTool", "tenant-a", "knowledge", "prod", "DRAFT",
                inputSchema, outputSchema, List.of(node), List.of(), Map.of(), Map.of(),
                new GraphDraft.OutputSelection("eligibility", ""),
                Map.of("eligibility", operator.fingerprint()), Map.of("eligibility", operator),
                GraphDraft.RevisionMetadata.empty()
        );
    }

    private static GraphDraft runDraft(OperatorDefinition operator) {
        GraphDraft base = draft(operator);
        GraphDraft.DraftNode decision = new GraphDraft.DraftNode(
                "decision", operator.operatorRef(), "Decision", Map.of(), Map.of(),
                new GraphDraft.Position(320, 100)
        );
        GraphDraft.DraftEdge edge = new GraphDraft.DraftEdge(
                "eligibility-to-decision", "data",
                new GraphDraft.Endpoint("eligibility", "output", ""),
                new GraphDraft.Endpoint("decision", "input", ""), ""
        );
        return new GraphDraft(
                base.schemaVersion(), base.draftId(), base.revision(), base.graphName(), base.tenantId(),
                base.namespace(), base.environment(), base.status(), base.inputSchema(), base.outputSchema(),
                List.of(base.nodes().getFirst(), decision), List.of(edge), base.visualLayout(), base.nodeFixtures(),
                new GraphDraft.OutputSelection("decision", ""),
                Map.of("eligibility", operator.fingerprint(), "decision", operator.fingerprint()),
                Map.of("eligibility", operator, "decision", operator), base.revisionMetadata()
        );
    }
}
