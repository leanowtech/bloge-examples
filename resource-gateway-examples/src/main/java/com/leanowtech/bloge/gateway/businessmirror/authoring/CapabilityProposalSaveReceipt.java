package com.leanowtech.bloge.gateway.businessmirror.authoring;

import java.time.Instant;
import java.util.regex.Pattern;

/** Exact durable response for one canonical Capability Proposal save command. */
public record CapabilityProposalSaveReceipt(
        String schemaVersion,
        String requestFingerprint,
        StoredCapabilityProposalDraft result,
        Instant completedAt
) {
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityProposalSaveReceipt.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    public CapabilityProposalSaveReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        result = java.util.Objects.requireNonNull(result, "result");
        completedAt = java.util.Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("Capability Proposal save receipt is incomplete or unsupported");
        }
    }

    public static CapabilityProposalSaveReceipt completed(
            String requestFingerprint, StoredCapabilityProposalDraft result) {
        return new CapabilityProposalSaveReceipt(SCHEMA_VERSION, requestFingerprint, result,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
}
