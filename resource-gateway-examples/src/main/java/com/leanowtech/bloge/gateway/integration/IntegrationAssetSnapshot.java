package com.leanowtech.bloge.gateway.integration;

/** Stable reference used to reconcile ANEKE's projection with Resource Gateway authority. */
public record IntegrationAssetSnapshot(
        String kind,
        String id,
        long revision,
        String fingerprint,
        String status,
        String payloadRef
) {
    public IntegrationAssetSnapshot {
        kind = kind == null ? "" : kind.trim().toUpperCase();
        id = id == null ? "" : id.trim();
        revision = Math.max(0, revision);
        fingerprint = fingerprint == null ? "" : fingerprint;
        status = status == null ? "" : status.trim().toUpperCase();
        payloadRef = payloadRef == null ? "" : payloadRef;
    }
}
