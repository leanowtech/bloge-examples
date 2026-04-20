package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.core.runtime.registry.VersionRoutingPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link VersionRoutingPolicy} variants to JSON.
 */
final class VersionRoutingPolicyJsonCodec {
    private VersionRoutingPolicyJsonCodec() {
    }

    static String encode(VersionRoutingPolicy policy, CheckpointCodec checkpointCodec) {
        if (policy == null) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        switch (policy) {
            case VersionRoutingPolicy.Latest ignored -> payload.put("kind", "latest");
            case VersionRoutingPolicy.Pinned pinned -> {
                payload.put("kind", "pinned");
                payload.put("version", pinned.version());
            }
            case VersionRoutingPolicy.Canary canary -> {
                payload.put("kind", "canary");
                payload.put("primaryVersion", canary.primaryVersion());
                payload.put("canaryVersion", canary.canaryVersion());
                payload.put("percentage", canary.percentage());
            }
        }
        return checkpointCodec.serialize(payload);
    }

    static VersionRoutingPolicy decode(String json, CheckpointCodec checkpointCodec) {
        if (json == null || json.isBlank()) {
            return new VersionRoutingPolicy.Latest();
        }
        Map<String, Object> payload = GraphEngineJsonSupport.decodeMap(checkpointCodec, json);
        String kind = GraphEngineJsonSupport.stringValue(payload.get("kind"));
        if ("pinned".equals(kind)) {
            return new VersionRoutingPolicy.Pinned(GraphEngineJsonSupport.stringValue(payload.get("version")));
        }
        if ("canary".equals(kind)) {
            return new VersionRoutingPolicy.Canary(
                    GraphEngineJsonSupport.stringValue(payload.get("primaryVersion")),
                    GraphEngineJsonSupport.stringValue(payload.get("canaryVersion")),
                    GraphEngineJsonSupport.integer(payload.get("percentage"))
            );
        }
        return new VersionRoutingPolicy.Latest();
    }
}
