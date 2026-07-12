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
        Map<String, List<String>> objects = new LinkedHashMap<>();
        objects.put("graphDraft", List.of(GraphDraft.SCHEMA_VERSION));
        objects.put("operatorLibrary", List.of("bloge.visualOperatorLibrary.v1"));
        objects.put("graphDraftIntegrationBundle", List.of(GraphDraftIntegrationBundle.SCHEMA_VERSION));
        objects.put("runEvidence", List.of(RunEvidenceBundle.SCHEMA_VERSION));
        objects.put("payloadReplay", List.of(PayloadReplayBundle.SCHEMA_VERSION));

        Map<String, Boolean> features = new LinkedHashMap<>();
        features.put("draftExportDependencyProfile", true);
        features.put("runEvidenceBundle", true);
        features.put("payloadReplay", true);
        features.put("payloadReplayNodeInputs", false);
        features.put("evidenceIntegrityManifest", true);
        features.put("evidenceSignature", false);
        features.put("deepLinks", false);
        features.put("governanceGateFeedback", false);
        features.put("eventCursor", false);
        features.put("webhook", false);

        return new IntegrationCapabilities("", "", "", objects, features, List.of(
                new Endpoint("GET", "/api/integration/capabilities"),
                new Endpoint("GET", "/api/integration/drafts/{draftId}/export"),
                new Endpoint("GET", "/api/integration/runs/{runId}/evidence"),
                new Endpoint("GET", "/api/integration/runs/{runId}/replay")
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
