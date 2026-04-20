package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.graphengine.model.GraphExecutionMode;
import com.leanowtech.bloge.graphengine.model.GraphVersionMetadata;
import com.leanowtech.bloge.graphengine.model.TaskDefinition;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link GraphVersionMetadata} values into durable JSON payloads.
 */
final class GraphVersionMetadataJsonCodec {
    private GraphVersionMetadataJsonCodec() {
    }

    static String encode(GraphVersionMetadata metadata, CheckpointCodec checkpointCodec) {
        if (metadata == null) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("executionMode", metadata.executionMode().name());
        if (!metadata.operatorRefs().isEmpty()) {
            payload.put("operatorRefs", metadata.operatorRefs());
        }
        if (!metadata.operatorFingerprints().isEmpty()) {
            payload.put("operatorFingerprints", metadata.operatorFingerprints());
        }
        if (metadata.inputSchema() != null) {
            payload.put("inputSchema", GraphEngineJsonSupport.encodeSchema(metadata.inputSchema()));
        }
        if (metadata.outputSchema() != null) {
            payload.put("outputSchema", GraphEngineJsonSupport.encodeSchema(metadata.outputSchema()));
        }
        if (!metadata.taskDefinitions().isEmpty()) {
            payload.put("taskDefinitions", encodeTaskDefinitions(metadata.taskDefinitions()));
        }
        if (!metadata.migrationHints().isEmpty()) {
            payload.put("migrationHints", metadata.migrationHints());
        }
        return checkpointCodec.serialize(payload);
    }

    static GraphVersionMetadata decode(String json, CheckpointCodec checkpointCodec) {
        if (json == null || json.isBlank()) {
            return new GraphVersionMetadata(null, null, null, null, null, null, null);
        }
        Map<String, Object> payload = GraphEngineJsonSupport.decodeMap(checkpointCodec, json);
        String executionMode = GraphEngineJsonSupport.stringValue(payload.get("executionMode"));
        return new GraphVersionMetadata(
                executionMode == null ? GraphExecutionMode.GRAPH : GraphExecutionMode.valueOf(executionMode),
                GraphEngineJsonSupport.stringList(payload.get("operatorRefs")),
                GraphEngineJsonSupport.stringMap(payload.get("operatorFingerprints")),
                GraphEngineJsonSupport.decodeSchema(payload.get("inputSchema")),
                GraphEngineJsonSupport.decodeSchema(payload.get("outputSchema")),
                decodeTaskDefinitions(payload.get("taskDefinitions")),
                GraphEngineJsonSupport.objectMap(payload.get("migrationHints"))
        );
    }

    private static Map<String, Object> encodeTaskDefinitions(Map<String, TaskDefinition> taskDefinitions) {
        LinkedHashMap<String, Object> encoded = new LinkedHashMap<>();
        taskDefinitions.forEach((nodeId, definition) -> {
            LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
            payload.put("nodeId", definition.nodeId());
            payload.put("taskType", definition.taskType());
            payload.put("formRef", definition.formRef());
            payload.put("defaultAssignee", definition.defaultAssignee());
            payload.put("candidateGroups", definition.candidateGroups());
            payload.put("candidateRoles", definition.candidateRoles());
            if (definition.payloadSchema() != null) {
                payload.put("payloadSchema", GraphEngineJsonSupport.encodeSchema(definition.payloadSchema()));
            }
            encoded.put(nodeId, payload);
        });
        return encoded;
    }

    private static Map<String, TaskDefinition> decodeTaskDefinitions(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected taskDefinitions object");
        }
        LinkedHashMap<String, TaskDefinition> decoded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> taskMap)) {
                throw new IllegalArgumentException("Expected task definition object");
            }
            Map<String, Object> payload = GraphEngineJsonSupport.castMap(taskMap);
            String nodeId = GraphEngineJsonSupport.stringValue(payload.get("nodeId"));
            decoded.put(
                    String.valueOf(entry.getKey()),
                    new TaskDefinition(
                            nodeId == null ? String.valueOf(entry.getKey()) : nodeId,
                            GraphEngineJsonSupport.stringValue(payload.get("taskType")),
                            GraphEngineJsonSupport.stringValue(payload.get("formRef")),
                            GraphEngineJsonSupport.stringValue(payload.get("defaultAssignee")),
                            GraphEngineJsonSupport.stringList(payload.get("candidateGroups")),
                            GraphEngineJsonSupport.stringList(payload.get("candidateRoles")),
                            GraphEngineJsonSupport.decodeSchema(payload.get("payloadSchema"))
                    )
            );
        }
        return Map.copyOf(decoded);
    }
}
