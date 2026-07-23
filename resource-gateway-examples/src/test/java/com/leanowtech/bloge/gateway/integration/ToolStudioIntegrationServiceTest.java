package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.domain.TestSuite;
import com.leanowtech.bloge.gateway.testing.domain.WorkerQuarantineRequestIndexMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityJobAuthorizer;
import com.leanowtech.bloge.gateway.testing.api.TestSecretAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveReconciliationHealth;
import com.leanowtech.bloge.gateway.testing.api.WorkerQuarantineChangeAuthorizationTrustStore;
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
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.EvidenceVerificationKeySet;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionAttempt;
import com.leanowtech.bloge.gateway.visual.runtime.VisualNodeExecutionFact;
import com.leanowtech.bloge.gateway.visual.runtime.VisualReplayAssertionResult;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView;
import com.leanowtech.bloge.gateway.visual.runtime.VisualSideEffectAttempt;
import com.leanowtech.bloge.gateway.visual.validation.GraphDraftValidator;
import com.leanowtech.bloge.gateway.visual.validation.VisualValidationResult;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolStudioIntegrationServiceTest {

    @Test
    void runEvidenceV7JsonSchemaMatchesSerializedProtocolFields() throws Exception {
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
                Map.of("eligibility", successFact(), "decision", committedSideEffectFact()));
        RunEvidenceBundle evidence = RunEvidenceBundle.from(VisualGraphRunRecord.storedDraft(
                draft, Map.of("customerId", "c-1"), response));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JsonNode schema = mapper.readTree(Files.readString(Path.of("..", "docs", "schemas",
                "tool-studio-resource-gateway", "run-evidence-bundle-v7.schema.json")));
        JsonNode serialized = mapper.valueToTree(evidence);

        assertSchemaProperties(serialized, schema.path("properties"));
        assertSchemaProperties(serialized.path("execution"), schema.at("/$defs/execution/properties"));
        assertSchemaProperties(serialized.path("recovery"), schema.at("/$defs/recovery/properties"));
        assertSchemaProperties(serialized.at("/execution/runControl"), schema.at("/$defs/runControl/properties"));
        assertSchemaProperties(serialized.path("retention"), schema.at("/$defs/retention/properties"));
        assertSchemaProperties(serialized.path("nodes").get(0), schema.at("/$defs/nodeEvidence/properties"));
        JsonNode sideEffect = serialized.path("nodes").get(1).path("sideEffectAttempts").get(0);
        assertSchemaProperties(sideEffect, schema.at("/$defs/sideEffectAttempt/properties"));
        assertSchemaProperties(sideEffect.path("request"), schema.at("/$defs/sideEffectRequest/properties"));
        assertSchemaProperties(sideEffect.path("receipt"), schema.at("/$defs/sideEffectReceipt/properties"));
        assertSchemaProperties(sideEffect.path("receipt").path("proof"),
                schema.at("/$defs/sideEffectProof/properties"));
        assertSchemaProperties(sideEffect.path("transitions").get(0),
                schema.at("/$defs/sideEffectTransition/properties"));
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
                .containsEntry("graphDraftConsistentDependencySnapshot", true)
                .containsEntry("graphDraftStructuredDependencyRefs", true)
                .containsEntry("capabilitySnapshotProtocol", true)
                .containsEntry("capabilityProjection", true)
                .containsEntry("capabilityClosureProtocol", true)
                .containsEntry("mirrorPlanProtocol", true)
                .containsEntry("builtInCapabilityClosureProjection", true)
                .containsEntry("visualCapabilityClosureProjection", true)
                .containsEntry("capabilitySnapshotApi", true)
                .containsEntry("capabilityLifecycleFencing", true)
                .containsEntry("mirrorPlanCompilation", false)
                .containsEntry("mirrorExternalLeafInterception", false)
                .containsEntry("mirrorOperationObservability", false)
                .containsEntry("mirrorServing", false)
                .containsEntry("mirrorIsolationAuthorityPublicationProtocol", true)
                .containsEntry("mirrorIsolationAuthorityDistributionApi", false)
                .containsEntry("mirrorIsolationAuthorityDistributionReady", false)
                .containsEntry("mirrorIsolationAttestationTrustProtocol", true)
                .containsEntry("mirrorIsolationAttestationDistributionApi", false)
                .containsEntry("mirrorIsolationAttestationDistributionReady", false)
                .containsEntry("mirrorObservationProtocol", true)
                .containsEntry("mirrorObservationAdmissionApi", false)
                .containsEntry("mirrorObservationAdmissionReady", false)
                .containsEntry("mirrorCorpusGovernanceProtocol", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationProtocol", true)
                .containsEntry("mirrorCorpusGovernanceApi", false)
                .containsEntry("mirrorCorpusGovernanceReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationApi", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationReady", false)
                .containsEntry("mirrorCorpusResolverReady", false)
                .containsEntry("mirrorServingGenerationFencing", false)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", false)
                .containsEntry("runEvidenceBundle", true)
                .containsEntry("structuredExecutionFacts", true)
                .containsEntry("graphDeadline", true)
                .containsEntry("operatorContextDeadlineBudget", true)
                .containsEntry("deadlineAdmissionControl", true)
                .containsEntry("retryBudgetEnforcement", true)
                .containsEntry("httpRemainingBudget", true)
                .containsEntry("remoteWorkerDeadlineBudget", true)
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
                .containsEntry("sideEffectJournal", true)
                .containsEntry("sideEffectConformanceContract", true)
                .containsEntry("sideEffectWriteAdmission", true)
                .containsEntry("sideEffectBindingConformance", true)
                .containsEntry("httpWriteSideEffectProtocol", true)
                .containsEntry("sideEffectCommitReceipts", true)
                .containsEntry("sideEffectReconciliation", true)
                .containsEntry("sideEffectReconciliationEvidence", true)
                .containsEntry("sideEffectReconcilerAdapters", false)
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
                .containsEntry("externalTestSecretAuthority", false)
                .containsEntry("durableTestSecretReauthorization", false)
                .containsEntry("dynamicTestSecretAuthorityTrust", false)
                .containsEntry("testSecretAuthorityTrustRefreshSlo", false)
                .containsEntry("testSecretAuthorityTrustCohortConvergence", false)
                .containsEntry("testSecretAuthorityTrustCohortReady", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", false)
                .containsEntry("mirrorStatefulProtocol", true)
                .containsEntry("mirrorStatefulSessionApi", false)
                .containsEntry("mirrorStatefulStateStoreReady", false)
                .containsEntry("mirrorStatefulResolverReady", false)
                .containsEntry("mirrorStatefulRuntimeReady", false)
                .containsEntry("webhook", false);
        assertThat(envelope.payload().supportedObjects())
                .containsKeys("capabilitySnapshot", "capabilityClosure", "mirrorPlan",
                        "mirrorServingGenerationToken",
                        "boundedStateExpression", "stateModel",
                        "writeEffectSpec", "sessionStateSpace",
                        "capabilityContract", "effectContract",
                        "artifactProvenance", "capabilityLifecycleTransition",
                        "capabilityClosureProjectionRequest");
        assertThat(envelope.payload().supportedObjects())
                .containsEntry("mirrorPlan", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorPlan.SCHEMA_VERSION_V1,
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorPlan.SCHEMA_VERSION))
                .containsEntry("mirrorServingGenerationToken", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorServingGenerationToken.SCHEMA_VERSION))
                .containsEntry("boundedStateExpression", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .BoundedStateExpression.SCHEMA_VERSION))
                .containsEntry("stateModel", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .StateModel.SCHEMA_VERSION))
                .containsEntry("writeEffectSpec", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .WriteEffectSpec.SCHEMA_VERSION))
                .containsEntry("sessionStateSpace", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .SessionStateSpace.SCHEMA_VERSION));
        assertThat(envelope.payload().endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .containsExactlyInAnyOrder(
                "GET /api/integration/capabilities",
                "PUT /api/integration/capability-snapshots/{capabilityId}/revisions/{revision}",
                "GET /api/integration/capability-snapshots/{capabilityId}",
                "POST /api/integration/capability-snapshots/{capabilityId}/lifecycle-transitions",
                "POST /api/integration/capability-closures/project",
                "GET /api/integration/drafts/{draftId}/export",
                "GET /api/integration/drafts/{draftId}/correctness-workbook",
                "GET /api/integration/runs/{runId}/evidence",
                        "GET /api/integration/runs/{runId}/side-effects/reconciliations",
                        "POST /api/integration/runs/{runId}/side-effects/{attemptId}/reconcile",
                        "GET /api/integration/runs/{runId}/replay",
                        "POST /api/integration/runs/{runId}/replay",
                        "GET /api/integration/runs/{runId}/payload-retention",
                        "POST /api/integration/runs/{runId}/payload-retention/holds",
                        "POST /api/integration/runs/{runId}/payload-retention/holds/{holdId}/release",
                        "POST /api/integration/runs/{runId}/payload-retention/purge",
                        "POST /api/integration/payload-retention/purge-expired",
                        "GET /api/integration/evidence-keys/{keyId}",
                        "GET /api/integration/evidence-keys",
                        "POST /api/integration/evidence-keys/trust-publications",
                        "GET /api/integration/evidence-keys/trust-bundle",
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
    void capabilitiesAdvertiseOnlyTheProtectedMirrorSurfacesOwnedByItsProfileMarker() {
        ToolStudioIntegrationService disabled = service(null, null, null, null);
        ToolStudioIntegrationService enabled = service(null, null, null, null);
        enabled.configureMirrorRuntime(new MirrorRuntimeAvailability(true, true));

        IntegrationCapabilities disabledCapabilities = disabled.capabilities().payload();
        IntegrationCapabilities enabledCapabilities = enabled.capabilities().payload();

        assertThat(disabledCapabilities.features())
                .containsEntry("mirrorPlanCompilation", false)
                .containsEntry("mirrorExternalLeafInterception", false)
                .containsEntry("mirrorOperationObservability", false)
                .containsEntry("mirrorServing", false);
        assertThat(disabledCapabilities.endpoints())
                .noneMatch(endpoint -> endpoint.path().startsWith("/api/mirror/"));
        assertThat(enabledCapabilities.features())
                .containsEntry("mirrorPlanCompilation", true)
                .containsEntry("mirrorExternalLeafInterception", true)
                .containsEntry("mirrorOperationObservability", true)
                .containsEntry("mirrorServing", true);
        assertThat(enabledCapabilities.supportedObjects())
                .containsEntry("mirrorPlanCreateRequest", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorPlanCreateRequest.SCHEMA_VERSION))
                .containsEntry("mirrorExecutionRequest", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorExecutionRequest.SCHEMA_VERSION))
                .containsEntry("mirrorRunSummary", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorRunSummary.SCHEMA_VERSION))
                .containsEntry("mirrorEvidenceBundle", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorEvidenceBundle.SCHEMA_VERSION_V1,
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorEvidenceBundle.SCHEMA_VERSION))
                .containsEntry("mirrorRunEvidence", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorRunEvidence.SCHEMA_VERSION_V1,
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorRunEvidence.SCHEMA_VERSION))
                .containsEntry("mirrorEvidenceAttestation", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorEvidenceAttestation.SCHEMA_VERSION_V1,
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorEvidenceAttestation.SCHEMA_VERSION));
        assertThat(enabledCapabilities.endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains("POST /api/mirror/plans", "GET /api/mirror/plans/{planId}",
                        "POST /api/mirror/executions", "GET /api/mirror/runs/{runId}",
                        "GET /api/mirror/runs/{runId}/evidence");
    }

    @Test
    void capabilitiesRecheckDynamicMirrorServingWithoutProducingMixedResponses() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean ready =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(true, true, ready::get));

        IntegrationCapabilities unavailable = service.capabilities().payload();
        ready.set(true);
        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("mirrorOperationObservability", true)
                .containsEntry("mirrorServing", false);
        assertThat(unavailable.endpoints())
                .anyMatch(endpoint -> endpoint.path().equals("/api/mirror/executions"));
        assertThat(unavailable.supportedObjects()).containsKey("mirrorExecutionRequest");
        assertThat(available.features())
                .containsEntry("mirrorOperationObservability", true)
                .containsEntry("mirrorServing", true);
        assertThat(available.endpoints())
                .anyMatch(endpoint -> endpoint.path().equals("/api/mirror/executions"));
        assertThat(available.supportedObjects()).containsKey("mirrorExecutionRequest");
    }

    @Test
    void capabilitiesSeparateExploratoryServingFromCertificationTrustReadiness() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean certificationReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, () -> true, true, () -> true,
                certificationReady::get));

        IntegrationCapabilities exploratoryOnly = service.capabilities().payload();
        certificationReady.set(true);
        IntegrationCapabilities certifiable = service.capabilities().payload();

        assertThat(exploratoryOnly.features())
                .containsEntry("mirrorServing", true)
                .containsEntry("mirrorIsolationRunTrustBindingProtocol", true)
                .containsEntry("mirrorIsolationRunTrustReady", false)
                .containsEntry("mirrorCertifiableEvidenceServingReady", false);
        assertThat(certifiable.features())
                .containsEntry("mirrorServing", true)
                .containsEntry("mirrorIsolationRunTrustReady", true)
                .containsEntry("mirrorCertifiableEvidenceServingReady", true);
        assertThat(certifiable.supportedObjects())
                .containsEntry("mirrorDeploymentIsolationRunTrust", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorDeploymentIsolationRunTrust.Binding.SCHEMA_VERSION));
    }

    @Test
    void capabilitiesSeparateAuthorityDistributionRouteAssemblyFromTrustReadiness() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean trustReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, trustReady::get));

        IntegrationCapabilities unavailable = service.capabilities().payload();
        trustReady.set(true);
        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("mirrorIsolationAuthorityPublicationProtocol", true)
                .containsEntry("mirrorIsolationAuthorityDistributionApi", true)
                .containsEntry("mirrorIsolationAuthorityDistributionReady", false);
        assertThat(unavailable.supportedObjects())
                .containsEntry("mirrorDeploymentIsolationAuthorityKeySetPublication", List.of(
                        com.leanowtech.bloge.gateway.integration.mirror
                                .MirrorDeploymentIsolationAuthorityKeySetPublication
                                .SCHEMA_VERSION));
        assertThat(unavailable.endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains(
                        "POST /api/mirror/trust/deployment-isolation/authority-key-sets",
                        "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/latest",
                        "GET /api/mirror/trust/deployment-isolation/authority-key-sets/{keySetId}/generations/{generation}");
        assertThat(available.features())
                .containsEntry("mirrorIsolationAuthorityDistributionApi", true)
                .containsEntry("mirrorIsolationAuthorityDistributionReady", true);
    }

    @Test
    void capabilitiesSeparateAttestationRouteAssemblyFromTrustChainReadiness() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean attestationReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, () -> true,
                true, attestationReady::get));

        IntegrationCapabilities unavailable = service.capabilities().payload();
        attestationReady.set(true);
        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("mirrorIsolationAttestationTrustProtocol", true)
                .containsEntry("mirrorIsolationAttestationDistributionApi", true)
                .containsEntry("mirrorIsolationAttestationDistributionReady", false);
        assertThat(unavailable.supportedObjects())
                .containsKeys("mirrorDeploymentIsolationAttestation",
                        "mirrorDeploymentIsolationAttestationStatus",
                        "mirrorDeploymentIsolationAttestationBundle",
                        "mirrorDeploymentIsolationAttestationRevocationRequest");
        assertThat(unavailable.endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains(
                        "POST /api/mirror/trust/deployment-isolation/attestations",
                        "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/latest",
                        "GET /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revisions/{revision}",
                        "POST /api/mirror/trust/deployment-isolation/attestations/{attestationId}/revocations");
        assertThat(available.features())
                .containsEntry("mirrorIsolationAttestationDistributionApi", true)
                .containsEntry("mirrorIsolationAttestationDistributionReady", true);
    }

    @Test
    void capabilitiesSeparateObservationRouteAssemblyFromAdmissionReadiness() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean observationReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, () -> true,
                true, () -> true, () -> true,
                true, observationReady::get));

        IntegrationCapabilities unavailable = service.capabilities().payload();
        observationReady.set(true);
        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("mirrorObservationProtocol", true)
                .containsEntry("mirrorObservationAdmissionApi", true)
                .containsEntry("mirrorObservationAdmissionReady", false);
        assertThat(unavailable.supportedObjects())
                .containsKeys(
                        "capabilityObservation",
                        "capabilityObservationAdmission",
                        "capabilityObservationReceipt");
        assertThat(unavailable.endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains("POST /api/mirror/observations");
        assertThat(available.features())
                .containsEntry("mirrorObservationAdmissionApi", true)
                .containsEntry("mirrorObservationAdmissionReady", true);
    }

    @Test
    void capabilitiesSeparateCorpusProtocolApiReadinessAndResolverClaims() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        java.util.concurrent.atomic.AtomicBoolean governanceReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean resolverReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean trajectoryReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean trajectoryResolverReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean clusterReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean clusterResolverReady =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, () -> true,
                true, () -> true, () -> true,
                true, () -> true, true, governanceReady::get,
                true, trajectoryReady::get,
                resolverReady::get, trajectoryResolverReady::get,
                true, clusterReady::get, clusterResolverReady::get));

        IntegrationCapabilities unavailable = service.capabilities().payload();
        governanceReady.set(true);
        trajectoryReady.set(true);
        IntegrationCapabilities governanceAvailable =
                service.capabilities().payload();
        clusterReady.set(true);
        IntegrationCapabilities clusterAvailable =
                service.capabilities().payload();
        resolverReady.set(true);
        IntegrationCapabilities exactResolverAvailable =
                service.capabilities().payload();
        trajectoryResolverReady.set(true);
        clusterResolverReady.set(true);
        IntegrationCapabilities fullyAvailable =
                service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("mirrorCorpusGovernanceProtocol", true)
                .containsEntry("mirrorCorpusExactResolverProtocol", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverProtocol", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationProtocol", true)
                .containsEntry(
                        "mirrorCorpusClusterPublicationProtocol", true)
                .containsEntry(
                        "mirrorCorpusClusterResolverProtocol", true)
                .containsEntry("mirrorCorpusGovernanceApi", true)
                .containsEntry("mirrorCorpusGovernanceReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationApi", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationReady", false)
                .containsEntry(
                        "mirrorCorpusClusterPublicationApi", true)
                .containsEntry(
                        "mirrorCorpusClusterPublicationReady", false)
                .containsEntry("mirrorCorpusResolverReady", false)
                .containsEntry("mirrorServingGenerationFencing", false)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", false)
                .containsEntry(
                        "mirrorCorpusClusterResolverReady", false);
        assertThat(unavailable.supportedObjects())
                .containsKeys(
                        "capabilityObservationReviewRequest",
                        "capabilityObservationReview",
                        "capabilityCorpusCandidateRequest",
                        "capabilityCorpusRevision",
                        "capabilityCorpusPublishRequest",
                        "capabilityCorpusPublication",
                        "capabilityCorpusTrajectoryPublishRequest",
                        "capabilityCorpusTrajectoryPublication",
                        "capabilityCorpusClusterValidation",
                        "capabilityCorpusClusterPublishRequest",
                        "capabilityCorpusClusterPublication",
                        "fixtureMirrorCorpusBindings",
                        "fixtureMirrorTrajectoryBindings",
                        "fixtureMirrorClusterBindings");
        assertThat(unavailable.endpoints())
                .extracting(endpoint -> endpoint.method() + " " + endpoint.path())
                .contains(
                        "POST /api/mirror/observations/{observationId}/reviews",
                        "POST /api/mirror/corpus-candidates",
                        "POST /api/mirror/corpus-publications",
                        "POST /api/mirror/corpus-trajectories",
                        "POST /api/mirror/corpus-clusters");
        assertThat(governanceAvailable.features())
                .containsEntry("mirrorCorpusGovernanceApi", true)
                .containsEntry("mirrorCorpusGovernanceReady", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationReady", true)
                .containsEntry(
                        "mirrorCorpusClusterPublicationReady", false)
                .containsEntry("mirrorCorpusResolverReady", false)
                .containsEntry("mirrorServingGenerationFencing", false)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", false)
                .containsEntry(
                        "mirrorCorpusClusterResolverReady", false);
        assertThat(clusterAvailable.features())
                .containsEntry(
                        "mirrorCorpusClusterPublicationReady", true)
                .containsEntry("mirrorCorpusResolverReady", false)
                .containsEntry("mirrorServingGenerationFencing", false)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", false)
                .containsEntry(
                        "mirrorCorpusClusterResolverReady", false);
        assertThat(exactResolverAvailable.features())
                .containsEntry("mirrorCorpusGovernanceReady", true)
                .containsEntry("mirrorCorpusResolverReady", true)
                .containsEntry("mirrorServingGenerationFencing", true)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", false)
                .containsEntry(
                        "mirrorCorpusClusterResolverReady", false);
        assertThat(fullyAvailable.features())
                .containsEntry("mirrorCorpusGovernanceReady", true)
                .containsEntry("mirrorCorpusResolverReady", true)
                .containsEntry("mirrorServingGenerationFencing", true)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", true)
                .containsEntry(
                        "mirrorCorpusClusterResolverReady", true);
    }

    @Test
    void trajectoryServingReadinessDoesNotDependOnPublicationRouteAssembly() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        service.configureMirrorRuntime(new MirrorRuntimeAvailability(
                true, true, () -> true, true, () -> true,
                true, () -> true, () -> true,
                true, () -> true, true, () -> true,
                false, () -> false, () -> true, () -> true));

        IntegrationCapabilities capabilities = service.capabilities().payload();

        assertThat(capabilities.features())
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationApi", false)
                .containsEntry(
                        "mirrorCorpusTrajectoryPublicationReady", false)
                .containsEntry("mirrorCorpusResolverReady", true)
                .containsEntry("mirrorServingGenerationFencing", true)
                .containsEntry(
                        "mirrorServingGenerationAuthorityReady", true)
                .containsEntry(
                        "mirrorCorpusTrajectoryResolverReady", true);
    }

    @Test
    void capabilitiesAdvertiseSecretProtocolAndReevaluateAuthorityReadiness() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        service.configureTestability(new TestabilityAvailability(
                true, false, WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                authorityDescriptor(false)));
        AtomicBoolean ready = new AtomicBoolean(true);
        TestSecretAuthority authority = mock(TestSecretAuthority.class);
        when(authority.descriptor()).thenAnswer(ignored -> new TestSecretAuthority.Descriptor(
                "", ready.get(), "EXTERNAL_HTTPS", "authority-a", Map.of()));
        @SuppressWarnings("unchecked")
        ObjectProvider<TestSecretAuthority> providers = mock(ObjectProvider.class);
        when(providers.getIfAvailable()).thenReturn(authority);
        service.configureTestSecretAuthorities(providers);

        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(available.supportedObjects().get("fixtureExecutionServices"))
                .containsExactly("bloge.fixtureExecutionServices.v1",
                        "bloge.fixtureExecutionServices.v2");
        assertThat(available.supportedObjects())
                .containsEntry("testSecretAuthorityRequest",
                        List.of("bloge.testSecretAuthorityRequest.v1"))
                .containsEntry("testSecretAuthorityResponse",
                        List.of("bloge.testSecretAuthorityResponse.v1"))
                .containsEntry("testSecretAuthorityTrustDescriptor",
                        List.of("bloge.testSecretAuthorityTrustDescriptor.v1"))
                .containsEntry("testSecretAuthorityTrustRefreshSnapshot",
                        List.of("bloge.testSecretAuthorityTrustRefreshSnapshot.v1"))
                .containsEntry("testSecretAuthorityTrustCohortSnapshot",
                        List.of("bloge.testSecretAuthorityTrustCohortSnapshot.v2"))
                .containsEntry("testSecretAuthorityTrustCohortDescriptor",
                        List.of("bloge.testSecretAuthorityTrustCohortDescriptor.v2"))
                .containsEntry("testSecretAuthorityServingInventory",
                        List.of("bloge.testSecretAuthorityServingInventory.v1"))
                .containsEntry("testSecretAuthorityServingInventoryMaterial",
                        List.of("bloge.testSecretAuthorityServingInventoryMaterial.v1"))
                .containsEntry("testSecretAuthorityServingInventoryObservation",
                        List.of("bloge.testSecretAuthorityServingInventoryObservation.v1"))
                .containsEntry("testSecretAuthorityServingInventoryDescriptor",
                        List.of("bloge.testSecretAuthorityServingInventoryDescriptor.v1"))
                .containsEntry("testSecretAuthorityServingInventoryAttestation",
                        List.of("bloge.testSecretAuthorityServingInventoryAttestation.v1"))
                .containsEntry("testSecretAuthorityServingInventoryPublication",
                        List.of("bloge.testSecretAuthorityServingInventoryPublication.v1"))
                .containsEntry("testSecretAuthorityServingInventoryPublicationMaterial",
                        List.of("bloge.testSecretAuthorityServingInventoryPublicationMaterial.v1"))
                .containsEntry("testSecretAuthorityServingInventoryWitness",
                        List.of("bloge.testSecretAuthorityServingInventoryWitness.v1"))
                .containsEntry("testSecretAuthorityServingInventoryWitnessMaterial",
                        List.of("bloge.testSecretAuthorityServingInventoryWitnessMaterial.v1"))
                .containsEntry("testSecretAuthorityServingInventoryPublicationGeneration",
                        List.of("bloge.testSecretAuthorityServingInventoryPublicationGeneration.v1"))
                .containsEntry("testSecretAuthorityServingInventoryRefreshSnapshot",
                        List.of("bloge.testSecretAuthorityServingInventoryRefreshSnapshot.v1"))
                .containsEntry("testSecretAuthorityServingInventoryTrustRootPublication",
                        List.of("bloge.testSecretAuthorityServingInventoryTrustRootPublication.v1"))
                .containsEntry("testSecretAuthorityServingInventoryTrustRootMaterial",
                        List.of("bloge.testSecretAuthorityServingInventoryTrustRootMaterial.v1"))
                .containsEntry("testSecretAuthorityServingInventoryTrustRootGeneration",
                        List.of("bloge.testSecretAuthorityServingInventoryTrustRootGeneration.v1"))
                .containsEntry("testSecretAuthorityServingInventoryTrustRootSnapshot",
                        List.of("bloge.testSecretAuthorityServingInventoryTrustRootSnapshot.v1"))
                .containsEntry("testSecretAuthorityServingInventoryDynamicTrustRootSnapshot",
                        List.of(
                                "bloge.testSecretAuthorityServingInventoryDynamicTrustRootSnapshot.v1"))
                .containsEntry("testSecretAuthorityDescriptor",
                        List.of("bloge.testSecretAuthorityDescriptor.v1"));
        assertThat(available.features())
                .containsEntry("externalTestSecretAuthority", true)
                .containsEntry("durableTestSecretReauthorization", true)
                .containsEntry("dynamicTestSecretAuthorityTrust", false)
                .containsEntry("testSecretAuthorityTrustRefreshSlo", false)
                .containsEntry("testSecretAuthorityTrustCohortConvergence", false)
                .containsEntry("testSecretAuthorityTrustCohortReady", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", false)
                .containsEntry("testSecretAuthorityDynamicServingInventory", false)
                .containsEntry("testSecretAuthoritySignedInventoryRevocation", false)
                .containsEntry("testSecretAuthorityWitnessedInventoryPublication", false)
                .containsEntry("testSecretAuthorityDurableInventoryPublicationFloor", false)
                .containsEntry(
                        "testSecretAuthorityExternallyAnchoredInventoryPublicationFloor", false)
                .containsEntry(
                        "testSecretAuthorityByzantineQuorumInventoryPublicationFloor", false)
                .containsEntry("testSecretAuthorityManagedServingInventoryTrustRoots", false)
                .containsEntry(
                        "testSecretAuthorityAtomicDualServingInventoryTrustRoots", false)
                .containsEntry("testSecretAuthorityDurableTrustRootFloor", false)
                .containsEntry("testSecretAuthorityExternallyAnchoredTrustRootFloor", false)
                .containsEntry("testSecretAuthorityByzantineQuorumTrustRootFloor", false)
                .containsEntry("testSecretAuthorityExternalNonEquivocationReady", false)
                .containsEntry("testSecretAuthorityManagedTrustRootsReady", false)
                .containsEntry("testSecretAuthorityDynamicServingInventoryReady", false);

        ready.set(false);
        assertThat(service.capabilities().payload().features())
                .containsEntry("externalTestSecretAuthority", false)
                .containsEntry("durableTestSecretReauthorization", false);

        when(authority.descriptor()).thenReturn(new TestSecretAuthority.Descriptor(
                "", true, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", "authority-a",
                Map.ofEntries(
                        Map.entry("trustProviderType", "DYNAMIC_JWKS_ED25519"),
                        Map.entry("trustAutomaticRefresh", true),
                        Map.entry("trustRefreshIntervalSeconds", 30L),
                        Map.entry("trustMaximumSnapshotAgeSeconds", 60L),
                        Map.entry("trustConditionalRequests", true),
                        Map.entry("trustFailClosedOnRefreshFailure", true))));
        assertThat(service.capabilities().payload().features())
                .containsEntry("dynamicTestSecretAuthorityTrust", true)
                .containsEntry("testSecretAuthorityTrustRefreshSlo", true)
                .containsEntry("testSecretAuthorityTrustCohortConvergence", false)
                .containsEntry("testSecretAuthorityTrustCohortReady", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", false);

        when(authority.descriptor()).thenReturn(new TestSecretAuthority.Descriptor(
                "", true, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", "authority-a",
                Map.ofEntries(
                        Map.entry("trustProviderType", "DYNAMIC_JWKS_ED25519"),
                        Map.entry("trustAutomaticRefresh", true),
                        Map.entry("trustRefreshIntervalSeconds", 30L),
                        Map.entry("trustMaximumSnapshotAgeSeconds", 60L),
                        Map.entry("trustConditionalRequests", true),
                        Map.entry("trustFailClosedOnRefreshFailure", true),
                        Map.entry("trustCohortConfigured", true),
                        Map.entry("trustCohortAvailable", true),
                        Map.entry("trustCohortStatus", "CONVERGED"),
                        Map.entry("trustCohortExpectedReplicaCount", 2),
                        Map.entry("trustCohortLiveReplicaCount", 2),
                        Map.entry("trustCohortHealthyReplicaCount", 2),
                        Map.entry("trustCohortDistinctGenerationCount", 1),
                        Map.entry("trustCohortLeaseDurationSeconds", 30L),
                        Map.entry("trustCohortDatabaseAuthority", true),
                        Map.entry("trustCohortExactConfiguredInventory", true))));
        assertThat(service.capabilities().payload().features())
                .containsEntry("testSecretAuthorityTrustCohortConvergence", true)
                .containsEntry("testSecretAuthorityTrustCohortReady", true)
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", false);

        when(authority.descriptor()).thenReturn(new TestSecretAuthority.Descriptor(
                "", true, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", "authority-a",
                Map.ofEntries(
                        Map.entry("trustProviderType", "DYNAMIC_JWKS_ED25519"),
                        Map.entry("trustAutomaticRefresh", true),
                        Map.entry("trustCohortConfigured", true),
                        Map.entry("trustCohortAvailable", true),
                        Map.entry("trustCohortStatus", "CONVERGED"),
                        Map.entry("trustCohortExpectedReplicaCount", 2),
                        Map.entry("trustCohortLiveReplicaCount", 2),
                        Map.entry("trustCohortHealthyReplicaCount", 2),
                        Map.entry("trustCohortDistinctGenerationCount", 1),
                        Map.entry("trustCohortDistinctInventoryGenerationCount", 1),
                        Map.entry("trustCohortExternallyAttestedInventory", true),
                        Map.entry("trustCohortLeaseDurationSeconds", 30L),
                        Map.entry("trustCohortDatabaseAuthority", true),
                        Map.entry("trustCohortExactConfiguredInventory", true),
                        Map.entry("servingInventorySourceType",
                                "DYNAMIC_HTTPS_SIGNED_PUBLICATION_WITH_WITNESS"),
                        Map.entry("servingInventoryAvailable", true),
                        Map.entry("servingInventoryStatus", "VERIFIED"),
                        Map.entry("servingInventoryAutomaticRefresh", true),
                        Map.entry("servingInventoryConditionalRequests", true),
                        Map.entry("servingInventoryFailClosedOnRefreshFailure", true),
                        Map.entry("servingInventorySignedRevocation", true),
                        Map.entry("servingInventoryWitnessedPublications", true),
                        Map.entry("servingInventoryWitnessSignatureThreshold", 2),
                        Map.entry("servingInventoryDurablePublicationFloor", true),
                        Map.entry("servingInventoryExternallyAnchoredPublicationFloor", true),
                        Map.entry("servingInventoryByzantineQuorumPublicationFloor", true),
                        Map.entry("servingInventoryManagedTrustRootRefresh", true),
                        Map.entry("servingInventoryAtomicDualTrustRootPublication", true),
                        Map.entry("servingInventoryDurableTrustRootFloor", true),
                        Map.entry("servingInventoryExternallyAnchoredTrustRootFloor", true),
                        Map.entry("servingInventoryByzantineQuorumTrustRootFloor", true),
                        Map.entry("servingInventoryExternalNonEquivocation", true))));
        assertThat(service.capabilities().payload().features())
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", true)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", true)
                .containsEntry("testSecretAuthorityDynamicServingInventory", true)
                .containsEntry("testSecretAuthoritySignedInventoryRevocation", true)
                .containsEntry("testSecretAuthorityWitnessedInventoryPublication", true)
                .containsEntry("testSecretAuthorityDurableInventoryPublicationFloor", true)
                .containsEntry(
                        "testSecretAuthorityExternallyAnchoredInventoryPublicationFloor", true)
                .containsEntry(
                        "testSecretAuthorityByzantineQuorumInventoryPublicationFloor", true)
                .containsEntry("testSecretAuthorityManagedServingInventoryTrustRoots", true)
                .containsEntry(
                        "testSecretAuthorityAtomicDualServingInventoryTrustRoots", true)
                .containsEntry("testSecretAuthorityDurableTrustRootFloor", true)
                .containsEntry("testSecretAuthorityExternallyAnchoredTrustRootFloor", true)
                .containsEntry("testSecretAuthorityByzantineQuorumTrustRootFloor", true)
                .containsEntry("testSecretAuthorityExternalNonEquivocationReady", true)
                .containsEntry("testSecretAuthorityManagedTrustRootsReady", true)
                .containsEntry("testSecretAuthorityDynamicServingInventoryReady", true);

        when(authority.descriptor()).thenReturn(new TestSecretAuthority.Descriptor(
                "", false, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", "authority-a",
                Map.ofEntries(
                        Map.entry("trustProviderType", "DYNAMIC_JWKS_ED25519"),
                        Map.entry("trustAutomaticRefresh", true),
                        Map.entry("trustCohortConfigured", true),
                        Map.entry("trustCohortAvailable", false),
                        Map.entry("trustCohortStatus", "SNAPSHOT_DIVERGED"),
                        Map.entry("trustCohortExpectedReplicaCount", 2),
                        Map.entry("trustCohortLiveReplicaCount", 2),
                        Map.entry("trustCohortHealthyReplicaCount", 2),
                        Map.entry("trustCohortDistinctGenerationCount", 2),
                        Map.entry("trustCohortDistinctInventoryGenerationCount", 2),
                        Map.entry("trustCohortExternallyAttestedInventory", true),
                        Map.entry("trustCohortLeaseDurationSeconds", 30L),
                        Map.entry("trustCohortDatabaseAuthority", true),
                        Map.entry("trustCohortExactConfiguredInventory", true))));
        assertThat(service.capabilities().payload().features())
                .containsEntry("externalTestSecretAuthority", false)
                .containsEntry("testSecretAuthorityTrustCohortConvergence", true)
                .containsEntry("testSecretAuthorityTrustCohortReady", false)
                .containsEntry("testSecretAuthorityDeploymentSignedInventory", true)
                .containsEntry("testSecretAuthorityDeploymentSignedInventoryReady", false);

        when(authority.descriptor()).thenReturn(new TestSecretAuthority.Descriptor(
                "", true, "HTTPS_SIGNED_TEST_SECRET_AUTHORITY", "authority-a",
                Map.ofEntries(
                        Map.entry("trustProviderType", "DYNAMIC_JWKS_ED25519"),
                        Map.entry("trustAutomaticRefresh", true),
                        Map.entry("trustRefreshIntervalSeconds", 30L),
                        Map.entry("trustMaximumSnapshotAgeSeconds", 0L),
                        Map.entry("trustConditionalRequests", true),
                        Map.entry("trustFailClosedOnRefreshFailure", true))));
        assertThat(service.capabilities().payload().features())
                .containsEntry("dynamicTestSecretAuthorityTrust", true)
                .containsEntry("testSecretAuthorityTrustRefreshSlo", false);
    }

    @Test
    void capabilitiesReevaluateCurrentAuthorityReadinessWithoutNetworkProbe() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        AtomicBoolean ready = new AtomicBoolean(true);
        TestSuiteStabilityJobAuthorizer authorizer =
                mock(TestSuiteStabilityJobAuthorizer.class);
        when(authorizer.descriptor()).thenAnswer(ignored -> authorityDescriptor(ready.get()));
        @SuppressWarnings("unchecked")
        ObjectProvider<TestSuiteStabilityJobAuthorizer> providers =
                mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> Stream.of(authorizer));
        service.configureTestability(new TestabilityAvailability(
                true, true, WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                authorityDescriptor(true)));
        service.configureSuiteStabilityAuthorizers(providers);

        IntegrationCapabilities available = service.capabilities().payload();

        assertThat(available.features())
                .containsEntry("asyncSuiteStabilityJobSubmission", true)
                .containsEntry("suiteStabilityCurrentAuthorityRevalidation", true)
                .containsEntry("signedChallengeBoundSuiteStabilityAuthority", true);
        assertThat(available.testability().suiteStabilityJobSubmissionEnabled()).isTrue();
        assertThat(available.testability().suiteStabilityCurrentAuthority().available())
                .isTrue();

        ready.set(false);
        IntegrationCapabilities unavailable = service.capabilities().payload();

        assertThat(unavailable.features())
                .containsEntry("asyncSuiteStabilityJobSubmission", false)
                .containsEntry("suiteStabilityCurrentAuthorityRevalidation", false)
                .containsEntry("signedChallengeBoundSuiteStabilityAuthority", false);
        assertThat(unavailable.testability().suiteStabilityJobSubmissionEnabled()).isFalse();
        assertThat(unavailable.testability().suiteStabilityCurrentAuthority().available())
                .isFalse();
    }

    @Test
    void capabilitiesReevaluateArchiveReconciliationReadinessAndFailClosed() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        service.configureTestability(new TestabilityAvailability(
                true, false, WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                authorityDescriptor(false)));
        var health = mock(
                TestSuiteStabilityObservationExternalArchiveReconciliationHealth.class);
        AtomicInteger state = new AtomicInteger();
        when(health.descriptor()).thenAnswer(ignored -> switch (state.get()) {
            case 0 -> new
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                    "", true, true, "HEALTHY", List.of(),
                    Instant.parse("2026-07-20T00:00:00Z"), 2);
            case 1 -> new
                    TestSuiteStabilityObservationExternalArchiveReconciliationHealth.Descriptor(
                    "", true, false, "SLO_VIOLATED", List.of("SCHEDULER_STALE"),
                    Instant.parse("2026-07-20T00:01:00Z"), 2);
            default -> throw new IllegalStateException("archive-a secret-fingerprint");
        });
        service.configureExternalArchiveReconciliationHealth(health);

        IntegrationCapabilities available = service.capabilities().payload();
        state.set(1);
        IntegrationCapabilities degraded = service.capabilities().payload();
        state.set(2);
        IntegrationCapabilities unavailable = service.capabilities().payload();

        assertThat(available.features())
                .containsEntry("externalObservationArchiveReconciliationConfigured", true)
                .containsEntry("externalObservationArchiveReconciliationReadiness", true);
        assertThat(degraded.features())
                .containsEntry("externalObservationArchiveReconciliationConfigured", true)
                .containsEntry("externalObservationArchiveReconciliationReadiness", false);
        assertThat(degraded.testability().externalArchiveReconciliation().violations())
                .containsExactly("SCHEDULER_STALE");
        assertThat(unavailable.features())
                .containsEntry("externalObservationArchiveReconciliationConfigured", true)
                .containsEntry("externalObservationArchiveReconciliationReadiness", false);
        assertThat(unavailable.testability().externalArchiveReconciliation().state())
                .isEqualTo("STORE_UNAVAILABLE");
        assertThat(unavailable.toString()).doesNotContain(
                "archive-a", "secret-fingerprint");
    }

    @Test
    void capabilitiesFailClosedForAuthorityAmbiguityAndDescriptorFailure() {
        ToolStudioIntegrationService service = service(null, null, null, null);
        TestSuiteStabilityJobAuthorizer broken =
                mock(TestSuiteStabilityJobAuthorizer.class);
        TestSuiteStabilityJobAuthorizer duplicate =
                mock(TestSuiteStabilityJobAuthorizer.class);
        when(broken.descriptor()).thenThrow(new IllegalStateException("trust refresh failed"));
        AtomicBoolean ambiguous = new AtomicBoolean(true);
        @SuppressWarnings("unchecked")
        ObjectProvider<TestSuiteStabilityJobAuthorizer> providers =
                mock(ObjectProvider.class);
        when(providers.orderedStream()).thenAnswer(ignored -> ambiguous.get()
                ? Stream.of(broken, duplicate) : Stream.of(broken));
        service.configureTestability(new TestabilityAvailability(
                true, true, WorkerQuarantineRequestIndexMode.DUAL_READ_KEYED_WRITE,
                WorkerQuarantineChangeAuthorizationTrustStore.unavailable().descriptor(),
                authorityDescriptor(true)));
        service.configureSuiteStabilityAuthorizers(providers);

        assertThat(service.capabilities().payload().testability())
                .satisfies(testability -> {
                    assertThat(testability.suiteStabilityJobSubmissionEnabled()).isFalse();
                    assertThat(testability.suiteStabilityCurrentAuthority().available()).isFalse();
                });

        ambiguous.set(false);
        assertThat(service.capabilities().payload().testability())
                .satisfies(testability -> {
                    assertThat(testability.suiteStabilityJobSubmissionEnabled()).isFalse();
                    assertThat(testability.suiteStabilityCurrentAuthority().available()).isFalse();
                });
    }

    @Test
    void wrapsSemanticWorkbookInTheStableIntegrationEnvelope() {
        SemanticCorrectnessWorkbookProjectionService projection =
                mock(SemanticCorrectnessWorkbookProjectionService.class);
        ToolStudioIntegrationService service = service(null, null, null, null);
        service.configureSemanticWorkbookProjection(projection);
        IntegrationRequestContext context = integrationContext("corr-semantic-workbook");
        SemanticCorrectnessWorkbookBundle workbook = semanticWorkbook();
        when(projection.project("suite-risk", 2, context)).thenReturn(workbook);

        IntegrationEnvelope<SemanticCorrectnessWorkbookBundle> envelope =
                service.semanticCorrectnessWorkbook("suite-risk", 2, context);

        assertThat(envelope.payloadKind()).isEqualTo("SEMANTIC_CORRECTNESS_WORKBOOK_BUNDLE");
        assertThat(envelope.payloadSchemaVersion())
                .isEqualTo(SemanticCorrectnessWorkbookBundle.SCHEMA_VERSION);
        assertThat(envelope.payloadFingerprint()).startsWith("sha256:");
        assertThat(envelope.payload()).isSameAs(workbook);
    }

    @Test
    void mapsSemanticWorkbookSourceAndStoreFailuresToStableIntegrationProblems() {
        SemanticCorrectnessWorkbookProjectionService projection =
                mock(SemanticCorrectnessWorkbookProjectionService.class);
        ToolStudioIntegrationService service = service(null, null, null, null);
        service.configureSemanticWorkbookProjection(projection);
        IntegrationRequestContext context = integrationContext("corr-semantic-workbook");
        when(projection.project("suite-risk", 2, context))
                .thenThrow(new SemanticCorrectnessWorkbookProjectionService.ProjectionException(
                        "SUITE_EVIDENCE_GENERATION_MISMATCH", "mixed protocol generations"))
                .thenThrow(new SemanticCorrectnessWorkbookProjectionService.StoreUnavailableException(
                        "history unavailable", new IllegalStateException("database offline")));

        assertThatThrownBy(() -> service.semanticCorrectnessWorkbook("suite-risk", 2, context))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    IntegrationProblem problem = failure.problem();
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code())
                            .isEqualTo("RG.INTEGRATION.SEMANTIC_WORKBOOK_SOURCE_INVALID");
                    assertThat(problem.details()).containsEntry(
                            "reason", "SUITE_EVIDENCE_GENERATION_MISMATCH");
                });
        assertThatThrownBy(() -> service.semanticCorrectnessWorkbook("suite-risk", 2, context))
                .isInstanceOfSatisfying(IntegrationProblemException.class, failure -> {
                    IntegrationProblem problem = failure.problem();
                    assertThat(problem.status()).isEqualTo(503);
                    assertThat(problem.code())
                            .isEqualTo("RG.INTEGRATION.SEMANTIC_WORKBOOK_STORE_UNAVAILABLE");
                    assertThat(problem.retryable()).isTrue();
                });
    }

    @Test
    void distinguishesEvidenceKeyProviderOutageFromUnknownKey() {
        VisualGraphRunRepository runs = new InMemoryVisualGraphRunRepository(VisualEvidenceSigner.unavailable());
        ToolStudioIntegrationService service = service(null, null, null, runs);

        assertThatThrownBy(() -> service.evidenceKey("kms-key-1"))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblem problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(503);
                    assertThat(problem.retryable()).isTrue();
                    assertThat(problem.code()).isEqualTo("RG.INTEGRATION.EVIDENCE_KEY_PROVIDER_UNAVAILABLE");
                });
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
        assertThat(first.payload().dependencyProfile().schemaVersion())
                .isEqualTo(GraphDraftDependencyProfile.SCHEMA_VERSION);
        assertThat(first.payload().dependencyProfile().snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.consistencyStatus()).isEqualTo("STABLE");
            assertThat(snapshot.fingerprint()).startsWith("sha256:");
            assertThat(snapshot.operatorCount()).isEqualTo(1);
            assertThat(snapshot.capturedAt()).isEqualTo(Instant.EPOCH);
        });
        assertThat(first.payloadFingerprint()).isEqualTo(second.payloadFingerprint());
    }

    @Test
    void rejectsExportWhenRelevantDependenciesDriftDuringAssembly() {
        OperatorDefinition operator = operator();
        GraphDraft draft = draft(operator);
        GraphDraftRepository repository = mock(GraphDraftRepository.class);
        when(repository.findRevision("draft-1", 7)).thenReturn(Optional.of(draft));
        GraphDraftValidator validator = mock(GraphDraftValidator.class);
        when(validator.validate(draft)).thenReturn(new VisualValidationResult(true, List.of()));
        VisualOperatorCatalog catalog = catalog(operator);
        GraphDraftDependencySnapshotService delegate = new GraphDraftDependencySnapshotService(catalog);
        AtomicInteger reads = new AtomicInteger();
        GraphDraftDependencySnapshotService drifting = new GraphDraftDependencySnapshotService(catalog) {
            @Override
            public Snapshot capture(GraphDraft ignored) {
                Snapshot snapshot = delegate.capture(ignored);
                return reads.incrementAndGet() == 1 ? snapshot : new Snapshot(
                        snapshot.fingerprint() + "-changed", snapshot.capturedAt(), snapshot.operators(),
                        snapshot.catalog(), snapshot.assets());
            }
        };
        ToolStudioIntegrationService service = new ToolStudioIntegrationService(
                repository, validator, catalog, null, new InMemoryGovernanceGateResultRepository(),
                new ObjectMapper().findAndRegisterModules(), IntegrationIdentityResolver.unavailable(),
                new SideEffectReconcilerRegistry(List.of()), drifting);

        assertThatThrownBy(() -> service.exportDraft("draft-1", 7, integrationContext("corr-drift")))
                .isInstanceOf(IntegrationProblemException.class)
                .satisfies(failure -> {
                    IntegrationProblem problem = ((IntegrationProblemException) failure).problem();
                    assertThat(problem.status()).isEqualTo(409);
                    assertThat(problem.code()).isEqualTo("RG.INTEGRATION.DRAFT_SNAPSHOT_CHANGED");
                    assertThat(problem.retryable()).isTrue();
                    assertThat(problem.details()).containsEntry("draftStable", true);
                });
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
        assertThat(envelope.payload().retention().payloadPolicy()).isEqualTo("DETACHED_SANITIZED");

        IntegrationEnvelope<com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner.VerificationKey> key =
                service.evidenceKey(envelope.payload().manifest().keyId());
        assertThat(key.payload().keyId()).isEqualTo(envelope.payload().manifest().keyId());
        assertThat(key.payload().encodedPublicKey()).isNotBlank();
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(key.payload().encodedPublicKey()))));
        verifier.update(envelope.payload().manifest().manifestHash().getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.verify(Base64.getDecoder().decode(envelope.payload().manifest().signature()))).isTrue();

        IntegrationEnvelope<EvidenceVerificationKeySet> keySetEnvelope = service.evidenceKeySet();
        EvidenceVerificationKeySet keySet = keySetEnvelope.payload();
        assertThat(keySetEnvelope.payloadKind()).isEqualTo("EVIDENCE_VERIFICATION_KEY_SET");
        assertThat(keySet.policyCompleteness())
                .isEqualTo(EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE);
        assertThat(keySet.keys()).singleElement().satisfies(policy -> {
            assertThat(policy.keyId()).isEqualTo(key.payload().keyId());
            assertThat(policy.state()).isEqualTo(EvidenceVerificationKeySet.KeyState.ACTIVE);
        });
        assertThat(keySet.events()).extracting(EvidenceVerificationKeySet.LifecycleEvent::type)
                .containsExactly(EvidenceVerificationKeySet.EventType.CREATED,
                        EvidenceVerificationKeySet.EventType.ACTIVATED);
        assertThat(keySet.snapshotFingerprint()).isEqualTo(ProtocolFingerprint.of(
                new ObjectMapper().findAndRegisterModules(), keySet.material()));
        Signature keySetVerifier = Signature.getInstance("Ed25519");
        keySetVerifier.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                Base64.getDecoder().decode(keySet.keys().getFirst().encodedPublicKey()))));
        keySetVerifier.update(keySet.snapshotFingerprint().getBytes(StandardCharsets.UTF_8));
        assertThat(keySetVerifier.verify(Base64.getDecoder().decode(keySet.attestation().signature()))).isTrue();
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
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().gaps())
                .contains("One or more external side-effect attempts do not have a definitive commit outcome.");
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
        assertThat(evidence.manifest().evidenceStatus()).isEqualTo("QUARANTINED");
        assertThat(evidence.manifest().gaps())
                .contains("One or more external side-effect attempts do not have a definitive commit outcome.");
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

    private static VisualNodeExecutionFact committedSideEffectFact() {
        VisualSideEffectAttempt.Receipt receipt = new VisualSideEffectAttempt.Receipt(
                "receipt-42", "payments", "txn-42", Instant.parse("2026-07-12T00:00:02Z"),
                new VisualSideEffectAttempt.Proof("kms://receipts/42", "sha256:" + "a".repeat(64)));
        VisualSideEffectAttempt attempt = new VisualSideEffectAttempt(
                "attempt-42",
                new VisualSideEffectAttempt.Request("payments.charge", "sha256:" + "A".repeat(43),
                        "payments.status", "vault://commands/charge-42",
                        Instant.parse("2026-07-12T00:00:00Z"), 0),
                "COMMITTED", receipt,
                List.of(
                        new VisualSideEffectAttempt.Transition(1, "PREPARED",
                                Instant.parse("2026-07-12T00:00:00Z"), "ATTEMPT_PREPARED", null),
                        new VisualSideEffectAttempt.Transition(2, "COMMITTED",
                                Instant.parse("2026-07-12T00:00:02Z"), "PROVIDER_CONFIRMED", receipt)));
        return new VisualNodeExecutionFact(
                "SUCCESS", "NONE", "ENGINE_STATUS", List.of(),
                new VisualNodeExecutionFact.Retry(1, 1, false, ""),
                new VisualNodeExecutionFact.Timeout(false, 0, false),
                new VisualNodeExecutionFact.Fallback(false, false, "NONE", ""),
                "COMMITTED", List.of(attempt), List.of());
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

    private static TestSuiteStabilityJobAuthorizer.Descriptor authorityDescriptor(
            boolean available) {
        return new TestSuiteStabilityJobAuthorizer.Descriptor(
                "", available, "HTTPS_PDP", "corporate-iam", Map.of(
                "protocolVersion", "bloge.testSuiteStabilityAuthorityRequest.v1",
                "responseProtocolVersion", "bloge.testSuiteStabilityAuthorityResponse.v1",
                "signedDecisions", true,
                "challengeBound", true,
                "redirectsFollowed", false,
                "automaticRetries", false,
                "privateMaterialPresent", false,
                "requestTimeoutMillis", 3_000));
    }

    private static SemanticCorrectnessWorkbookBundle semanticWorkbook() {
        String fingerprint = "sha256:" + "a".repeat(64);
        TestSuite.FixtureBundleRef fixture =
                new TestSuite.FixtureBundleRef("fixture-risk", 3, fingerprint);
        SemanticCorrectnessWorkbookBundle.Suite suite =
                new SemanticCorrectnessWorkbookBundle.Suite(
                        "bloge.testSuite.v2", "suite-risk", 2, fingerprint,
                        new TestSuite.Target("GRAPH", "risk", fingerprint), "RESTRICTED",
                        List.of(new SemanticCorrectnessWorkbookBundle.CaseRef(
                                "golden", TestSuite.CaseType.GOLDEN, fixture, List.of("release"))),
                        TestSuite.CoveragePolicy.defaults(), null,
                        TestSuite.PromotionPolicy.defaults(), fingerprint);
        return new SemanticCorrectnessWorkbookBundle("", "", suite, List.of(), null);
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
