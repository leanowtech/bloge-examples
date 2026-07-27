package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Independent verifier for one continuous-assessment quarantine remediation receipt.
 *
 * <p>The verifier links no server or Spring type. It verifies the strict command and receipt
 * Schemas, both projection content addresses, the appended lifecycle event, exact caller fences,
 * actor-bound command identity, permitted state delta, and receipt content address.</p>
 */
public final class
AuthoritativeOutcomeContinuousAssessmentRemediationVerifier {
    /** Canonical actor-bound command identity version. */
    public static final String COMMAND_BINDING_SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeContinuousAssessmentRemediationCommandBinding.v1";
    /** Maximum canonical actor-bound command bytes. */
    public static final int MAXIMUM_COMMAND_BINDING_BYTES =
            128 * 1024;
    /** Maximum canonical receipt bytes. */
    public static final int MAXIMUM_RECEIPT_BYTES =
            1024 * 1024;

    /** Creates one stateless remediation verifier. */
    public AuthoritativeOutcomeContinuousAssessmentRemediationVerifier() {
    }

    /** Bounded remediation verification outcome. */
    public enum Outcome {
        /** Schema, command, transition, lifecycle, and content-address checks passed. */
        VERIFIED,
        /** At least one independent remediation closure check failed. */
        INVALID
    }

    /**
     * Payload-free verification result suitable for CI and governance clients.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param projectionId verified projection identity, or blank when unavailable
     * @param commandId verified remediation command identity, or blank when unavailable
     * @param remediationGeneration verified monotonic remediation generation
     * @param receiptFingerprint verified receipt content address, or blank when unavailable
     * @param currentProjectionFingerprint accepted projection content address, or blank when
     *        unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String projectionId,
            String commandId,
            long remediationGeneration,
            String receiptFingerprint,
            String currentProjectionFingerprint
    ) {
        /** Bounds all diagnostics and rejects contradictory success results. */
        public VerificationResult {
            outcome = Objects.requireNonNull(
                    outcome, "outcome");
            reasonCode = bounded(
                    reasonCode, 255);
            projectionId = bounded(
                    projectionId, 512);
            commandId = bounded(
                    commandId, 128);
            receiptFingerprint = bounded(
                    receiptFingerprint, 128);
            currentProjectionFingerprint = bounded(
                    currentProjectionFingerprint, 128);
            if (!reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")
                    || remediationGeneration < 0
                    || outcome == Outcome.VERIFIED
                    && (projectionId.isBlank()
                    || commandId.isBlank()
                    || remediationGeneration < 1
                    || !fingerprint(receiptFingerprint)
                    || !fingerprint(
                    currentProjectionFingerprint))) {
                throw new IllegalArgumentException(
                        "Continuous assessment remediation verification result is invalid");
            }
        }

        /**
         * Reports whether every independent remediation check passed.
         *
         * @return whether every independent remediation check passed
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }
    }

    /**
     * Verifies one decoded remediation receipt without trusting producer-derived fields.
     *
     * @param receipt decoded strict remediation receipt
     * @return bounded verification result with log-safe coordinates
     */
    public VerificationResult verify(JsonNode receipt) {
        Coordinates coordinates =
                Coordinates.from(receipt);
        try {
            CapabilityMirrorSchemaValidator.require(
                    receipt,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_RECEIPT_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_RECEIPT_SCHEMA_INVALID");
            JsonNode command = receipt.path("command");
            CapabilityMirrorSchemaValidator.require(
                    command,
                    CapabilityMirrorProtocol
                            .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_REQUEST_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_COMMAND_SCHEMA_INVALID");
            String projectionId =
                    text(receipt, "projectionId");
            JsonNode previous =
                    receipt.path("previousProjection");
            JsonNode event =
                    receipt.path("lifecycleEvent");
            JsonNode current =
                    event.path("projection");
            AuthoritativeOutcomeContinuousAssessmentVerifier
                    .requireProjection(
                            previous,
                            canonicalInstant(
                                    previous,
                                    "updatedAt"));
            requireLifecycle(
                    projectionId,
                    command,
                    event);
            requireTransition(
                    receipt.path("scope"),
                    projectionId,
                    command,
                    previous,
                    event,
                    current);
            String expectedCommandFingerprint =
                    commandFingerprint(
                            receipt.path("scope"),
                            projectionId,
                            event.path(
                                    "actorFingerprint")
                                    .asText(""),
                            command);
            if (!expectedCommandFingerprint.equals(
                    text(
                            receipt,
                            "commandFingerprint"))) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_COMMAND_FINGERPRINT_INVALID");
            }
            ObjectNode material =
                    ((ObjectNode) receipt)
                            .deepCopy();
            String claimed =
                    text(
                            receipt,
                            "receiptFingerprint");
            material.put(
                    "receiptFingerprint", "");
            if (!EvidenceVerificationSupport
                    .sha256Bounded(
                            material,
                            MAXIMUM_RECEIPT_BYTES)
                    .equals(claimed)) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_RECEIPT_FINGERPRINT_INVALID");
            }
            return new VerificationResult(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    projectionId,
                    command.path("commandId")
                            .asText(""),
                    receipt.path(
                            "remediationGeneration")
                            .asLong(),
                    claimed,
                    current.path(
                            "recordFingerprint")
                            .asText(""));
        } catch (VerificationFailure failure) {
            return invalid(
                    failure.reasonCode,
                    coordinates);
        } catch (RuntimeException invalid) {
            return invalid(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_INVALID",
                    coordinates);
        }
    }

    private static void requireLifecycle(
            String projectionId,
            JsonNode command,
            JsonNode event) {
        long afterOrdinal =
                command.path(
                        "expectedLifecycleHeadOrdinal")
                        .asLong(-1);
        String predecessor =
                command.path(
                        "expectedLifecycleHeadFingerprint")
                        .asText("");
        ObjectNode page =
                JsonNodeFactory.instance.objectNode();
        page.put(
                "schemaVersion",
                CapabilityMirrorProtocol
                        .AUTHORITATIVE_OUTCOME_CONTINUOUS_ASSESSMENT_LIFECYCLE_PAGE_V1);
        page.put("projectionId", projectionId);
        page.put(
                "afterOrdinal", afterOrdinal);
        page.put(
                "predecessorFingerprint",
                predecessor);
        page.put(
                "nextOrdinal",
                event.path("eventOrdinal")
                        .asLong(-1));
        page.put("hasMore", false);
        ArrayNode events =
                page.putArray("events");
        events.add(event.deepCopy());
        AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier
                .VerificationResult verified =
                new
                        AuthoritativeOutcomeContinuousAssessmentLifecycleVerifier()
                        .verify(
                                page,
                                afterOrdinal,
                                predecessor);
        if (!verified.verified()
                || !projectionId.equals(
                verified.projectionId())) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_LIFECYCLE_INVALID");
        }
    }

    private static void requireTransition(
            JsonNode scope,
            String projectionId,
            JsonNode command,
            JsonNode previous,
            JsonNode event,
            JsonNode current) {
        if (!scope.equals(
                previous.path("scope"))
                || !scope.equals(
                current.path("scope"))
                || !projectionId.equals(
                previous.path(
                        "projectionId")
                        .asText(""))
                || !projectionId.equals(
                current.path(
                        "projectionId")
                        .asText(""))
                || !"QUARANTINED".equals(
                previous.path("status")
                        .asText(""))
                || !"REMEDIATION_ACCEPTED".equals(
                event.path("transition")
                        .asText(""))
                || !"QUEUED".equals(
                current.path("status")
                        .asText(""))
                || !command.path(
                "expectedProjectionFingerprint")
                .asText("")
                .equals(
                        previous.path(
                                "recordFingerprint")
                                .asText(""))
                || event.path("eventOrdinal")
                .asLong(-1)
                != command.path(
                "expectedLifecycleHeadOrdinal")
                .asLong(-1) + 1
                || !event.path(
                "previousEventFingerprint")
                .asText("")
                .equals(
                        command.path(
                                "expectedLifecycleHeadFingerprint")
                                .asText(""))
                || event.path(
                "actorFingerprint")
                .asText("")
                .isBlank()
                || canonicalInstant(
                event,
                "occurredAt")
                .isBefore(
                        canonicalInstant(
                                previous,
                                "updatedAt"))
                || !same(
                previous,
                current,
                "populationRef",
                "assessmentId",
                "lastAssessmentRef",
                "observationSetFingerprint",
                "dispositionSetFingerprint",
                "currentThrough",
                "freshUntil",
                "attemptCount",
                "leaseEpoch",
                "createdAt")
                || current.path(
                "consecutiveFailures")
                .asInt(-1) != 0
                || !current.path(
                "nextEligibleAt")
                .equals(
                        current.path(
                                "updatedAt"))
                || !current.path(
                "leaseOwnerFingerprint")
                .asText("")
                .isBlank()
                || !"1970-01-01T00:00:00Z".equals(
                current.path(
                        "leaseExpiresAt")
                        .asText(""))
                || !current.path(
                "failureCode")
                .asText("")
                .isBlank()
                || !current.path(
                "terminalAt")
                .isNull()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_TRANSITION_INVALID");
        }
    }

    private static boolean same(
            JsonNode left,
            JsonNode right,
            String... fields) {
        for (String field : fields) {
            if (!left.path(field)
                    .equals(right.path(field))) {
                return false;
            }
        }
        return true;
    }

    private static String commandFingerprint(
            JsonNode scope,
            String projectionId,
            String actorFingerprint,
            JsonNode command) {
        ObjectNode binding =
                JsonNodeFactory.instance.objectNode();
        binding.put(
                "schemaVersion",
                COMMAND_BINDING_SCHEMA_VERSION);
        binding.set(
                "scope", scope.deepCopy());
        binding.put(
                "projectionId", projectionId);
        binding.put(
                "actorFingerprint",
                actorFingerprint);
        binding.set(
                "command", command.deepCopy());
        return EvidenceVerificationSupport
                .sha256Bounded(
                        binding,
                        MAXIMUM_COMMAND_BINDING_BYTES);
    }

    private static Instant canonicalInstant(
            JsonNode value,
            String field) {
        try {
            String encoded =
                    text(value, field);
            Instant exact =
                    Instant.parse(encoded);
            if (!exact.toString()
                    .equals(encoded)) {
                fail(
                        "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_TIME_INVALID");
            }
            return exact;
        } catch (DateTimeParseException invalid) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_TIME_INVALID");
            throw new IllegalStateException(
                    "unreachable");
        }
    }

    private static String text(
            JsonNode value,
            String field) {
        JsonNode candidate =
                value == null
                        ? null : value.get(field);
        if (candidate == null
                || !candidate.isTextual()
                || candidate.textValue()
                .isBlank()) {
            fail(
                    "OUTCOME_CONTINUOUS_ASSESSMENT_REMEDIATION_FIELD_INVALID");
        }
        return candidate.textValue();
    }

    private static VerificationResult invalid(
            String reasonCode,
            Coordinates coordinates) {
        return new VerificationResult(
                Outcome.INVALID,
                reasonCode,
                coordinates.projectionId,
                coordinates.commandId,
                Math.max(
                        0,
                        coordinates.remediationGeneration),
                coordinates.receiptFingerprint,
                "");
    }

    private static boolean fingerprint(String value) {
        return value != null
                && value.matches(
                "sha256:[a-f0-9]{64}");
    }

    private static String bounded(
            String value,
            int maximum) {
        String exact =
                value == null ? "" : value.trim();
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
            String commandId,
            long remediationGeneration,
            String receiptFingerprint
    ) {
        private static Coordinates from(
                JsonNode receipt) {
            if (receipt == null
                    || !receipt.isObject()) {
                return new Coordinates(
                        "", "", 0, "");
            }
            return new Coordinates(
                    receipt.path(
                            "projectionId")
                            .asText(""),
                    receipt.path("command")
                            .path("commandId")
                            .asText(""),
                    receipt.path(
                            "remediationGeneration")
                            .asLong(0),
                    receipt.path(
                            "receiptFingerprint")
                            .asText(""));
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
