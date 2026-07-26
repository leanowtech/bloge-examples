package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;

/**
 * Bounded append-ordered lifecycle page for one outcome observation lineage.
 *
 * @param schemaVersion exact page protocol version
 * @param observationId stable observation lineage
 * @param afterOrdinal exclusive requested cursor
 * @param nextOrdinal last returned ordinal or the requested cursor for an empty page
 * @param hasMore whether another event exists
 * @param events oldest-first chained lifecycle facts
 */
public record AuthoritativeOutcomeInboxLifecyclePage(
        String schemaVersion,
        String observationId,
        long afterOrdinal,
        long nextOrdinal,
        boolean hasMore,
        List<AuthoritativeOutcomeInboxLifecycleEvent> events
) {
    /** Exact lifecycle page protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeInboxLifecyclePage.v1";

    /** Enforces bounded contiguous page coordinates. */
    public AuthoritativeOutcomeInboxLifecyclePage {
        schemaVersion = schemaVersion == null
                || schemaVersion.isBlank()
                ? SCHEMA_VERSION
                : schemaVersion.trim();
        observationId = observationId == null
                ? "" : observationId.trim();
        events = events == null ? List.of() : List.copyOf(events);
        String exactObservationId = observationId;
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || observationId.isBlank()
                || afterOrdinal < 0
                || nextOrdinal < afterOrdinal
                || events.size() > 1_000
                || events.isEmpty()
                && nextOrdinal != afterOrdinal
                || !events.isEmpty()
                && (events.getFirst().eventOrdinal()
                != afterOrdinal + 1
                || events.getLast().eventOrdinal()
                != nextOrdinal)
                || events.stream().anyMatch(event ->
                !event.observationId().equals(
                        exactObservationId))) {
            throw new IllegalArgumentException(
                    "outcome inbox lifecycle page is inconsistent");
        }
    }
}
