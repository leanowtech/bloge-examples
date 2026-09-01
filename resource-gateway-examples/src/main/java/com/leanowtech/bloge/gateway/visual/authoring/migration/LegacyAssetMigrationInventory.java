package com.leanowtech.bloge.gateway.visual.authoring.migration;

import java.util.List;

/** Payload-free inventory of legacy authoring assets and their explicit compatibility path. */
public record LegacyAssetMigrationInventory(
        String schemaVersion,
        Summary summary,
        List<Item> items
) {
    public static final String SCHEMA_VERSION = "bloge.legacyAssetMigrationInventory.v1";

    public LegacyAssetMigrationInventory {
        schemaVersion = schemaVersion == null ? SCHEMA_VERSION : schemaVersion;
        summary = summary == null ? new Summary(0, 0, 0, 0) : summary;
        items = items == null ? List.of() : List.copyOf(items);
    }

    /** Counts one exact inventory projection without implying that any mutation occurred. */
    public record Summary(int total, int readyToReauthor, int needsRepair, int legacyOnly) { }

    /** One payload-free compatibility decision for one legacy coordinate. */
    public record Item(
            Kind kind,
            String sourceId,
            long sourceRevision,
            String displayName,
            Status status,
            int fixtureReferences,
            List<String> reasonCodes,
            Action action
    ) {
        public Item {
            if (kind == null || sourceId == null || sourceId.isBlank() || displayName == null
                    || displayName.isBlank() || status == null || action == null) {
                throw new IllegalArgumentException("A legacy inventory item requires an exact public identity");
            }
            if (sourceRevision < 0 || fixtureReferences < 0) {
                throw new IllegalArgumentException("Legacy inventory counts cannot be negative");
            }
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    public enum Kind { API_RESOURCE, REUSABLE_FLOW_DRAFT, REUSABLE_FLOW_VERSION, FIXTURE_SET }

    public enum Status { READY_TO_REAUTHOR, NEEDS_REPAIR, LEGACY_ONLY }

    /** A relative, server-selected recovery action; it never carries an external destination. */
    public record Action(ActionKind kind, String path) {
        public Action {
            if (kind == null || path == null || !path.startsWith("/")) {
                throw new IllegalArgumentException("A legacy recovery action must use a relative application path");
            }
        }
    }

    public enum ActionKind { REAUTHOR_RESOURCE, REPAIR_SOURCE, OPEN_LEGACY_FLOW, REAUTHOR_FIXTURE }
}
