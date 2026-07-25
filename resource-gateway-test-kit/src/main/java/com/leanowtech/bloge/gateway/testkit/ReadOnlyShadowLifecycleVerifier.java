package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.Objects;

/**
 * Independent verifier for one bounded durable Shadow lifecycle page.
 *
 * <p>The verifier does not link the Resource Gateway server model. It applies the packaged strict
 * Schemas, recomputes the current job record fingerprint, checks exact scope/request/job
 * coordinates, validates append order and legal local state transitions, and reconciles an
 * untruncated suffix with the current job projection. A result is complete only when the page
 * contains the original {@code ADMITTED} fact and reaches the current job head.</p>
 */
public final class ReadOnlyShadowLifecycleVerifier {
    /** Maximum canonical current-job bytes admitted to verification. */
    public static final int MAXIMUM_JOB_BYTES =
            256 * 1024;
    /** Maximum canonical lifecycle-page bytes admitted to verification. */
    public static final int MAXIMUM_PAGE_BYTES =
            2 * 1024 * 1024;

    /** Creates a dependency-light verifier backed by the schemas packaged in the Test Kit. */
    public ReadOnlyShadowLifecycleVerifier() {
    }

    /** Closed lifecycle verification outcomes. */
    public enum Outcome {
        /** The page proves the complete admitted-to-current lifecycle. */
        VERIFIED_COMPLETE,
        /** The bounded page is valid but does not contain the complete lifecycle closure. */
        VERIFIED_PAGE,
        /** The page or current job is malformed, inconsistent, or corrupt. */
        INVALID
    }

    /**
     * Payload-free lifecycle verification result.
     *
     * @param outcome bounded outcome
     * @param reasonCode stable machine-readable reason
     * @param jobId job identity when structurally available
     * @param requestFingerprint immutable request fingerprint when structurally available
     * @param nextSequence last page cursor when structurally available
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String requestFingerprint,
            long nextSequence
    ) {
        /** Normalizes bounded coordinates and rejects unsafe free-form reasons. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(reasonCode, 255);
            jobId = bounded(jobId, 512);
            requestFingerprint = bounded(
                    requestFingerprint, 128);
            nextSequence = Math.max(
                    0L, nextSequence);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Shadow lifecycle verification result is invalid");
            }
        }

        /**
         * Reports whether the supplied bounded page is internally valid.
         *
         * @return {@code true} for complete and partial verified pages
         */
        public boolean verified() {
            return outcome != Outcome.INVALID;
        }

        /**
         * Reports whether the page proves the complete admitted-to-current lifecycle.
         *
         * @return {@code true} only for {@link Outcome#VERIFIED_COMPLETE}
         */
        public boolean complete() {
            return outcome
                    == Outcome.VERIFIED_COMPLETE;
        }
    }

    /**
     * Verifies a current job projection and one lifecycle cursor page.
     *
     * @param job decoded current public job projection
     * @param page decoded lifecycle page
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode job,
            JsonNode page) {
        Coordinates coordinates =
                Coordinates.from(job, page);
        try {
            CapabilityMirrorSchemaValidator.require(
                    job,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_JOB_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_JOB_SCHEMA_INVALID");
            CapabilityMirrorSchemaValidator.require(
                    page,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_JOB_LIFECYCLE_PAGE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_LIFECYCLE_PAGE_SCHEMA_INVALID");
            EvidenceVerificationSupport.sha256Bounded(
                    page, MAXIMUM_PAGE_BYTES);
            verifyCurrentJob(job);
            Outcome outcome =
                    verifyPage(job, page);
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
                    "SHADOW_LIFECYCLE_CLOSURE_INVALID",
                    coordinates);
        }
    }

    private static void verifyCurrentJob(
            JsonNode job) {
        ObjectNode material =
                ((ObjectNode) job).deepCopy();
        material.put("recordFingerprint", "");
        if (!EvidenceVerificationSupport
                .sha256Bounded(
                        material,
                        MAXIMUM_JOB_BYTES)
                .equals(text(
                        job, "recordFingerprint"))) {
            fail("SHADOW_LIFECYCLE_JOB_FINGERPRINT_INVALID");
        }
    }

    private static Outcome verifyPage(
            JsonNode job,
            JsonNode page) {
        String jobId = text(job, "jobId");
        String requestFingerprint =
                text(job, "requestFingerprint");
        JsonNode scope = job.path("scope");
        if (!jobId.equals(text(page, "jobId"))) {
            fail("SHADOW_LIFECYCLE_JOB_MISMATCH");
        }
        long afterSequence =
                page.path("afterSequence")
                        .asLong(-1);
        long nextSequence =
                page.path("nextSequence")
                        .asLong(-1);
        JsonNode events = page.path("events");
        if (afterSequence < 0
                || nextSequence < afterSequence
                || !events.isArray()
                || page.path("hasMore").asBoolean()
                && events.isEmpty()) {
            fail("SHADOW_LIFECYCLE_CURSOR_INVALID");
        }
        long cursor = afterSequence;
        JsonNode previous = null;
        Instant createdAt =
                instant(job.path("createdAt"));
        Instant updatedAt =
                instant(job.path("updatedAt"));
        Instant deadlineAt =
                instant(job.path("deadlineAt"));
        for (JsonNode event : events) {
            CapabilityMirrorSchemaValidator.require(
                    event,
                    CapabilityMirrorProtocol
                            .READ_ONLY_SHADOW_JOB_LIFECYCLE_EVENT_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SHADOW_LIFECYCLE_EVENT_SCHEMA_INVALID");
            long sequence =
                    event.path("sequence")
                            .asLong(-1);
            Instant occurredAt =
                    instant(event.path("occurredAt"));
            if (sequence <= cursor
                    || !jobId.equals(
                    text(event, "jobId"))
                    || !requestFingerprint.equals(
                    text(
                            event,
                            "requestFingerprint"))
                    || !scope.equals(
                    event.path("scope"))
                    || occurredAt.isBefore(createdAt)
                    || occurredAt.isAfter(updatedAt)
                    || instant(
                    event.path("nextEligibleAt"))
                    .isAfter(deadlineAt)
                    || instant(
                    event.path("leaseExpiresAt"))
                    .isAfter(deadlineAt)) {
                fail("SHADOW_LIFECYCLE_EVENT_CLOSURE_INVALID");
            }
            if (previous != null) {
                verifyTransition(
                        previous, event);
            }
            previous = event;
            cursor = sequence;
        }
        if (cursor != nextSequence) {
            fail("SHADOW_LIFECYCLE_CURSOR_INVALID");
        }
        if (afterSequence == 0
                && (previous == null
                || !"ADMITTED".equals(
                text(events.get(0), "transition")))) {
            fail("SHADOW_LIFECYCLE_ADMISSION_MISSING");
        }
        if (!events.isEmpty()
                && "ADMITTED".equals(
                text(events.get(0), "transition"))) {
            if (!instant(
                    events.get(0).path("occurredAt"))
                    .equals(createdAt)) {
                fail("SHADOW_LIFECYCLE_ADMISSION_TIME_INVALID");
            }
        }
        boolean hasMore =
                page.path("hasMore").asBoolean();
        if (!hasMore && previous != null) {
            verifyHead(job, previous);
        }
        return !hasMore
                && !events.isEmpty()
                && "ADMITTED".equals(
                text(events.get(0), "transition"))
                ? Outcome.VERIFIED_COMPLETE
                : Outcome.VERIFIED_PAGE;
    }

    private static void verifyTransition(
            JsonNode before,
            JsonNode after) {
        Instant beforeAt =
                instant(before.path("occurredAt"));
        Instant afterAt =
                instant(after.path("occurredAt"));
        if (afterAt.isBefore(beforeAt)
                || terminal(text(before, "status"))
                || "ADMITTED".equals(
                text(after, "transition"))) {
            fail("SHADOW_LIFECYCLE_TRANSITION_INVALID");
        }
        String transition =
                text(after, "transition");
        String beforeStatus =
                text(before, "status");
        int beforeAttempt =
                before.path("attemptCount")
                        .asInt(-1);
        int afterAttempt =
                after.path("attemptCount")
                        .asInt(-1);
        long beforeEpoch =
                before.path("leaseEpoch")
                        .asLong(-1);
        long afterEpoch =
                after.path("leaseEpoch")
                        .asLong(-1);
        boolean sameAttempt =
                beforeAttempt == afterAttempt;
        boolean sameEpoch =
                beforeEpoch == afterEpoch;
        boolean sameOwner =
                text(before, "ownerFingerprint")
                        .equals(text(
                                after,
                                "ownerFingerprint"));
        boolean valid = switch (transition) {
            case "CLAIMED" ->
                    "QUEUED".equals(beforeStatus)
                            && afterAttempt
                            == beforeAttempt + 1
                            && afterEpoch
                            == beforeEpoch + 1;
            case "TAKEN_OVER" ->
                    "RUNNING".equals(beforeStatus)
                            && afterAttempt
                            == beforeAttempt + 1
                            && afterEpoch
                            == beforeEpoch + 1;
            case "LEASE_RENEWED" ->
                    "RUNNING".equals(beforeStatus)
                            && sameAttempt
                            && sameEpoch
                            && sameOwner
                            && !instant(
                            after.path("leaseExpiresAt"))
                            .isBefore(instant(
                                    before.path(
                                            "leaseExpiresAt")));
            case "RETRY_SCHEDULED" ->
                    "RUNNING".equals(beforeStatus)
                            && sameAttempt
                            && sameEpoch
                            && sameOwner;
            case "SUCCEEDED" ->
                    "RUNNING".equals(beforeStatus)
                            && sameAttempt
                            && sameEpoch
                            && sameOwner;
            case "FAILED" ->
                    ("QUEUED".equals(beforeStatus)
                            || "RUNNING".equals(
                            beforeStatus))
                            && sameAttempt
                            && sameEpoch
                            && (!"RUNNING".equals(
                            beforeStatus)
                            || sameOwner);
            case "EXPIRED" ->
                    ("QUEUED".equals(beforeStatus)
                            || "RUNNING".equals(
                            beforeStatus))
                            && sameAttempt
                            && sameEpoch
                            && text(
                            after,
                            "ownerFingerprint")
                            .isBlank();
            default -> false;
        };
        if (!valid) {
            fail("SHADOW_LIFECYCLE_TRANSITION_INVALID");
        }
    }

    private static void verifyHead(
            JsonNode job,
            JsonNode event) {
        String comparisonFingerprint =
                job.path("comparisonRef").isNull()
                        ? ""
                        : text(
                                job.path("comparisonRef"),
                                "fingerprint");
        if (!text(job, "status").equals(
                text(event, "status"))
                || job.path("attemptCount").asInt()
                != event.path("attemptCount").asInt()
                || job.path("leaseEpoch").asLong()
                != event.path("leaseEpoch").asLong()
                || !job.path("nextEligibleAt").equals(
                event.path("nextEligibleAt"))
                || !job.path("leaseExpiresAt").equals(
                event.path("leaseExpiresAt"))
                || !comparisonFingerprint.equals(
                text(
                        event,
                        "comparisonFingerprint"))
                || !text(job, "failureCode").equals(
                text(event, "failureCode"))
                || !text(
                job,
                "recordFingerprint").equals(
                text(
                        event,
                        "recordFingerprint"))
                || !job.path("updatedAt").equals(
                event.path("occurredAt"))) {
            fail("SHADOW_LIFECYCLE_HEAD_MISMATCH");
        }
    }

    private static boolean terminal(
            String status) {
        return "SUCCEEDED".equals(status)
                || "FAILED".equals(status)
                || "EXPIRED".equals(status);
    }

    private static Instant instant(
            JsonNode value) {
        try {
            return Instant.parse(
                    value.asText());
        } catch (RuntimeException invalid) {
            fail("SHADOW_LIFECYCLE_TIME_INVALID");
            throw new IllegalStateException();
        }
    }

    private static String text(
            JsonNode value,
            String field) {
        return value == null
                ? "" : value.path(field)
                .asText("");
    }

    private static VerificationResult result(
            Outcome outcome,
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                outcome,
                reasonCode,
                coordinates.jobId,
                coordinates.requestFingerprint,
                coordinates.nextSequence);
    }

    private static void fail(
            String reasonCode) {
        throw new VerificationFailure(
                reasonCode);
    }

    private static String bounded(
            String value,
            int maximumLength) {
        String normalized = value == null
                ? "" : value.trim();
        return normalized.length()
                <= maximumLength
                ? normalized
                : normalized.substring(
                        0, maximumLength);
    }

    private record Coordinates(
            String jobId,
            String requestFingerprint,
            long nextSequence
    ) {
        private static Coordinates from(
                JsonNode job,
                JsonNode page) {
            return new Coordinates(
                    text(job, "jobId"),
                    text(job, "requestFingerprint"),
                    page == null
                            ? 0L
                            : page.path("nextSequence")
                            .asLong(0L));
        }
    }

    private static final class VerificationFailure
            extends RuntimeException {
        private final String reasonCode;

        private VerificationFailure(
                String reasonCode) {
            super(reasonCode);
            this.reasonCode = reasonCode;
        }
    }
}
