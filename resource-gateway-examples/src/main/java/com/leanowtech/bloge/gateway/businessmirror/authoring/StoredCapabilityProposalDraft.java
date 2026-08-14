package com.leanowtech.bloge.gateway.businessmirror.authoring;

import com.leanowtech.bloge.gateway.businessmirror.domain.CapabilityProposalDraft;
import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.time.Instant;
import java.util.regex.Pattern;

/** Repository-owned envelope for one exact mutable Capability Proposal revision. */
public record StoredCapabilityProposalDraft(
        String schemaVersion,
        String draftFingerprint,
        CapabilityProposalDraft draft,
        Instant createdAt,
        Instant updatedAt,
        String updatedBy
) {
    public static final String SCHEMA_VERSION = "resourceGateway.storedCapabilityProposalDraft.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    public StoredCapabilityProposalDraft {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        draftFingerprint = draftFingerprint == null ? "" : draftFingerprint.trim();
        draft = java.util.Objects.requireNonNull(draft, "draft");
        createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
        updatedAt = java.util.Objects.requireNonNull(updatedAt, "updatedAt");
        updatedBy = updatedBy == null ? "" : updatedBy.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(draftFingerprint).matches()
                || draft.revision() < 1 || updatedBy.isBlank() || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Stored Capability Proposal draft is incomplete or unsupported");
        }
    }

    public String proposalId() {
        return draft.proposalId();
    }

    public long revision() {
        return draft.revision();
    }

    public CapabilitySnapshot.Scope scope() {
        return draft.scope();
    }
}
