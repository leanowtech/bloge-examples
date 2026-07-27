package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Independent verifier for one bounded continuous-assessment lifecycle page.
 *
 * <p>The verifier links no server or Spring class. It validates both strict Schemas, reuses the
 * independent projection state verifier, recomputes every event content address, requires
 * contiguous ordinals and exact hash-chain predecessors, and binds the page to a caller-owned
 * cursor checkpoint. It contains no business payload or worker identity.</p>
 */
public final class
AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier {
    /** Maximum canonical lifecycle event bytes admitted to hashing. */
    public static final int MAXIMUM_EVENT_BYTES =
            512 * 1024;

    /** Creates one stateless lifecycle verifier. */
    public AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier() {
    }

    /** Bounded lifecycle verification outcome. */
    public enum Outcome {
        /** Schema, projection, event address, cursor, and chain checks passed. */
        VERIFIED,
        /** At least one lifecycle closure check failed. */
        INVALID
    }

    /**
     * Payload-free continuation checkpoint and verification result.
     *
     * @param outcome bounded result class
     * @param reasonCode stable machine-readable reason
     * @param projectionId exact projection identity when available
     * @param nextOrdinal last independently verified event ordinal
     * @param eventFingerprint fingerprint at {@code nextOrdinal}, blank only at zero
     * @param projectionFingerprint resulting projection fingerprint, blank on empty first page
     * @param hasMore whether another producer page was declared
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String projectionId,
            long nextOrdinal,
            String eventFingerprint,
            String projectionFingerprint,
            boolean hasMore
    ) {
        /** Bounds every coordinate and rejects contradictory checkpoints. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(
                    reasonCode, 255);
            projectionId = bounded(
                    projectionId, 512);
            eventFingerprint = bounded(
                    eventFingerprint, 128);
            projectionFingerprint = bounded(
                    projectionFingerprint, 128);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || nextOrdinal < 0
                    || (nextOrdinal == 0)
                    != eventFingerprint.isBlank()) {
                throw new IllegalArgumentException(
                        "Continuous assessment lifecycle verification result is invalid");
            }
        }

        /**
         * Reports whether every independent lifecycle closure check passed.
         *
         * @return whether all independent checks passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one page against a caller-owned exclusive cursor checkpoint.
     *
     * @param page decoded lifecycle page payload
     * @param expectedAfterOrdinal exact requested exclusive ordinal
     * @param expectedPredecessorFingerprint fingerprint at the requested cursor, blank at zero
     * @return bounded continuation checkpoint
     */
    public VerificationResult verify(
            JsonNode page,
            long expectedAfterOrdinal,
            String expectedPredecessorFingerprint) {
        Coordinates coordinates =
                Coordinates.from(page);
        try {
            CapabilityMirrorSchemaValidator.require(
                    page,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_PAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_PAGE_SCHEMA_INVALID");
            String expectedPredecessor = normalized(
                    expectedPredecessorFingerprint);
            if (expectedAfterOrdinal < 0
                    || page.path("afterOrdinal").asLong(-1)
                    != expectedAfterOrdinal
                    || !text(
                    page,
                    "predecessorFingerprint",
                    true).equals(
                            expectedPredecessor)
                    || (expectedAfterOrdinal == 0)
                    != expectedPredecessor.isBlank()) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_CURSOR_INVALID");
            }
            String projectionId =
                    text(page, "projectionId", false);
            long cursor = expectedAfterOrdinal;
            String previous = expectedPredecessor;
            String projectionFingerprint = "";
            for (JsonNode event : page.path("events")) {
                CapabilityMirrorSchemaValidator.require(
                        event,
                        CapabilityMirrorProtocol
                                .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_EVENT_SCHEMA_RESOURCE,
                        "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_EVENT_SCHEMA_INVALID");
                long ordinal =
                        event.path("eventOrdinal")
                                .asLong(-1);
                JsonNode projection =
                        event.path("projection");
                if (ordinal != cursor + 1
                        || !projectionId.equals(
                        projection.path(
                                "projectionId")
                                .asText(""))
                        || !previous.equals(
                        text(
                                event,
                                "previousEventFingerprint",
                                true))
                        || !text(
                        event,
                        "occurredAt",
                        false).equals(
                                projection.path(
                                        "updatedAt")
                                        .asText(""))) {
                    fail(
                            "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_CHAIN_INVALID");
                }
                Instant occurredAt =
                        instant(event, "occurredAt");
                AuthoritativeOutcomeContinuousAssessmentVerifier
                        .requireProjection(
                                projection,
                                occurredAt);
                ObjectNode material =
                        ((ObjectNode) event)
                                .deepCopy();
                String claimed =
                        text(
                                event,
                                "eventFingerprint",
                                false);
                material.put(
                        "eventFingerprint", "");
                if (!EvidenceVerificationSupport
                        .sha256Bounded(
                                material,
                                MAXIMUM_EVENT_BYTES)
                        .equals(claimed)) {
                    fail(
                            "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_FINGERPRINT_INVALID");
                }
                requireTransitionShape(
                        event,
                        projection);
                cursor = ordinal;
                previous = claimed;
                projectionFingerprint =
                        projection.path(
                                "recordFingerprint")
                                .asText("");
            }
            if (page.path("nextOrdinal")
                    .asLong(-1) != cursor) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_NEXT_CURSOR_INVALID");
            }
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    projectionId,
                    cursor,
                    previous,
                    projectionFingerprint,
                    page.path("hasMore")
                            .asBoolean());
        } catch (VerificationFailure failure) {
            return invalid(
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return invalid(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_INVALID",
                    coordinates);
        }
    }

    private static void requireTransitionShape(
            JsonNode event,
            JsonNode projection) {
        String transition =
                text(
                        event,
                        "transition",
                        false);
        String actor =
                text(
                        event,
                        "actorFingerprint",
                        true);
        String status =
                projection.path("status")
                        .asText("");
        boolean valid = switch (transition) {
            case "REGISTERED" ->
                    actor.isBlank()
                            && "QUEUED".equals(status)
                            && projection.path(
                            "attemptCount")
                            .asLong(-1) == 0
                            && projection.path(
                            "leaseEpoch")
                            .asLong(-1) == 0;
            case "MIGRATED" -> true;
            case "CLAIMED" ->
                    "RUNNING".equals(status)
                            && !actor.isBlank()
                            && actor.equals(
                            projection.path(
                                    "leaseOwnerFingerprint")
                                    .asText(""))
                            && projection.path(
                            "attemptCount")
                            .asLong(-1) >= 1
                            && projection.path(
                            "leaseEpoch")
                            .asLong(-1) >= 1;
            case "ASSESSMENT_PUBLISHED",
                    "SOURCE_UNCHANGED" ->
                    "QUEUED".equals(status)
                            && !actor.isBlank()
                            && projection.hasNonNull(
                            "lastAssessmentRef")
                            && projection.path(
                            "failureCode")
                            .asText("")
                            .isBlank();
            case "RETRY_SCHEDULED",
                    "LEASE_EXPIRED" ->
                    "RETRY_WAIT".equals(status)
                            && !actor.isBlank()
                            && !projection.path(
                            "failureCode")
                            .asText("")
                            .isBlank();
            case "QUARANTINED" ->
                    "QUARANTINED".equals(status)
                            && !actor.isBlank()
                            && !projection.path(
                            "failureCode")
                            .asText("")
                            .isBlank()
                            && projection.hasNonNull(
                            "terminalAt");
            case "REMEDIATION_ACCEPTED" ->
                    "QUEUED".equals(status)
                            && !actor.isBlank()
                            && projection.path(
                            "consecutiveFailures")
                            .asInt(-1) == 0
                            && projection.path(
                            "failureCode")
                            .asText("")
                            .isBlank()
                            && projection.path(
                            "terminalAt")
                            .isNull()
                            && projection.path(
                            "nextEligibleAt")
                            .asText("")
                            .equals(
                                    projection.path(
                                            "updatedAt")
                                            .asText(""));
            default -> false;
        };
        if (!valid) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_TRANSITION_INVALID");
        }
    }

    private static VerificationResult invalid(
            String reasonCode,
            Coordinates coordinates) {
        long ordinal = Math.max(
                0, coordinates.nextOrdinal);
        String fingerprint = ordinal == 0
                ? "" : coordinates.eventFingerprint;
        if (ordinal > 0
                && fingerprint.isBlank()) {
            ordinal = 0;
        }
        return new VerificationResult(
                Outcome.INVALID,
                reasonCode,
                coordinates.projectionId,
                ordinal,
                fingerprint,
                "",
                coordinates.hasMore);
    }

    private static String text(
            JsonNode value,
            String field,
            boolean allowBlank) {
        JsonNode candidate =
                value == null
                        ? null : value.get(field);
        if (candidate == null
                || !candidate.isTextual()
                || !allowBlank
                && candidate.textValue()
                .isBlank()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_FIELD_INVALID");
        }
        return candidate.textValue();
    }

    private static Instant instant(
            JsonNode value,
            String field) {
        try {
            String encoded = text(
                    value, field, false);
            Instant exact = Instant.parse(encoded);
            if (!exact.toString().equals(encoded)) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_TIME_INVALID");
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_TIME_INVALID");
            throw new IllegalStateException(
                    "unreachable");
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static String bounded(
            String value,
            int maximum) {
        String exact = normalized(value);
        return exact.length() <= maximum
                ? exact
                : exact.substring(0, maximum);
    }

    private static void fail(String reasonCode) {
        throw new VerificationFailure(
                reasonCode);
    }

    private record Coordinates(
            String projectionId,
            long nextOrdinal,
            String eventFingerprint,
            boolean hasMore
    ) {
        private static Coordinates from(
                JsonNode page) {
            if (page == null
                    || !page.isObject()) {
                return new Coordinates(
                        "", 0, "", false);
            }
            JsonNode events =
                    page.path("events");
            JsonNode last = events.isArray()
                    && !events.isEmpty()
                    ? events.get(
                    events.size() - 1)
                    : null;
            return new Coordinates(
                    page.path(
                            "projectionId")
                            .asText(""),
                    page.path("nextOrdinal")
                            .asLong(0),
                    last == null
                            ? page.path(
                            "predecessorFingerprint")
                            .asText("")
                            : last.path(
                            "eventFingerprint")
                            .asText(""),
                    page.path("hasMore")
                            .asBoolean(false));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            super(null, null, false, false);
            this.reasonCode = reasonCode;
        }
    }
}
