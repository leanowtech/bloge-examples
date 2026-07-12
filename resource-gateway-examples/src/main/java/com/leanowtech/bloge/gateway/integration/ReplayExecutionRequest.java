package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Command for a zero-external-call assertion replay over one recorded run. */
public record ReplayExecutionRequest(
        String schemaVersion,
        String requestId,
        String mode,
        String caseType,
        String externalSideEffectPolicy,
        List<Assertion> assertions
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.replayExecutionRequest.v1";

    public ReplayExecutionRequest {
        schemaVersion = normalize(schemaVersion).isBlank() ? SCHEMA_VERSION : normalize(schemaVersion);
        requestId = normalize(requestId);
        mode = normalize(mode).isBlank() ? "RECORDED_ASSERTIONS" : normalize(mode).toUpperCase();
        caseType = normalize(caseType).isBlank() ? "REGRESSION" : normalize(caseType).toUpperCase();
        externalSideEffectPolicy = normalize(externalSideEffectPolicy).isBlank()
                ? "DENY" : normalize(externalSideEffectPolicy).toUpperCase();
        assertions = assertions == null ? List.of() : List.copyOf(assertions);
    }

    public String fingerprint() {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", schemaVersion);
        material.put("requestId", requestId);
        material.put("mode", mode);
        material.put("caseType", caseType);
        material.put("externalSideEffectPolicy", externalSideEffectPolicy);
        material.put("assertions", assertions);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    public record Assertion(String assertionId, String scope, String nodeId, String mode, String path,
                            Object expectedValue) {
        public Assertion {
            assertionId = normalize(assertionId);
            scope = normalize(scope).isBlank() ? "OUTPUT" : normalize(scope).toUpperCase();
            nodeId = normalize(nodeId);
            mode = normalize(mode).isBlank() ? "EQUALS" : normalize(mode).toUpperCase();
            path = normalize(path);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
