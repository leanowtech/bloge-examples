package com.leanowtech.bloge.gateway.authoring.workspace;

/** Request fingerprint paired with the durable receipt used for collision-safe retries. */
public record StoredWorkspaceForkReceipt(
        String requestFingerprint,
        WorkspaceForkReceipt receipt
) {
    public StoredWorkspaceForkReceipt {
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        if (requestFingerprint.isBlank() || receipt == null) {
            throw new IllegalArgumentException("Stored Workspace fork receipt is incomplete");
        }
    }
}
