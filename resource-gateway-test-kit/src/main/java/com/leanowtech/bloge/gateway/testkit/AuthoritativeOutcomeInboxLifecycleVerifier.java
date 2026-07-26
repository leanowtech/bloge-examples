package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Independent verifier for one authoritative outcome inbox head and bounded lifecycle page.
 *
 * <p>The verifier links no Resource Gateway server class. It applies packaged strict Schemas,
 * recomputes the mutable head and every append-only event content address, verifies cursor and
 * predecessor continuity, closes observation revision evolution, and reconciles a complete page
 * with the current durable head.</p>
 */
public final class AuthoritativeOutcomeInboxLifecycleVerifier {
    /** Maximum canonical current-head bytes admitted to verification. */
    public static final int MAXIMUM_ENTRY_BYTES = 256 * 1024;
    /** Maximum canonical lifecycle page bytes admitted to verification. */
    public static final int MAXIMUM_PAGE_BYTES = 4 * 1024 * 1024;
    /** Maximum canonical event bytes admitted to verification. */
    public static final int MAXIMUM_EVENT_BYTES = 256 * 1024;

    /** Creates a dependency-light verifier backed by packaged strict Schemas. */
    public AuthoritativeOutcomeInboxLifecycleVerifier() {
    }

    /** Closed lifecycle verification outcomes. */
    public enum Outcome {
        /** The page proves the complete first-admission-to-current-head lifecycle. */
        VERIFIED_COMPLETE,
        /** The bounded page is valid but is only a suffix or prefix. */
        VERIFIED_PAGE,
        /** The page or current head is malformed, inconsistent, or corrupt. */
        INVALID
    }

    /**
     * Payload-free lifecycle verification result.
     *
     * @param outcome bounded result
     * @param reasonCode stable machine-readable reason
     * @param observationId observation identity when structurally available
     * @param currentObservationFingerprint current head address when structurally available
     * @param nextOrdinal last page cursor when structurally available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String observationId,
            String currentObservationFingerprint,
            long nextOrdinal
    ) {
        /** Normalizes bounded log-safe coordinates. */
        public VerificationResult {
            outcome = Objects.requireNonNull(outcome, "outcome");
            reasonCode = bounded(reasonCode, 255);
            observationId = bounded(observationId, 512);
            currentObservationFingerprint = bounded(
                    currentObservationFingerprint, 128);
            nextOrdinal = Math.max(0, nextOrdinal);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Outcome lifecycle verification result is invalid");
            }
        }

        /**
         * Reports whether the supplied page and head pass bounded structural and content checks.
         *
         * @return whether this page is internally and cryptographically consistent
         */
        public boolean verified() {
            return outcome != Outcome.INVALID;
        }

        /**
         * Reports whether the page proves the full lifecycle rather than a valid bounded segment.
         *
         * @return whether this page closes initial admission through the current head
         */
        public boolean complete() {
            return outcome == Outcome.VERIFIED_COMPLETE;
        }
    }

    /**
     * Verifies one current durable head and one lifecycle cursor page.
     *
     * @param entry decoded current head
     * @param page decoded lifecycle page
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode entry,
            JsonNode page) {
        Coordinates coordinates = Coordinates.from(entry, page);
        try {
            CapabilityMirrorSchemaValidator.require(
                    entry,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_INBOX_ENTRY_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_INBOX_ENTRY_SCHEMA_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    page,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_PAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_LIFECYCLE_PAGE_SCHEMA_INVALID");
            EvidenceVerificationSupport.sha256Bounded(
                    page, MAXIMUM_PAGE_BYTES);
            verifyEntry(entry);
            Outcome outcome = verifyPage(entry, page);
            return result(
                    outcome,
                    outcome == Outcome.VERIFIED_COMPLETE
                            ? "VERIFIED_COMPLETE"
                            : "VERIFIED_PAGE",
                    coordinates);
        } catch (VerificationFailure failure) {
            return result(
                    Outcome.INVALID,
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "OUTCOME_LIFECYCLE_CLOSURE_INVALID",
                    coordinates);
        }
    }

    private static void verifyEntry(JsonNode entry) {
        ObjectNode material = object(entry);
        material.put("recordFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_ENTRY_BYTES).equals(
                text(entry, "recordFingerprint"))) {
            fail("OUTCOME_INBOX_ENTRY_FINGERPRINT_INVALID");
        }
    }

    private static Outcome verifyPage(
            JsonNode entry,
            JsonNode page) {
        String observationId = text(entry, "observationId");
        JsonNode scope = entry.path("scope");
        if (!observationId.equals(
                text(page, "observationId"))) {
            fail("OUTCOME_LIFECYCLE_OBSERVATION_MISMATCH");
        }
        long after = page.path("afterOrdinal").asLong(-1);
        long next = page.path("nextOrdinal").asLong(-1);
        boolean hasMore = page.path("hasMore").asBoolean();
        JsonNode events = page.path("events");
        if (after < 0
                || next < after
                || !events.isArray()
                || hasMore && events.isEmpty()) {
            fail("OUTCOME_LIFECYCLE_CURSOR_INVALID");
        }
        long cursor = after;
        JsonNode previous = null;
        Instant previousTime = null;
        for (JsonNode event : events) {
            CapabilityMirrorSchemaValidator.require(
                    event,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_INBOX_LIFECYCLE_EVENT_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_LIFECYCLE_EVENT_SCHEMA_INVALID");
            verifyEventFingerprint(event);
            long ordinal = event.path("eventOrdinal").asLong(-1);
            Instant occurredAt = instant(
                    event.path("occurredAt"));
            if (ordinal != cursor + 1
                    || !observationId.equals(
                    text(event, "observationId"))
                    || !scope.equals(event.path("scope"))
                    || event.path("observationRevision")
                    .asLong(Long.MAX_VALUE)
                    > entry.path("currentRevision").asLong(-1)
                    || previousTime != null
                    && occurredAt.isBefore(previousTime)) {
                fail("OUTCOME_LIFECYCLE_EVENT_CLOSURE_INVALID");
            }
            if (ordinal == 1) {
                if (!"OBSERVATION_APPENDED".equals(
                        text(event, "transition"))
                        || !text(
                        event,
                        "previousEventFingerprint").isBlank()
                        || !text(
                        event,
                        "predecessorObservationFingerprint").isBlank()) {
                    fail("OUTCOME_LIFECYCLE_ADMISSION_INVALID");
                }
            } else if (previous == null) {
                if (text(
                        event,
                        "previousEventFingerprint").isBlank()
                        || text(
                        event,
                        "predecessorObservationFingerprint").isBlank()) {
                    fail("OUTCOME_LIFECYCLE_PREDECESSOR_INVALID");
                }
            } else {
                verifySuccessor(previous, event);
            }
            previous = event;
            previousTime = occurredAt;
            cursor = ordinal;
        }
        if (cursor != next) {
            fail("OUTCOME_LIFECYCLE_CURSOR_INVALID");
        }
        boolean startsAtAdmission = after == 0
                && !events.isEmpty()
                && events.get(0).path("eventOrdinal").asLong() == 1;
        if (after == 0 && !startsAtAdmission) {
            fail("OUTCOME_LIFECYCLE_ADMISSION_MISSING");
        }
        if (startsAtAdmission && !hasMore) {
            verifyHead(entry, previous);
            return Outcome.VERIFIED_COMPLETE;
        }
        return Outcome.VERIFIED_PAGE;
    }

    private static void verifyEventFingerprint(JsonNode event) {
        ObjectNode material = object(event);
        material.put("eventFingerprint", "");
        if (!EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_EVENT_BYTES).equals(
                text(event, "eventFingerprint"))) {
            fail("OUTCOME_LIFECYCLE_EVENT_FINGERPRINT_INVALID");
        }
    }

    private static void verifySuccessor(
            JsonNode previous,
            JsonNode current) {
        if (!text(previous, "eventFingerprint").equals(
                text(current, "previousEventFingerprint"))
                || !text(
                previous,
                "observationFingerprint").equals(
                text(
                        current,
                        "predecessorObservationFingerprint"))) {
            fail("OUTCOME_LIFECYCLE_PREDECESSOR_INVALID");
        }
        long beforeRevision =
                previous.path("observationRevision").asLong(-1);
        long afterRevision =
                current.path("observationRevision").asLong(-1);
        if (afterRevision < beforeRevision
                || afterRevision > beforeRevision + 1
                || afterRevision == beforeRevision
                && !text(
                previous,
                "observationFingerprint").equals(
                text(current, "observationFingerprint"))
                || afterRevision == beforeRevision + 1
                && !("OBSERVATION_APPENDED".equals(
                text(current, "transition"))
                || "SUCCESSOR_APPENDED".equals(
                text(current, "transition")))) {
            fail("OUTCOME_LIFECYCLE_OBSERVATION_LINEAGE_INVALID");
        }
    }

    private static void verifyHead(
            JsonNode entry,
            JsonNode event) {
        if (event == null
                || entry.path("currentRevision").asLong(-1)
                != event.path("observationRevision").asLong(-2)
                || !text(
                entry,
                "currentObservationFingerprint").equals(
                text(event, "observationFingerprint"))
                || !text(entry, "reconciliation").equals(
                text(event, "reconciliation"))
                || !text(entry, "status").equals(
                text(event, "status"))
                || entry.path("attemptCount").asLong(-1)
                != event.path("attemptCount").asLong(-2)
                || entry.path("consecutiveFailures").asInt(-1)
                != event.path("consecutiveFailures").asInt(-2)
                || entry.path("leaseEpoch").asLong(-1)
                != event.path("leaseEpoch").asLong(-2)
                || !text(entry, "failureCode").equals(
                text(event, "failureCode"))
                || !instant(entry.path("updatedAt")).equals(
                instant(event.path("occurredAt")))) {
            fail("OUTCOME_LIFECYCLE_HEAD_MISMATCH");
        }
    }

    private static ObjectNode object(JsonNode value) {
        if (!(value instanceof ObjectNode)) {
            fail("OUTCOME_LIFECYCLE_OBJECT_REQUIRED");
        }
        return ((ObjectNode) value).deepCopy();
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText(""));
        } catch (DateTimeParseException invalid) {
            fail("OUTCOME_LIFECYCLE_TIME_INVALID");
            throw invalid;
        }
    }

    private static String text(
            JsonNode value,
            String field) {
        JsonNode child = value.path(field);
        if (!child.isTextual()) {
            fail("OUTCOME_LIFECYCLE_TEXT_REQUIRED");
        }
        return child.textValue();
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.observationId,
                coordinates.currentObservationFingerprint,
                coordinates.nextOrdinal);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(reasonCode);
    }

    private static String bounded(
            String value,
            int maximum) {
        String normalized = value == null ? "" : value.trim();
        return normalized.length() <= maximum
                ? normalized
                : normalized.substring(0, maximum);
    }

    private record Coordinates(
            String observationId,
            String currentObservationFingerprint,
            long nextOrdinal
    ) {
        private static Coordinates from(
                JsonNode entry,
                JsonNode page) {
            return new Coordinates(
                    safeText(entry, "observationId"),
                    safeText(
                            entry,
                            "currentObservationFingerprint"),
                    page == null
                            ? 0
                            : Math.max(
                            0,
                            page.path("nextOrdinal")
                                    .asLong(0)));
        }

        private static String safeText(
                JsonNode value,
                String field) {
            if (value == null
                    || !value.path(field).isTextual()) {
                return "";
            }
            return bounded(
                    value.path(field).textValue(),
                    512);
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }
}
