package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;

/**
 * Bounded cursor page of payload-free durable Shadow lifecycle facts.
 *
 * @param schemaVersion exact page protocol version
 * @param jobId exact durable job identity
 * @param afterSequence exclusive input cursor
 * @param nextSequence last returned sequence, or the input cursor for an empty page
 * @param hasMore whether another bounded page exists
 * @param events oldest-to-newest exact-job lifecycle suffix
 */
public record ReadOnlyShadowJobLifecyclePage(
        String schemaVersion,
        String jobId,
        long afterSequence,
        long nextSequence,
        boolean hasMore,
        List<ReadOnlyShadowJobLifecycleEvent> events
) {
    /** Current lifecycle page protocol. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.readOnlyShadowJobLifecyclePage.v1";

    /** Enforces cursor monotonicity and exact-job append order. */
    public ReadOnlyShadowJobLifecyclePage {
        if (!SCHEMA_VERSION.equals(
                schemaVersion == null
                        ? "" : schemaVersion.trim())) {
            throw new IllegalArgumentException(
                    "unsupported read-only Shadow lifecycle page schemaVersion");
        }
        jobId = required(jobId);
        if (afterSequence < 0
                || nextSequence < afterSequence) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle page cursor is invalid");
        }
        events = events == null
                ? List.of() : List.copyOf(events);
        long cursor = afterSequence;
        for (ReadOnlyShadowJobLifecycleEvent event
                : events) {
            Objects.requireNonNull(event, "event");
            if (!jobId.equals(event.jobId())
                    || event.sequence() <= cursor) {
                throw new IllegalArgumentException(
                        "read-only Shadow lifecycle page order is invalid");
            }
            cursor = event.sequence();
        }
        if (cursor != nextSequence
                || events.isEmpty()
                && nextSequence != afterSequence) {
            throw new IllegalArgumentException(
                    "read-only Shadow lifecycle page next cursor is invalid");
        }
    }

    private static String required(String value) {
        String normalized = value == null
                ? "" : value.trim();
        if (normalized.isBlank()
                || normalized.length() > 512) {
            throw new IllegalArgumentException(
                    "jobId is invalid");
        }
        return normalized;
    }
}
