package com.leanowtech.bloge.gateway.visual.draft;

/** Enterprise isolation coordinate for Graph draft save receipts. */
public record GraphDraftSaveScope(String tenantId, String namespace, String environment) {

    public GraphDraftSaveScope {
        tenantId = normalized(tenantId);
        namespace = normalized(namespace);
        environment = normalized(environment);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
