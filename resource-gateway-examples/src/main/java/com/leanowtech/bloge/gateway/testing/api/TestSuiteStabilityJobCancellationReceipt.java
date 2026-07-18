package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Database-timed semantic result of the first accepted cancellation command for one job.
 *
 * <p>The receipt contains no suite metadata, fixture, graph input, node output, or credential. It
 * exists long enough to construct a transaction-bound security event and is never emitted by the
 * public job API.</p>
 *
 * @param command exact authenticated cancellation command
 * @param previousStatus status locked before the command
 * @param resultingStatus status committed by the command
 * @param outcome closed semantic result
 * @param occurredAt database observation time used by the mutation
 */
public record TestSuiteStabilityJobCancellationReceipt(
        TestSuiteStabilityJobCancellationCommand command,
        TestSuiteStabilityJobRecord.Status previousStatus,
        TestSuiteStabilityJobRecord.Status resultingStatus,
        Outcome outcome,
        Instant occurredAt) {

    /** Stable security-event type for cancellation semantic facts. */
    public static final String EVENT_TYPE = "SUITE_STABILITY_JOB_CANCELLATION";
    private static final String SCHEMA_VERSION =
            "bloge.testSuiteStabilityJobCancellationAudit.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Closed cancellation outcomes used by audit consumers. */
    public enum Outcome {
        /** A queued job became terminal before any worker claim. */
        CANCELLED_BEFORE_START,
        /** A live worker will observe the cooperative cancellation fence. */
        CANCELLATION_REQUESTED,
        /** Signed publication already linearized and cancellation cannot win. */
        TOO_LATE_TO_CANCEL,
        /** The job was already terminal when the first cancellation command arrived. */
        ALREADY_TERMINAL,
        /** Parent execution had already published the signed terminal winner. */
        PARENT_ALREADY_COMPLETED
    }

    /** Validates the complete status transition truth table. */
    public TestSuiteStabilityJobCancellationReceipt {
        command = Objects.requireNonNull(command, "command");
        previousStatus = Objects.requireNonNull(previousStatus, "previousStatus");
        resultingStatus = Objects.requireNonNull(resultingStatus, "resultingStatus");
        outcome = Objects.requireNonNull(outcome, "outcome");
        occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        boolean valid = switch (outcome) {
            case CANCELLED_BEFORE_START -> previousStatus
                    == TestSuiteStabilityJobRecord.Status.QUEUED
                    && resultingStatus == TestSuiteStabilityJobRecord.Status.CANCELLED;
            case CANCELLATION_REQUESTED -> previousStatus
                    == TestSuiteStabilityJobRecord.Status.RUNNING
                    && resultingStatus == TestSuiteStabilityJobRecord.Status.CANCEL_REQUESTED;
            case TOO_LATE_TO_CANCEL -> previousStatus
                    == TestSuiteStabilityJobRecord.Status.COMMITTING
                    && resultingStatus == previousStatus;
            case ALREADY_TERMINAL -> previousStatus.terminal()
                    && resultingStatus == previousStatus;
            case PARENT_ALREADY_COMPLETED -> !previousStatus.terminal()
                    && previousStatus != TestSuiteStabilityJobRecord.Status.COMMITTING
                    && resultingStatus == TestSuiteStabilityJobRecord.Status.SUCCEEDED;
        };
        if (!valid) {
            throw new IllegalArgumentException(
                    "Invalid suite-stability cancellation receipt transition");
        }
    }

    /**
     * Builds the payload-free semantic event that must commit with the queue mutation.
     *
     * @param objectMapper canonical fingerprint mapper
     * @return bounded security event attributed to the current cancellation actor
     */
    public TestSecurityEvent toSecurityEvent(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        TestSuiteStabilityJobPrincipal actor = command.actor();
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", SCHEMA_VERSION);
        facts.put("jobId", command.jobId());
        facts.put("clientRequestId", command.clientRequestId());
        facts.put("commandFingerprint", command.commandFingerprint());
        facts.put("organizationId", actor.organizationId());
        facts.put("projectId", actor.projectId());
        facts.put("actorType", actor.actorType());
        facts.put("delegatedBy", actor.delegatedBy());
        facts.put("delegationGrantId", actor.delegationGrantId());
        facts.put("purpose", actor.purpose());
        facts.put("clearance", actor.clearance());
        facts.put("groupCount", actor.groups().size());
        facts.put("groupFingerprint", ProtocolFingerprint.of(
                objectMapper, actor.groups().stream().sorted().toList()));
        facts.put("previousStatus", previousStatus.name());
        facts.put("resultingStatus", resultingStatus.name());
        facts.put("cancellationOutcome", outcome.name());
        TestSecurityEvent event = new TestSecurityEvent(
                0, occurredAt, actor.correlationId(), command.tenantId(),
                command.environmentId(), actor.actorId(),
                EVENT_TYPE, "COMMITTED", reasonCode(), facts);
        facts.put("semanticFingerprint", fingerprint(objectMapper, event, facts));
        return new TestSecurityEvent(
                event.sequence(), event.occurredAt(), event.correlationId(), event.tenantId(),
                event.environmentId(), event.actorId(), event.eventType(), event.outcome(),
                event.reasonCode(), facts);
    }

    /**
     * Verifies the complete top-level identity and payload-free fact projection of one event.
     *
     * @param objectMapper canonical fingerprint mapper
     * @param event cancellation event read from the append-only store
     * @return the same verified event
     * @throws IllegalStateException when any semantic field is absent or has been changed
     */
    public static TestSecurityEvent verifySecurityEvent(
            ObjectMapper objectMapper, TestSecurityEvent event) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(event, "event");
        Map<String, Object> facts = new LinkedHashMap<>(event.facts());
        String retained = normalized(facts.remove("semanticFingerprint"));
        if (!EVENT_TYPE.equals(event.eventType())
                || !SCHEMA_VERSION.equals(facts.get("schemaVersion"))
                || !FINGERPRINT.matcher(retained).matches()
                || !retained.equals(fingerprint(objectMapper, event, facts))) {
            throw new IllegalStateException(
                    "Suite-stability cancellation security event failed integrity verification");
        }
        return event;
    }

    private static String fingerprint(
            ObjectMapper objectMapper,
            TestSecurityEvent event,
            Map<String, Object> factsWithoutFingerprint) {
        Map<String, Object> material = new LinkedHashMap<>();
        material.put("schemaVersion", SCHEMA_VERSION);
        material.put("occurredAt", event.occurredAt());
        material.put("correlationId", event.correlationId());
        material.put("tenantId", event.tenantId());
        material.put("environmentId", event.environmentId());
        material.put("actorId", event.actorId());
        material.put("eventType", event.eventType());
        material.put("eventOutcome", event.outcome());
        material.put("reasonCode", event.reasonCode());
        material.put("facts", factsWithoutFingerprint);
        return ProtocolFingerprint.of(objectMapper, material);
    }

    private String reasonCode() {
        return switch (outcome) {
            case CANCELLED_BEFORE_START, CANCELLATION_REQUESTED ->
                    "RG.TEST.STABILITY_JOB_CANCELLATION_APPLIED";
            case TOO_LATE_TO_CANCEL -> "RG.TEST.STABILITY_JOB_CANCELLATION_TOO_LATE";
            case ALREADY_TERMINAL -> "RG.TEST.STABILITY_JOB_CANCELLATION_TERMINAL_NOOP";
            case PARENT_ALREADY_COMPLETED ->
                    "RG.TEST.STABILITY_JOB_CANCELLATION_PARENT_COMPLETED";
        };
    }

    private static String normalized(Object value) {
        return value == null ? "" : value.toString().trim();
    }
}
