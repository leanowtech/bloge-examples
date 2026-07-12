package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Machine-readable integration capability and compatibility probe.
 */
public record IntegrationCapabilities(
        String schemaVersion,
        String protocol,
        String protocolVersion,
        Map<String, List<String>> supportedObjects,
        Map<String, Boolean> features,
        List<Endpoint> endpoints
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.capabilities.v1";

    public IntegrationCapabilities {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        protocol = protocol == null || protocol.isBlank() ? ToolStudioResourceGatewayProtocol.NAME : protocol;
        protocolVersion = protocolVersion == null || protocolVersion.isBlank()
                ? ToolStudioResourceGatewayProtocol.VERSION : protocolVersion;
        supportedObjects = supportedObjects == null ? Map.of() : immutableLists(supportedObjects);
        features = features == null ? Map.of() : new LinkedHashMap<>(features);
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public static IntegrationCapabilities current() {
        return current(false);
    }

    public static IntegrationCapabilities current(boolean evidenceSignature) {
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("graphDraft", List.of(GraphDraft.SCHEMA_VERSION));
        objects.put("operatorLibrary", List.of("bloge.visualOperatorLibrary.v1"));
        objects.put("graphDraftIntegrationBundle", List.of(GraphDraftIntegrationBundle.SCHEMA_VERSION));
        objects.put("runEvidence", List.of(RunEvidenceBundle.SCHEMA_VERSION_V1, RunEvidenceBundle.SCHEMA_VERSION));
        objects.put("payloadReplay", List.of(PayloadReplayBundle.SCHEMA_VERSION));
        objects.put("replayExecutionRequest", List.of(ReplayExecutionRequest.SCHEMA_VERSION));
        objects.put("replayExecutionResult", List.of(ReplayExecutionResult.SCHEMA_VERSION));
        objects.put("evidenceVerificationKey", List.of(
                com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner.VerificationKey.SCHEMA_VERSION));
        objects.put("governanceGateResult", List.of(GovernanceGateResult.SCHEMA_VERSION));
        objects.put("integrationEvent", List.of(IntegrationChangeEvent.SCHEMA_VERSION));
        objects.put("eventCursor", List.of(IntegrationEventCursorCodec.SCHEMA_VERSION));
        objects.put("changeFeed", List.of(IntegrationChangeFeed.SCHEMA_VERSION));
        objects.put("reconciliationSnapshot", List.of(IntegrationReconciliationSnapshot.SCHEMA_VERSION));
        objects.put("contractTestSuite", List.of(
                com.leanowtech.bloge.gateway.visual.testing.VisualOperatorContractTestSuite.SCHEMA_VERSION));

        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("draftExportDependencyProfile", true);
        features.put("runEvidenceBundle", true);
        features.put("payloadReplay", true);
        features.put("payloadReplayNodeInputs", true);
        features.put("recordedAssertionReplay", true);
        features.put("replayExternalSideEffects", false);
        features.put("evidenceIntegrityManifest", true);
        features.put("evidenceSignature", evidenceSignature);
        features.put("deepLinks", true);
        features.put("governanceGateFeedback", true);
        features.put("transactionalOutbox", true);
        features.put("eventCursor", true);
        features.put("reconciliationSnapshot", true);
        features.put("webhook", false);

        return new IntegrationCapabilities("", "", "", objects, features, List.of(
                new Endpoint("GET", "/api/integration/capabilities"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/export"),
                new Endpoint("GET", "/api/integration/runs/{runId}/evidence"),
                new Endpoint("GET", "/api/integration/runs/{runId}/replay"),
                new Endpoint("POST", "/api/integration/runs/{runId}/replay"),
                new Endpoint("GET", "/api/integration/evidence-keys/{keyId}"),
                new Endpoint("POST", "/api/integration/gate-results"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/gate-result"),
                new Endpoint("GET", "/api/integration/events"),
                new Endpoint("GET", "/api/integration/reconciliation"),
                new Endpoint("GET", "/api/integration/operator-libraries/{libraryId}"),
                new Endpoint("GET", "/api/integration/operator-test-suites/{suiteId}")
        ));
    }

    private static Map<String, List<String>> immutableLists(Map<String, List<String>> values) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach((key, value) -> copy.put(key, value == null ? List.of() : List.copyOf(value)));
        return copy;
    }

    public record Endpoint(String method, String path) {
        public Endpoint {
            method = method == null ? "" : method.trim().toUpperCase();
            path = path == null ? "" : path.trim();
        }
    }
}
