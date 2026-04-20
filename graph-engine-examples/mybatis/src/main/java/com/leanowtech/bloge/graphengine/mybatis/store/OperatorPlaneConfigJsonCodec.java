package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.graphengine.model.OperatorPlaneConfig;
import com.leanowtech.bloge.graphengine.model.RemoteWorkerBinding;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link OperatorPlaneConfig} values to JSON.
 */
final class OperatorPlaneConfigJsonCodec {
    private OperatorPlaneConfigJsonCodec() {
    }

    static String encode(OperatorPlaneConfig config, CheckpointCodec checkpointCodec) {
        if (config == null) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("builtinEnabled", config.builtinEnabled());
        payload.put("pluginClasspath", config.pluginClasspath());
        if (!config.remoteWorkers().isEmpty()) {
            LinkedHashMap<String, Object> workers = new LinkedHashMap<>();
            config.remoteWorkers().forEach((operatorRef, binding) -> workers.put(operatorRef, encodeBinding(binding)));
            payload.put("remoteWorkers", workers);
        }
        return checkpointCodec.serialize(payload);
    }

    static OperatorPlaneConfig decode(String json, CheckpointCodec checkpointCodec) {
        if (json == null || json.isBlank()) {
            return OperatorPlaneConfig.defaults();
        }
        Map<String, Object> payload = GraphEngineJsonSupport.decodeMap(checkpointCodec, json);
        return new OperatorPlaneConfig(
                GraphEngineJsonSupport.booleanValue(payload.get("builtinEnabled"), true),
                GraphEngineJsonSupport.stringList(payload.get("pluginClasspath")),
                decodeBindings(payload.get("remoteWorkers"))
        );
    }

    private static Map<String, RemoteWorkerBinding> decodeBindings(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Expected remoteWorkers object");
        }
        LinkedHashMap<String, RemoteWorkerBinding> decoded = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> bindingMap)) {
                throw new IllegalArgumentException("Expected remote worker binding object");
            }
            Map<String, Object> payload = GraphEngineJsonSupport.castMap(bindingMap);
            decoded.put(
                    String.valueOf(entry.getKey()),
                    new RemoteWorkerBinding(
                            GraphEngineJsonSupport.stringValue(payload.get("workerId")),
                            GraphEngineJsonSupport.stringValue(payload.get("topic")),
                            GraphEngineJsonSupport.stringValue(payload.get("endpoint")),
                            GraphEngineJsonSupport.stringMap(payload.get("labels"))
                    )
            );
        }
        return Map.copyOf(decoded);
    }

    private static Map<String, Object> encodeBinding(RemoteWorkerBinding binding) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("workerId", binding.workerId());
        payload.put("topic", binding.topic());
        payload.put("endpoint", binding.endpoint());
        payload.put("labels", binding.labels());
        return payload;
    }
}
