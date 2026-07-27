package com.leanowtech.bloge.gateway.integration.mirror;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Bounded cursor page of hash-chained continuous-assessment lifecycle facts.
 *
 * @param schemaVersion exact page protocol version
 * @param projectionId exact continuous projection identity
 * @param afterOrdinal exclusive input cursor
 * @param predecessorFingerprint event fingerprint at the input cursor, blank only at zero
 * @param nextOrdinal last returned ordinal, or the input cursor for an empty page
 * @param hasMore whether another event exists after this bounded page
 * @param events oldest-first contiguous lifecycle suffix
 */
public record AuthoritativeOutcomeContinuousAssessmentLifecyclePage(
        String schemaVersion,
        String projectionId,
        long afterOrdinal,
        String predecessorFingerprint,
        long nextOrdinal,
        boolean hasMore,
        List<AuthoritativeOutcomeContinuousAssessmentLifecycleEvent>
                events
) {
    /** Current bounded lifecycle page protocol version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentLifecyclePage.v1";
    /** Maximum events returned in one page. */
    public static final int MAXIMUM_EVENTS = 1_000;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces exact cursor, projection, ordinal, and hash-chain continuity. */
    public AuthoritativeOutcomeContinuousAssessmentLifecyclePage {
        schemaVersion = normalized(schemaVersion);
        if (schemaVersion.isBlank()) {
            schemaVersion = SCHEMA_VERSION;
        }
        projectionId = normalized(projectionId);
        predecessorFingerprint = normalized(
                predecessorFingerprint);
        events = events == null
                ? List.of() : List.copyOf(events);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !IDENTIFIER.matcher(
                projectionId).matches()
                || afterOrdinal < 0
                || nextOrdinal < afterOrdinal
                || events.size() > MAXIMUM_EVENTS
                || (afterOrdinal == 0)
                != predecessorFingerprint.isBlank()
                || !predecessorFingerprint.isBlank()
                && !FINGERPRINT.matcher(
                predecessorFingerprint).matches()) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle page coordinates are invalid");
        }
        long cursor = afterOrdinal;
        String previous = predecessorFingerprint;
        for (AuthoritativeOutcomeContinuousAssessmentLifecycleEvent
                event : events) {
            Objects.requireNonNull(event, "event");
            if (!projectionId.equals(
                    event.projection().projectionId())
                    || event.eventOrdinal() != cursor + 1
                    || !event.previousEventFingerprint()
                    .equals(previous)) {
                throw new IllegalArgumentException(
                        "continuous assessment lifecycle page chain is invalid");
            }
            cursor = event.eventOrdinal();
            previous = event.eventFingerprint();
        }
        if (cursor != nextOrdinal
                || events.isEmpty()
                && nextOrdinal != afterOrdinal) {
            throw new IllegalArgumentException(
                    "continuous assessment lifecycle page next cursor is invalid");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
