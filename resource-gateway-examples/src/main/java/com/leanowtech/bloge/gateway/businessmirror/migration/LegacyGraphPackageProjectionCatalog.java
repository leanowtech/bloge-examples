package com.leanowtech.bloge.gateway.businessmirror.migration;

import com.leanowtech.bloge.gateway.integration.mirror.CapabilitySnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Complete bounded catalog of Legacy Graph migration previews in one enterprise Scope. */
public record LegacyGraphPackageProjectionCatalog(
        String schemaVersion,
        CapabilitySnapshot.Scope scope,
        List<LegacyGraphPackageProjection> items
) {
    public static final String SCHEMA_VERSION =
            "resourceGateway.legacyGraphPackageProjectionCatalog.v1";

    public LegacyGraphPackageProjectionCatalog {
        schemaVersion = schemaVersion == null || schemaVersion.isBlank()
                ? SCHEMA_VERSION : schemaVersion.trim();
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        scope = Objects.requireNonNull(scope, "scope");
        CapabilitySnapshot.Scope catalogScope = scope;
        items = items == null ? List.of() : items.stream()
                .map(value -> Objects.requireNonNull(value, "projection"))
                .sorted(Comparator.comparing(LegacyGraphPackageProjection::graphName))
                .toList();
        if (items.size() > 256
                || items.stream().map(LegacyGraphPackageProjection::graphName)
                .distinct().count() != items.size()
                || items.stream().anyMatch(value -> !catalogScope.equals(value.scope()))) {
            throw new IllegalArgumentException(
                    "Legacy Graph projection catalog must be unique and single-Scope");
        }
    }
}
