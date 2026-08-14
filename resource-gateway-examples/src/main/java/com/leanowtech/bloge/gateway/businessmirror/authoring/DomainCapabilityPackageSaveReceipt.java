package com.leanowtech.bloge.gateway.businessmirror.authoring;

import java.time.Instant;
import java.util.regex.Pattern;

/** Exact durable response for one canonical Package save command. */
public record DomainCapabilityPackageSaveReceipt(
        String schemaVersion,
        String requestFingerprint,
        StoredDomainCapabilityPackageDraft result,
        Instant completedAt
) {
    public static final String SCHEMA_VERSION = "resourceGateway.domainCapabilityPackageSaveReceipt.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    public DomainCapabilityPackageSaveReceipt {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        requestFingerprint = requestFingerprint == null ? "" : requestFingerprint.trim();
        result = java.util.Objects.requireNonNull(result, "result");
        completedAt = java.util.Objects.requireNonNull(completedAt, "completedAt");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(requestFingerprint).matches()) {
            throw new IllegalArgumentException("Package save receipt is incomplete or unsupported");
        }
    }

    public static DomainCapabilityPackageSaveReceipt completed(
            String requestFingerprint, StoredDomainCapabilityPackageDraft result) {
        return new DomainCapabilityPackageSaveReceipt(
                SCHEMA_VERSION, requestFingerprint, result,
                Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
}
