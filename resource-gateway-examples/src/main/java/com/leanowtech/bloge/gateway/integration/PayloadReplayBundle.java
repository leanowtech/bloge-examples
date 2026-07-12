package com.leanowtech.bloge.gateway.integration;

import com.leanowtech.bloge.gateway.visual.runtime.VisualGraphRunRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualPayloadRedactionManifest;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sanitized, side-effect-free recorded replay payload for one run.
 */
public record PayloadReplayBundle(
        String schemaVersion,
        String runId,
        String parentRunId,
        String replayMode,
        PayloadPolicy payloadPolicy,
        Map<String, Object> context,
        Object output,
        List<NodeReplay> nodes,
        VisualPayloadRedactionManifest redaction
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.payloadReplayBundle.v1";

    public PayloadReplayBundle {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        runId = runId == null ? "" : runId;
        parentRunId = parentRunId == null ? "" : parentRunId;
        replayMode = replayMode == null || replayMode.isBlank() ? "RECORDED" : replayMode;
        payloadPolicy = payloadPolicy == null ? PayloadPolicy.sanitized() : payloadPolicy;
        context = context == null ? Map.of() : new LinkedHashMap<>(context);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        redaction = redaction == null ? VisualPayloadRedactionManifest.empty() : redaction;
    }

    public static PayloadReplayBundle from(VisualGraphRunRecord record) {
        Set<String> nodeIds = new LinkedHashSet<>(record.nodeSnapshots().keySet());
        nodeIds.addAll(record.resultsPayload().keySet());
        List<NodeReplay> nodes = nodeIds.stream()
                .map(nodeId -> new NodeReplay(nodeId, Map.of(), record.resultsPayload().get(nodeId), false,
                        record.resultsPayload().containsKey(nodeId), List.of()))
                .toList();
        return new PayloadReplayBundle("", record.runId(), record.runId(), "RECORDED",
                PayloadPolicy.sanitized(), record.contextPayload(), record.outputPayload(), nodes,
                record.redaction());
    }

    public record PayloadPolicy(String mode, String redactionProfile, boolean rawAvailable,
                                boolean externalSideEffectsAllowed) {
        static PayloadPolicy sanitized() {
            return new PayloadPolicy("SANITIZED", VisualPayloadRedactionManifest.DEFAULT_PROFILE, false, false);
        }
    }

    public record NodeReplay(String nodeId, Object input, Object output, boolean inputAvailable,
                             boolean outputAvailable, List<Object> assertionResults) {
        public NodeReplay {
            nodeId = nodeId == null ? "" : nodeId;
            assertionResults = assertionResults == null ? List.of() : List.copyOf(assertionResults);
        }
    }
}
