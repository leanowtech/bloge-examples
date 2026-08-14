package com.leanowtech.bloge.gateway.businessmirror.authoring;

import java.util.List;

/** Bounded Package index page in one verified enterprise scope. */
public record DomainCapabilityPackagePage(
        String schemaVersion,
        List<StoredDomainCapabilityPackageDraft> items,
        String nextCursor
) {
    public static final String SCHEMA_VERSION = "resourceGateway.domainCapabilityPackagePage.v1";

    public DomainCapabilityPackagePage {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        items = items == null ? List.of() : List.copyOf(items);
        nextCursor = nextCursor == null ? "" : nextCursor.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion) || items.size() > 200) {
            throw new IllegalArgumentException("Package page is incomplete or unsupported");
        }
    }
}
