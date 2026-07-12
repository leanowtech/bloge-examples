package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Sanitized replay payload stored outside immutable run evidence. */
public record VisualRunPayloadSnapshot(
        String schemaVersion,
        String runId,
        String tenantId,
        String namespace,
        String environment,
        Map<String, Object> context,
        Object output,
        Map<String, Object> results,
        Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
        VisualPayloadRedactionManifest redaction,
        String payloadFingerprint
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunPayloadSnapshot.v1";

    public VisualRunPayloadSnapshot {
        schemaVersion = normalize(schemaVersion, SCHEMA_VERSION);
        runId = normalize(runId, "");
        tenantId = normalize(tenantId, "");
        namespace = normalize(namespace, "");
        environment = normalize(environment, "");
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        results = results == null ? Map.of() : new LinkedHashMap<>(results);
        nodeAttempts = immutableAttempts(nodeAttempts);
        redaction = redaction == null ? VisualPayloadRedactionManifest.empty() : redaction;
        String calculated = fingerprint(runId, tenantId, namespace, environment, context, output, results,
                nodeAttempts, redaction);
        payloadFingerprint = payloadFingerprint == null || payloadFingerprint.isBlank()
                ? calculated : payloadFingerprint;
        if (!payloadFingerprint.equals(calculated)) {
            throw new IllegalArgumentException("Visual run payload fingerprint does not match its content");
        }
    }

    public static VisualRunPayloadSnapshot from(VisualGraphRunRecord record) {
        return new VisualRunPayloadSnapshot("", record.runId(), record.tenantId(), record.namespace(),
                record.environment(), record.contextPayload(), record.outputPayload(), record.resultsPayload(),
                record.nodeAttempts(), record.redaction(), "");
    }

    private static String fingerprint(String runId,
                                      String tenantId,
                                      String namespace,
                                      String environment,
                                      Map<String, Object> context,
                                      Object output,
                                      Map<String, Object> results,
                                      Map<String, List<VisualNodeExecutionAttempt>> nodeAttempts,
                                      VisualPayloadRedactionManifest redaction) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("runId", runId);
        material.put("tenantId", tenantId);
        material.put("namespace", namespace);
        material.put("environment", environment);
        material.put("context", context);
        material.put("output", output == null ? "" : output);
        material.put("results", results);
        material.put("nodeAttempts", nodeAttempts);
        material.put("redaction", redaction);
        return VisualBundleFingerprint.fromMaterial(material);
    }

    private static Map<String, List<VisualNodeExecutionAttempt>> immutableAttempts(
            Map<String, List<VisualNodeExecutionAttempt>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<VisualNodeExecutionAttempt>> copy = new LinkedHashMap<>();
        values.forEach((key, attempts) -> copy.put(key, attempts == null ? List.of() : List.copyOf(attempts)));
        return copy;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
