package com.leanowtech.bloge.gateway.businessmirror.authoring;

import java.util.List;

/** Bounded Capability Proposal index page in one verified enterprise scope. */
public record CapabilityProposalPage(
        String schemaVersion,
        List<StoredCapabilityProposalDraft> items,
        String nextCursor
) {
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityProposalPage.v1";

    public CapabilityProposalPage {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || items.size() > 200) {
            throw new IllegalArgumentException("Capability Proposal page is incomplete or unsupported");
        }
    }
}
