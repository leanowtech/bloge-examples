package com.leanowtech.bloge.gateway.visual.runtime;

import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Sanitized, immutable lineage captured before a managed visual graph begins execution. */
public record VisualRunRecoveryReservation(
        String schemaVersion,
        String reservationId,
        String requestId,
        String runId,
        String sourceKind,
        GraphDraft draft,
        String publicationId,
        String sourceArtifactKind,
        String inputFingerprint,
        Map<String, Object> contextPayload,
        VisualPayloadRedactionManifest redaction,
        String outputNode,
        Instant reservedAt,
        String materialFingerprint
) {
    public static final String SCHEMA_VERSION = "bloge.visualRunRecoveryReservation.v1";

    public VisualRunRecoveryReservation {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        reservationId = reservationId == null ? "" : reservationId.trim();
        requestId = requestId == null ? "" : requestId.trim();
        runId = runId == null ? "" : runId.trim();
        sourceKind = sourceKind == null ? "" : sourceKind.trim().toUpperCase(java.util.Locale.ROOT);
        publicationId = publicationId == null ? "" : publicationId.trim();
        sourceArtifactKind = sourceArtifactKind == null
                ? ""
                : sourceArtifactKind.trim().toUpperCase(java.util.Locale.ROOT);
        inputFingerprint = inputFingerprint == null ? "" : inputFingerprint.trim();
        contextPayload = contextPayload == null ? Map.of() : new LinkedHashMap<>(contextPayload);
        redaction = redaction == null ? VisualPayloadRedactionManifest.empty() : redaction;
        outputNode = outputNode == null ? "" : outputNode.trim();
        reservedAt = reservedAt == null ? Instant.now() : reservedAt;
        materialFingerprint = materialFingerprint == null ? "" : materialFingerprint.trim();
    }

    public static VisualRunRecoveryReservation create(String requestId,
                                                      String sourceKind,
                                                      GraphDraft draft,
                                                      String publicationId,
                                                      String sourceArtifactKind,
                                                      Map<String, Object> context,
                                                      String outputNode,
                                                      Instant reservedAt) {
        String normalizedRequestId = requestId == null ? "" : requestId.trim();
        String normalizedSourceKind = sourceKind == null
                ? ""
                : sourceKind.trim().toUpperCase(java.util.Locale.ROOT);
        String normalizedPublicationId = publicationId == null ? "" : publicationId.trim();
        String normalizedArtifactKind = sourceArtifactKind == null
                ? ""
                : sourceArtifactKind.trim().toUpperCase(java.util.Locale.ROOT);
        String runId = UUID.nameUUIDFromBytes(("bloge-managed-run:" + normalizedRequestId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        GraphDraft safeDraft = draft == null ? null : draft.withNodeFixtures(Map.of());
        VisualPayloadSanitizer.Capture capture = VisualPayloadSanitizer.capture(context, null, Map.of());
        String inputFingerprint = VisualBundleFingerprint.fromMaterial(Map.of(
                "context", context == null ? Map.of() : context));
        String selectedOutput = outputNode == null || outputNode.isBlank()
                ? safeDraft == null ? "" : safeDraft.output().nodeId()
                : outputNode.trim();
        String fingerprint = fingerprint(normalizedRequestId, runId, normalizedSourceKind, safeDraft,
                normalizedPublicationId, normalizedArtifactKind, inputFingerprint, capture.context(),
                capture.redaction(), selectedOutput);
        return new VisualRunRecoveryReservation("", "recovery:" + runId, normalizedRequestId, runId,
                normalizedSourceKind, safeDraft, normalizedPublicationId, normalizedArtifactKind,
                inputFingerprint, capture.context(), capture.redaction(), selectedOutput,
                reservedAt, fingerprint);
    }

    public String recomputedMaterialFingerprint() {
        return fingerprint(requestId, runId, sourceKind, draft, publicationId, sourceArtifactKind, inputFingerprint,
                contextPayload, redaction, outputNode);
    }

    private static String fingerprint(String requestId,
                                      String runId,
                                      String sourceKind,
                                      GraphDraft draft,
                                      String publicationId,
                                      String sourceArtifactKind,
                                      String inputFingerprint,
                                      Map<String, Object> contextPayload,
                                      VisualPayloadRedactionManifest redaction,
                                      String outputNode) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("requestId", requestId);
        material.put("runId", runId);
        material.put("sourceKind", sourceKind);
        material.put("draft", draft == null ? "" : draft);
        material.put("publicationId", publicationId);
        material.put("sourceArtifactKind", sourceArtifactKind);
        material.put("inputFingerprint", inputFingerprint);
        material.put("contextPayload", contextPayload);
        material.put("redaction", redaction);
        material.put("outputNode", outputNode);
        return VisualBundleFingerprint.fromMaterial(material);
    }
}
