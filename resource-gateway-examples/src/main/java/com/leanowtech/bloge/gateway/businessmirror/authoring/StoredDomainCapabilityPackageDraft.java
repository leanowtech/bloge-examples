package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.DomainCapabilityPackageDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.time.Instant;
import java.util.regex.Pattern;

/** Repository-owned envelope for one exact mutable Package revision. */
public record StoredDomainCapabilityPackageDraft(
        String schemaVersion,
        String draftFingerprint,
        DomainCapabilityPackageDraft draft,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy
) {
    public static final String SCHEMA_VERSION =
            "resourceGateway.storedDomainCapabilityPackageDraft.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    public StoredDomainCapabilityPackageDraft {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        draftFingerprint = draftFingerprint == null ? "" : draftFingerprint.trim();
        draft = java.util.Objects.requireNonNull(draft, "draft");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
        updatedBy = updatedBy == null ? "" : updatedBy.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(draftFingerprint).matches()
                || draft.revision() < 1
                || updatedBy.isBlank()
                || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Stored Package draft is incomplete or unsupported");
        }
    }

    public String packageId() {
        return draft.packageId();
    }

    public long revision() {
        return draft.revision();
    }

    public CapabilitySnapshot.Scope scope() {
        return draft.scope();
    }
}
