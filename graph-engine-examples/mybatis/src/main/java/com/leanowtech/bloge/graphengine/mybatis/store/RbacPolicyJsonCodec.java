package com.leanowtech.bloge.graphengine.mybatis.store;

import com.leanowtech.bloge.core.checkpoint.CheckpointCodec;
import com.leanowtech.bloge.graphengine.model.RbacPolicy;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Serializes {@link RbacPolicy} values into JSON object payloads.
 */
final class RbacPolicyJsonCodec {
    private RbacPolicyJsonCodec() {
    }

    static String encode(RbacPolicy policy, CheckpointCodec checkpointCodec) {
        if (policy == null) {
            return null;
        }
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("viewRoles", policy.viewRoles());
        payload.put("startRoles", policy.startRoles());
        payload.put("deployRoles", policy.deployRoles());
        payload.put("adminRoles", policy.adminRoles());
        return checkpointCodec.serialize(payload);
    }

    static RbacPolicy decode(String json, CheckpointCodec checkpointCodec) {
        if (json == null || json.isBlank()) {
            return new RbacPolicy(null, null, null, null);
        }
        Map<String, Object> payload = GraphEngineJsonSupport.decodeMap(checkpointCodec, json);
        return new RbacPolicy(
                GraphEngineJsonSupport.stringSet(payload.get("viewRoles")),
                GraphEngineJsonSupport.stringSet(payload.get("startRoles")),
                GraphEngineJsonSupport.stringSet(payload.get("deployRoles")),
                GraphEngineJsonSupport.stringSet(payload.get("adminRoles"))
        );
    }
}
