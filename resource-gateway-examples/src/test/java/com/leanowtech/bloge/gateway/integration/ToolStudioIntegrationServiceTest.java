package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolStudioIntegrationServiceTest {

    @Test
    void runEvidenceV5JsonSchemaMatchesSerializedProtocolFields() throws Exception {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision", Map.of("decision", "APPROVE"),
                Map.of("eligibility", Map.of("eligible", true), "decision", Map.of("decision", "APPROVE")),
                Map.of("eligibility", "COMPLETED", "decision", "COMPLETED"), 10,
                Map.of("eligibility", 4L, "decision", 3L), List.of(), List.of(), null, null,
                "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of(
                        "eligibility", List.of(attempt(Map.of("customerId", "c-1"), Map.of("eligible", true))),
                        "decision", List.of(attempt(Map.of("eligible", true), Map.of("decision", "APPROVE")))),
                successFacts("eligibility", "decision"));
        RunEvidenceBundle evidence = RunEvidenceBundle.from(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode schema = mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", "run-evidence-bundle-v5.schema.json")));
        JsonNode serialized = mapper.valueToTree(evidence);

        assertSchemaProperties(serialized, schema.path("properties"));
        assertSchemaProperties(serialized.path("execution"), schema.at("/$defs/execution/properties"));
        assertSchemaProperties(serialized.path("recovery"), schema.at("/$defs/recovery/properties"));
        assertSchemaProperties(serialized.at("/execution/runControl"), schema.at("/$defs/runControl/properties"));
        assertSchemaProperties(serialized.path("nodes").get(0), schema.at("/$defs/nodeEvidence/properties"));
        assertSchemaProperties(serialized.path("edges").get(0), schema.at("/$defs/edgeEvidence/properties"));
        assertSchemaProperties(serialized.path("manifest"), schema.at("/$defs/manifest/properties"));
        assertThat(schema.at("/$defs/status/enum")).extracting(JsonNode::asText)
                .containsExactlyInAnyOrder(java.util.Arrays.stream(VisualRunStatus.values())
                        .map(Enum::name).toArray(String[]::new));
    }

    @Test
    void unconfirmedControlledRunIsQuarantinedAndExposesSideEffectRisk() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualRunControlView control = new VisualRunControlView("", "request-unconfirmed", "execution-1",
                "TERMINATION_UNCONFIRMED", "OWNER_EXITED_WITH_ACTIVE_OPERATORS", 5,
                Instant.now().plusSeconds(1), Instant.now(), Instant.now(), null, false, true);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, false, draft.graphName(), "decision", null, Map.of(),
                Map.of("eligibility", "FAILED", "decision", "CANCELLED"), 20,
                Map.of("eligibility", 20L), List.of(), List.of("termination unconfirmed"), null, null,
                "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of("eligibility", List.of(attempt(Map.of("customerId", "c-1"), null))),
                Map.of("eligibility", executionFact("FAILED"), "decision", executionFact("CANCELLED")), control);

        RunEvidenceBundle evidence = RunEvidenceBundle.from(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));

        assertThat(evidence.execution().status()).isEqualTo("PARTIAL");
        assertThat(evidence.execution().reasonCode()).isEqualTo("OWNER_EXITED_WITH_ACTIVE_OPERATORS");
        assertThat(evidence.execution().runControl()).satisfies(runControl -> {
            assertThat(runControl.terminationConfirmed()).isFalse();
            assertThat(runControl.sideEffectsMayBeInFlight()).isTrue();
        });
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().gaps())
                .contains("Controlled run termination is not confirmed; external side effects may still be in flight.");
    }

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
                .containsEntry("structuredExecutionFacts", true)
                .containsEntry("graphDeadline", true)
                .containsEntry("userRunCancellation", true)
                .containsEntry("runTerminationConfirmation", true)
                .containsEntry("hardRunTermination", false)
                .containsEntry("durableRunControl", true)
                .containsEntry("crossInstanceRunCancellation", true)
                .containsEntry("runOwnerLease", true)
                .containsEntry("runOwnerEpochFencing", true)
                .containsEntry("restartRunResumption", false)
                .containsEntry("expiredOwnerQuarantine", true)
                .containsEntry("runControlEvidence", true)
                .containsEntry("runEvidenceRecoveryReservation", true)
                .containsEntry("abandonedRunEvidenceRecovery", true)
                .containsEntry("recoveryTransactionalOutbox", true)
                .containsEntry("sideEffectCommitConfirmation", false)
                .containsEntry("payloadReplay", true)
                .containsEntry("recordedAssertionReplay", true)
                .containsEntry("replayExternalSideEffects", false)
                .containsEntry("deepLinks", true)
                .containsEntry("governanceGateFeedback", true)
                .containsEntry("transactionalOutbox", true)
                .containsEntry("eventCursor", true)
                .containsEntry("reconciliationSnapshot", true)
                .containsEntry("trustedWorkloadIdentity", false)
                .containsEntry("demoIdentityMode", false)
                .containsEntry("webhook", false);
        assertThat(envelope.payload().endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .containsExactlyInAnyOrder(
                        "GET /api/integration/capabilities",
                        "GET /api/integration/drafts/{draftId}/export",
                        "GET /api/integration/runs/{runId}/evidence",
                        "GET /api/integration/runs/{runId}/replay",
                        "POST /api/integration/runs/{runId}/replay",
                        "GET /api/integration/evidence-keys/{keyId}",
                        "POST /api/integration/gate-results",
                        "GET /api/integration/drafts/{draftId}/gate-result",
                        "GET /api/integration/events",
                        "GET /api/integration/reconciliation",
                        "GET /api/integration/operator-libraries/{libraryId}",
                        "GET /api/integration/operator-test-suites/{suiteId}",
                        "GET /api/visual/run-controls/{requestId}",
                        "POST /api/visual/run-controls/{requestId}/cancel"
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
    void exportsEvidenceWithStandardizedNodeAndEdgeFacts() throws Exception {
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
                List.of(), List.of(), null, null, "graph customerKnowledgeTool {}",
                new VisualValidationResult(true, List.of()), "",
                Map.of(
                        "eligibility", List.of(attempt(Map.of("customerId", "c-1"), Map.of("eligible", true))),
                        "decision", List.of(attempt(Map.of("eligible", true),
                                Map.of("decision", "APPROVE", "token", "raw-token")))
                ),
                successFacts("eligibility", "decision")
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
        assertThat(envelope.payload().manifest().evidenceStatus()).isEqualTo("READY");
        assertThat(envelope.payload().manifest().capturedNodeInputCount()).isEqualTo(2);
        assertThat(envelope.payload().manifest().manifestHash()).startsWith("sha256:");
        assertThat(envelope.payload().manifest().signatureStatus()).isEqualTo("VERIFIED");
        assertThat(envelope.payload().manifest().signatureAlgorithm()).isEqualTo("Ed25519");
        assertThat(envelope.payload().manifest().signature()).isNotBlank();
        assertThat(envelope.payload().retention().payloadPolicy()).isEqualTo("SANITIZED");

        IntegrationEnvelope<com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner.VerificationKey> key =
                service.evidenceKey(envelope.payload().manifest().keyId());
        assertThat(key.payload().keyId()).isEqualTo(envelope.payload().manifest().keyId());
        assertThat(key.payload().encodedPublicKey()).isNotBlank();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(key.payload().encodedPublicKey()))));
        verifier.update(envelope.payload().manifest().manifestHash().getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(envelope.payload().manifest().signature()))).isTrue();
    }

    @Test
    void replaysSanitizedPayloadWithoutExposingSecrets() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision",
                Map.of("decision", "APPROVE", "token", "raw-token"),
                Map.of("decision", Map.of("decision", "APPROVE", "token", "raw-token")),
                Map.of("decision", "SUCCESS"), 25, Map.of("decision", 10L),
                List.of(), List.of(), null, null, "graph customerKnowledgeTool {}",
                new VisualValidationResult(true, List.of()), "",
                Map.of("decision", List.of(attempt(
                        Map.of("customerId", "c-1", "authorization", "Bearer raw-secret"),
                        Map.of("decision", "APPROVE", "token", "raw-token"))))
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
            assertThat(node.inputAvailable()).isTrue();
            assertThat(node.input()).isInstanceOfSatisfying(Map.class, input ->
                    assertThat(input).containsEntry("authorization", "[REDACTED]"));
        });
        assertThat(envelope.payload().redaction().redactedCount()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void quarantinesSignedEvidenceWhenInvokedNodeInputsAreMissing() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision", Map.of("decision", "APPROVE"),
                Map.of("eligibility", Map.of("eligible", true), "decision", Map.of("decision", "APPROVE")),
                Map.of("eligibility", "SUCCESS", "decision", "SUCCESS"), 25,
                List.of(), List.of(), null, null, "graph customerKnowledgeTool {}"
        );
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(draft,
                Map.of("customerId", "c-1"), response));
        ToolStudioIntegrationService service = service(null, null, null, runs);

        RunEvidenceBundle evidence = service.runEvidence(stored.runId(), integrationContext("corr-quarantine"))
                .payload();

        assertThat(evidence.manifest().signatureStatus()).isEqualTo("VERIFIED");
        assertThat(evidence.manifest().complete()).isFalse();
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().gaps())
                .contains("Exact input was not captured for every invoked node.");
    }

    @Test
    void distinguishesTimeoutAndPartialFailureFromGenericFailure() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualNodeExecutionAttempt timeout = new VisualNodeExecutionAttempt(
                1, Map.of("eligible", true), null, "FAILED", Instant.parse("2026-07-12T00:00:00Z"), 100,
                "com.leanowtech.bloge.core.exception.OperatorTimeoutException", "timed out after PT0.1S");
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, false, draft.graphName(), "decision", null,
                Map.of("eligibility", Map.of("eligible", true)),
                Map.of("eligibility", "COMPLETED", "decision", "FAILED"), 120,
                Map.of("eligibility", 5L, "decision", 100L), List.of(), List.of("decision timed out"),
                null, null, "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of(
                        "eligibility", List.of(attempt(Map.of("customerId", "c-1"), Map.of("eligible", true))),
                        "decision", List.of(timeout)
                ),
                Map.of(
                        "eligibility", successFact(),
                        "decision", timeoutFact()
                )
        );
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(draft,
                Map.of("customerId", "c-1"), response));

        RunEvidenceBundle evidence = service(null, null, null, runs)
                .runEvidence(stored.runId(), integrationContext("corr-timeout"))
                .payload();

        assertThat(evidence.execution().status()).isEqualTo("TIMEOUT");
        assertThat(evidence.execution().reasonCode()).isEqualTo("CRITICAL_OUTPUT_NOT_REACHED");
        assertThat(evidence.nodes()).filteredOn(node -> node.nodeId().equals("decision"))
                .singleElement().satisfies(node -> {
                    assertThat(node.status()).isEqualTo("TIMEOUT");
                    assertThat(node.reasonCode()).isEqualTo("NODE_TIMEOUT");
                    assertThat(node.observationSource()).isEqualTo("ENGINE_RESILIENCE_EVENT");
                    assertThat(node.retry().attempts()).isEqualTo(1);
                    assertThat(node.retry().maxAttempts()).isEqualTo(2);
                    assertThat(node.retry().lastErrorCode()).contains("OperatorTimeoutException");
                });
        assertThat(evidence.edges()).singleElement().satisfies(edge ->
                assertThat(edge.status()).isEqualTo("SUCCESS"));
        assertThat(evidence.execution().criticalOutputReached()).isFalse();
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("READY");
    }

    @Test
    void marksGraphPartialOnlyWhenCriticalOutputWasReachedWithIndependentDegradation() {
        OperatorDefinition operator = operator();
        GraphDraft base = runDraft(operator);
        GraphDraft draft = new GraphDraft(
                base.schemaVersion(), base.draftId(), base.revision(), base.graphName(), base.tenantId(),
                base.namespace(), base.environment(), base.status(), base.inputSchema(), base.outputSchema(),
                base.nodes(), base.edges(), base.visualLayout(), base.nodeFixtures(),
                new GraphDraft.OutputSelection("eligibility", ""), base.operatorFingerprints(),
                base.operatorSnapshots(), base.revisionMetadata());
        VisualNodeExecutionAttempt failure = new VisualNodeExecutionAttempt(
                0, Map.of("eligible", true), null, "FAILED", Instant.parse("2026-07-12T00:00:00Z"), 8,
                "java.lang.IllegalStateException", "secondary provider failed");
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, false, draft.graphName(), "eligibility", Map.of("eligible", true),
                Map.of("eligibility", Map.of("eligible", true)),
                Map.of("eligibility", "COMPLETED", "decision", "FAILED"), 20,
                Map.of("eligibility", 5L, "decision", 8L), List.of(), List.of("decision failed"),
                null, null, "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of(
                        "eligibility", List.of(attempt(Map.of("customerId", "c-1"), Map.of("eligible", true))),
                        "decision", List.of(failure)),
                Map.of("eligibility", successFact(), "decision", failedFact()));
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));

        RunEvidenceBundle evidence = service(null, null, null, runs)
                .runEvidence(stored.runId(), integrationContext("corr-partial")).payload();

        assertThat(evidence.execution().status()).isEqualTo("PARTIAL");
        assertThat(evidence.execution().reasonCode()).isEqualTo("CRITICAL_OUTPUT_REACHED_WITH_DEGRADATION");
        assertThat(evidence.execution().criticalOutputReached()).isTrue();
        assertThat(evidence.execution().degraded()).isTrue();
        assertThat(evidence.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.status()).isEqualTo("SUCCESS");
            assertThat(edge.propagated()).isTrue();
            assertThat(edge.reasonCode()).isEqualTo("VALUE_PROPAGATED");
        });
    }

    @Test
    void exportsCancellationCauseWithoutInventingAnEdgePayload() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, false, draft.graphName(), "decision", null, Map.of(),
                Map.of("eligibility", "FAILED", "decision", "CANCELLED"), 100,
                Map.of("eligibility", 100L), List.of(), List.of("eligibility timed out"), null, null,
                "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of("eligibility", List.of(new VisualNodeExecutionAttempt(
                        0, Map.of("customerId", "c-1"), null, "FAILED",
                        Instant.parse("2026-07-12T00:00:00Z"), 100,
                        "com.leanowtech.bloge.core.exception.OperatorTimeoutException", "timed out"))),
                Map.of(
                        "eligibility", timeoutFact(),
                        "decision", new VisualNodeExecutionFact(
                                "CANCELLED", "UPSTREAM_FAILED", "TOPOLOGY_DERIVATION", List.of("eligibility"),
                                new VisualNodeExecutionFact.Retry(1, 0, false, ""),
                                new VisualNodeExecutionFact.Timeout(false, 0, false),
                                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                                "NOT_INVOKED", List.of())));
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord stored = runs.create(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));

        RunEvidenceBundle evidence = service(null, null, null, runs)
                .runEvidence(stored.runId(), integrationContext("corr-cancel")).payload();

        assertThat(evidence.nodes()).filteredOn(node -> node.nodeId().equals("decision"))
                .singleElement().satisfies(node -> {
                    assertThat(node.status()).isEqualTo("CANCELLED");
                    assertThat(node.reasonCode()).isEqualTo("UPSTREAM_FAILED");
                    assertThat(node.causedByNodeIds()).containsExactly("eligibility");
                    assertThat(node.inputAvailable()).isFalse();
                });
        assertThat(evidence.edges()).singleElement().satisfies(edge -> {
            assertThat(edge.status()).isEqualTo("CANCELLED");
            assertThat(edge.reasonCode()).isEqualTo("UPSTREAM_FAILURE_PROPAGATED");
            assertThat(edge.propagated()).isFalse();
            assertThat(edge.payloadRef()).isBlank();
        });
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("READY");
    }

    @Test
    void executesIdempotentRecordedReplayWithLineageAssertionsAndNoExternalCalls() {
        OperatorDefinition operator = operator();
        GraphDraft draft = runDraft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "decision",
                Map.of("decision", "APPROVE", "reason", "eligible"),
                Map.of(
                        "eligibility", Map.of("eligible", true, "score", 720),
                        "decision", Map.of("decision", "APPROVE", "reason", "eligible")),
                Map.of("eligibility", "SUCCESS", "decision", "SUCCESS"), 25,
                Map.of("eligibility", 8L, "decision", 7L), List.of(), List.of(), null, null,
                "graph customerKnowledgeTool {}", new VisualValidationResult(true, List.of()), "",
                Map.of(
                        "eligibility", List.of(attempt(
                                Map.of("customerId", "c-1"), Map.of("eligible", true, "score", 720))),
                        "decision", List.of(attempt(
                                Map.of("eligible", true, "score", 720),
                                Map.of("decision", "APPROVE", "reason", "eligible"))))
                , successFacts("eligibility", "decision"));
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord parent = runs.create(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));
        ToolStudioIntegrationService service = service(null, null, null, runs);
        ReplayExecutionRequest request = new ReplayExecutionRequest(
                "", "replay-request-1", "RECORDED_ASSERTIONS", "REGRESSION", "DENY", List.of(
                new ReplayExecutionRequest.Assertion(
                        "terminal-decision", "OUTPUT", "", "PATH_EQUALS", "/decision", "APPROVE"),
                new ReplayExecutionRequest.Assertion(
                        "eligibility-node", "NODE", "eligibility", "PATH_EQUALS", "/eligible", true),
                new ReplayExecutionRequest.Assertion(
                        "output-schema", "OUTPUT", "", "MATCHES_SCHEMA", "", Map.of(
                        "type", "object", "required", List.of("decision", "reason"),
                        "properties", Map.of("decision", Map.of("type", "string")))),
                new ReplayExecutionRequest.Assertion(
                        "signed-parent", "RUN", "", "GOVERNANCE_EXPECTATION", "", "SIGNATURE_VERIFIED")
        ));

        ReplayExecutionResult first = service.executeReplay(parent.runId(), request, replayContext("corr-rpl-1"))
                .payload();
        ReplayExecutionResult second = service.executeReplay(parent.runId(), request, replayContext("corr-rpl-2"))
                .payload();

        assertThat(first.status()).isEqualTo("PASSED");
        assertThat(first.replayRunId()).isNotEqualTo(parent.runId()).isEqualTo(second.replayRunId());
        assertThat(first.parentRunId()).isEqualTo(parent.runId());
        assertThat(first.externalInvocationCount()).isZero();
        assertThat(first.sideEffectPolicy()).isEqualTo("DENY");
        assertThat(first.evidenceStatus()).isEqualTo("READY");
        assertThat(first.assertionResults()).hasSize(4).allMatch(VisualReplayAssertionResult::passed);

        VisualGraphRunRecord replay = runs.find(first.replayRunId()).orElseThrow();
        assertThat(replay.sourceKind()).isEqualTo(VisualGraphRunRecord.SOURCE_RECORDED_REPLAY);
        assertThat(replay.replay().parentRunId()).isEqualTo(parent.runId());
        assertThat(replay.replay().requestFingerprint()).isEqualTo(request.fingerprint());
        assertThat(replay.replay().externalInvocationCount()).isZero();
        assertThat(replay.statusMap()).containsOnlyKeys("eligibility", "decision")
                .allSatisfy((nodeId, status) -> assertThat(status).isEqualTo("MOCKED"));
        assertThat(replay.evidenceSeal().signature()).isNotBlank();

        RunEvidenceBundle evidence = service.runEvidence(replay.runId(), integrationContext("corr-rpl-evidence"))
                .payload();
        assertThat(evidence.replay().parentRunId()).isEqualTo(parent.runId());
        assertThat(evidence.replay().externalInvocationCount()).isZero();
        assertThat(evidence.execution().mockUsed()).isTrue();
        assertThat(evidence.edges()).allSatisfy(edge -> assertThat(edge.status()).isEqualTo("MOCKED"));
        assertThat(evidence.assertions().status()).isEqualTo("PASSED");
        assertThat(evidence.assertions().results()).hasSize(4);
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("READY");
    }

    @Test
    void persistsFailedReplayAssertionsAndRejectsIdempotencyOrSafetyPolicyViolations() {
        OperatorDefinition operator = operator();
        GraphDraft draft = draft(operator);
        VisualGraphRunResponse response = new VisualGraphRunResponse(
                true, true, true, draft.graphName(), "eligibility", Map.of("eligible", false),
                Map.of("eligibility", Map.of("eligible", false)), Map.of("eligibility", "SUCCESS"), 4,
                Map.of("eligibility", 4L), List.of(), List.of(), null, null, "graph customerKnowledgeTool {}",
                new VisualValidationResult(true, List.of()), "",
                Map.of("eligibility", List.of(attempt(Map.of("score", 300), Map.of("eligible", false)))),
                successFacts("eligibility"));
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository();
        VisualGraphRunRecord parent = runs.create(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-2"), response));
        ToolStudioIntegrationService service = service(null, null, null, runs);
        ReplayExecutionRequest failing = new ReplayExecutionRequest(
                "", "replay-conflict", "", "NEGATIVE", "DENY", List.of(
                new ReplayExecutionRequest.Assertion(
                        "expected-approval", "OUTPUT", "", "PATH_EQUALS", "/eligible", true)));

        ReplayExecutionResult result = service.executeReplay(
                parent.runId(), failing, replayContext("corr-failed-replay")).payload();

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.assertionResults()).singleElement().satisfies(assertion -> {
            assertThat(assertion.passed()).isFalse();
            assertThat(assertion.expectedFingerprint()).startsWith("sha256:");
            assertThat(assertion.actualFingerprint()).startsWith("sha256:");
        });
        assertThat(runs.find(result.replayRunId())).get().satisfies(replay -> {
            assertThat(replay.success()).isFalse();
            assertThat(replay.errors()).containsExactly("Assertion failed for mode PATH_EQUALS.");
        });

        ReplayExecutionRequest conflicting = new ReplayExecutionRequest(
                "", "replay-conflict", "", "BOUNDARY", "DENY", List.of(
                new ReplayExecutionRequest.Assertion(
                        "different", "OUTPUT", "", "PATH_EXISTS", "/eligible", null)));
        ReplayExecutionRequest unsafe = new ReplayExecutionRequest(
                "", "unsafe-replay", "", "REGRESSION", "ALLOW", List.of(
                new ReplayExecutionRequest.Assertion(
                        "exists", "OUTPUT", "", "PATH_EXISTS", "/eligible", null)));

        assertThatThrownBy(() -> service.executeReplay(
                parent.runId(), conflicting, replayContext("corr-conflict")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().status()).isEqualTo(409));
        assertThatThrownBy(() -> service.executeReplay(
                parent.runId(), unsafe, replayContext("corr-unsafe")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.INTEGRATION.REPLAY_REQUEST_INVALID"));
        assertThatThrownBy(() -> service.executeReplay(
                parent.runId(), failing, integrationContext("corr-purpose")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(error -> assertThat(((IntegrationProblemException) error).problem().code())
                        .isEqualTo("RG.INTEGRATION.PURPOSE_NOT_ALLOWED"));
    }

    private static VisualNodeExecutionAttempt attempt(Object input, Object output) {
        return new VisualNodeExecutionAttempt(0, input, output, "SUCCESS",
                Instant.parse("2026-07-12T00:00:00Z"), 5, "", "");
    }

    private static void assertSchemaProperties(JsonNode value, JsonNode properties) {
        LinkedHashSet<String> actual = new LinkedHashSet<>();
        value.fieldNames().forEachRemaining(actual::add);
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        properties.fieldNames().forEachRemaining(expected::add);
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    private static Map<String, VisualNodeExecutionFact> successFacts(String... nodeIds) {
        java.util.LinkedHashMap<String, VisualNodeExecutionFact> facts = new java.util.LinkedHashMap<>();
        for (String nodeId : nodeIds) {
            facts.put(nodeId, successFact());
        }
        return facts;
    }

    private static VisualNodeExecutionFact successFact() {
        return new VisualNodeExecutionFact(
                "SUCCESS", "NONE", "ENGINE_STATUS", List.of(),
                new VisualNodeExecutionFact.Retry(1, 1, false, ""),
                new VisualNodeExecutionFact.Timeout(false, 0, false),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "NOT_APPLICABLE", List.of());
    }

    private static VisualNodeExecutionFact executionFact(String status) {
        return new VisualNodeExecutionFact(
                status, "CANCELLED".equals(status) ? "UPSTREAM_FAILURE" : "OPERATOR_ERROR",
                "CANCELLED".equals(status) ? "TOPOLOGY_DERIVATION" : "ENGINE_STATUS", List.of(),
                new VisualNodeExecutionFact.Retry("CANCELLED".equals(status) ? 0 : 1,
                        "CANCELLED".equals(status) ? 0 : 1, false, ""),
                new VisualNodeExecutionFact.Timeout(false, 0, false),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "CANCELLED".equals(status) ? "NOT_INVOKED" : "UNKNOWN_COMMIT", List.of());
    }

    private static VisualNodeExecutionFact timeoutFact() {
        return new VisualNodeExecutionFact(
                "TIMEOUT", "NODE_TIMEOUT", "ENGINE_RESILIENCE_EVENT", List.of(),
                new VisualNodeExecutionFact.Retry(2, 1, false,
                        "com.leanowtech.bloge.core.exception.OperatorTimeoutException"),
                new VisualNodeExecutionFact.Timeout(true, 100, true),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "UNKNOWN_COMMIT", List.of(new VisualNodeExecutionFact.Event(
                        1, "TIMEOUT", Instant.parse("2026-07-12T00:00:00Z"), 0,
                        "com.leanowtech.bloge.core.exception.OperatorTimeoutException")));
    }

    private static VisualNodeExecutionFact failedFact() {
        return new VisualNodeExecutionFact(
                "FAILED", "OPERATOR_ERROR", "ENGINE_STATUS", List.of(),
                new VisualNodeExecutionFact.Retry(1, 1, false, "java.lang.IllegalStateException"),
                new VisualNodeExecutionFact.Timeout(false, 0, false),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "NOT_APPLICABLE", List.of());
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

    private static IntegrationRequestContext replayContext(String correlationId) {
        return new IntegrationRequestContext(
                "tenant-a", "knowledge-governance", "tool-studio", "prod", "ap-southeast-1",
                "WORKLOAD", "aneke-replay", "", "PAYLOAD_REPLAY", correlationId
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
