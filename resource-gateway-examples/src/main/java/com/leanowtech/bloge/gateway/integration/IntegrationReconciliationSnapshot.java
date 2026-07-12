package com.leanowtech.bloge.gateway.integration;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Authoritative current-state projection paired with an event checkpoint. */
public record IntegrationReconciliationSnapshot(
        String schemaVersion,
        String tenantId,
        String environmentId,
        Instant generatedAt,
        String checkpointCursor,
        List<IntegrationAssetSnapshot> assets,
        Map<String, Integer> countsByKind,
        String rollingFingerprint
) {
    public static final String SCHEMA_VERSION = "toolStudio.resourceGateway.reconciliationSnapshot.v1";

    public IntegrationReconciliationSnapshot {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank() ? SCHEMA_VERSION : schemaVersion;
        tenantId = tenantId == null ? "" : tenantId;
        environmentId = environmentId == null ? "" : environmentId;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
        checkpointCursor = checkpointCursor == null ? "" : checkpointCursor;
        assets = assets == null ? List.of() : List.copyOf(assets);
        countsByKind = countsByKind == null ? Map.of() : new LinkedHashMap<>(countsByKind);
        rollingFingerprint = rollingFingerprint == null ? "" : rollingFingerprint;
    }
}
