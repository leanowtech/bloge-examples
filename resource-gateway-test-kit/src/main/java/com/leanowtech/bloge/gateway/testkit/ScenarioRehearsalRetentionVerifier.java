package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.security.GeneralSecurityException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;

/**
 * Dependency-light offline verifier for a Scenario retention projection and deletion proof.
 *
 * <p>The verifier applies the packaged strict Schema, re-derives the latest event content
 * address, checks projection/event identity closure, applies verification-key lifecycle policy,
 * and verifies the detached Ed25519 seal. The event carries only hashes and governance
 * coordinates, so validation never requires retained customer payload.</p>
 */
public final class ScenarioRehearsalRetentionVerifier {
    /** Maximum canonical bytes admitted for one retention event. */
    public static final int MAXIMUM_EVENT_BYTES = 64 * 1024;

    /** Creates a verifier with the fixed Scenario retention v1 policy. */
    public ScenarioRehearsalRetentionVerifier() {
    }

    /** Bounded offline verification outcome. */
    public enum Outcome {
        /** Schema, closure, fingerprint, key policy, and signature passed. */
        VERIFIED,
        /** Schema, closure, fingerprint, or signature is invalid. */
        INVALID,
        /** The exact signature verification key was not supplied. */
        KEY_UNAVAILABLE,
        /** Public-key lifecycle policy rejects the proof. */
        POLICY_REJECTED
    }

    /**
     * Payload-free result suitable for audit ingestion and publish-gate evidence.
     *
     * @param outcome bounded verification outcome
     * @param reasonCode stable machine-readable reason
     * @param runId Scenario aggregate identity, or blank when unavailable
     * @param requestId aggregate request identity, or blank when unavailable
     * @param revision latest retention revision, or zero when unavailable
     * @param eventType latest event type, or blank when unavailable
     * @param evidenceBundleFingerprint deleted or retained aggregate identity
     * @param eventFingerprint independently derived latest-event identity
     * @param keyId detached-seal key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String runId,
            String requestId,
            long revision,
            String eventType,
            String evidenceBundleFingerprint,
            String eventFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe verification output. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            runId = normalized(runId);
            requestId = normalized(requestId);
            eventType = normalized(eventType);
            evidenceBundleFingerprint =
                    normalized(evidenceBundleFingerprint);
            eventFingerprint = normalized(eventFingerprint);
            keyId = normalized(keyId);
            if (outcome == null
                    || revision < 0
                    || !reasonCode.matches(
                    "[A-Z][A-Z0-9_.-]{0,254}")) {
                throw new IllegalArgumentException(
                        "Scenario retention verification result is invalid");
            }
        }

        /**
         * Reports whether the projection and its latest signed event passed every verification.
         *
         * @return true only for a fully verified projection and latest event
         */
        public boolean verified() {
            return outcome == Outcome.VERIFIED;
        }

        /**
         * Reports whether the verified latest event proves governed aggregate deletion.
         *
         * @return true only for a verified signed deletion proof
         */
        public boolean verifiedDeletionProof() {
            return verified() && "PURGED".equals(eventType);
        }
    }

    /**
     * Independently verifies one decoded Scenario retention-state payload.
     *
     * @param state decoded v1 retention projection
     * @param key exact public key resolved from the latest event key id; may be {@code null}
     * @return bounded payload-free verification result
     */
    public VerificationResult verify(
            JsonNode state, EvidenceVerificationKey key) {
        Coordinates coordinates = Coordinates.from(state);
        try {
            CapabilityMirrorSchemaValidator.require(
                    state,
                    CapabilityMirrorProtocol
                            .SCENARIO_REHEARSAL_RETENTION_STATE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_RETENTION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_RETENTION_SCHEMA_INVALID",
                    coordinates, "");
        }
        JsonNode event = state.path("latestEvent");
        String eventFingerprint;
        try {
            verifyProjectionClosure(state, event);
            eventFingerprint = eventFingerprint(event);
            JsonNode seal = event.path("evidenceSeal");
            if (!eventFingerprint.equals(
                    seal.path("materialFingerprint").asText())) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_RETENTION_FINGERPRINT_INVALID",
                        coordinates, eventFingerprint);
            }
            Instant occurredAt = instant(
                    event.path("occurredAt"));
            Instant signedAt = instant(
                    seal.path("signedAt"));
            if (signedAt.isBefore(occurredAt)) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_RETENTION_SIGNING_TIME_INVALID",
                        coordinates, eventFingerprint);
            }
            if (key == null) {
                return result(
                        Outcome.KEY_UNAVAILABLE,
                        "SCENARIO_RETENTION_KEY_UNAVAILABLE",
                        coordinates, eventFingerprint);
            }
            if (!key.keyId().equals(
                    seal.path("keyId").asText())
                    || !"Ed25519".equals(key.algorithm())
                    || !"Ed25519".equals(
                    seal.path("algorithm").asText())) {
                return result(
                        Outcome.POLICY_REJECTED,
                        "SCENARIO_RETENTION_KEY_IDENTITY_REJECTED",
                        coordinates, eventFingerprint);
            }
            if (!key.verificationAllowed()
                    || signedAt.isBefore(
                    key.createdAt().minus(
                            EvidenceVerificationSupport
                                    .KEY_CREATION_SKEW))) {
                return result(
                        Outcome.POLICY_REJECTED,
                        "SCENARIO_RETENTION_KEY_POLICY_REJECTED",
                        coordinates, eventFingerprint);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    eventFingerprint,
                    seal.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_RETENTION_SIGNATURE_INVALID",
                        coordinates, eventFingerprint);
            }
            return result(
                    Outcome.VERIFIED,
                    "VERIFIED",
                    coordinates, eventFingerprint);
        } catch (DateTimeParseException
                 | GeneralSecurityException
                 | IllegalArgumentException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_RETENTION_PROOF_INVALID",
                    coordinates, "");
        }
    }

    static String eventFingerprint(JsonNode event) {
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        material.put(
                "signatureDomain",
                "RESOURCE_GATEWAY_SCENARIO_RETENTION_V1");
        copy(material, event, "schemaVersion");
        copy(material, event, "eventId");
        copy(material, event, "commandId");
        copy(material, event, "scope");
        copy(material, event, "requestId");
        copy(material, event, "runId");
        copy(material, event, "revision");
        copy(material, event, "type");
        copy(material, event, "retainUntil");
        copy(material, event, "occurredAt");
        copy(material, event, "actorId");
        copy(material, event, "reasonCode");
        copy(material, event, "holdId");
        copy(material, event, "evidenceBundleFingerprint");
        copy(material, event, "previousEventFingerprint");
        copy(material, event, "deletedCaseProgressCount");
        copy(material, event, "childEvidenceDisposition");
        return EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_EVENT_BYTES);
    }

    private static void verifyProjectionClosure(
            JsonNode state, JsonNode event) {
        for (String field : Set.of(
                "scope", "runId", "requestId",
                "evidenceBundleFingerprint", "revision",
                "retainUntil")) {
            if (!state.path(field).equals(
                    event.path(field))) {
                throw new IllegalArgumentException(
                        "retention projection closure is invalid");
            }
        }
        if (!state.path("updatedAt").equals(
                event.path("occurredAt"))) {
            throw new IllegalArgumentException(
                    "retention projection time closure is invalid");
        }
        Set<String> holds = new HashSet<>();
        for (JsonNode hold : state.path("activeHoldIds")) {
            if (!holds.add(hold.asText())) {
                throw new IllegalArgumentException(
                        "retention hold closure is invalid");
            }
        }
        boolean purged =
                "PURGED".equals(state.path("status").asText());
        if (purged != "PURGED".equals(
                event.path("type").asText())
                || purged && !holds.isEmpty()) {
            throw new IllegalArgumentException(
                    "retention deletion-proof closure is invalid");
        }
    }

    private static Instant instant(JsonNode value) {
        return Instant.parse(value.asText());
    }

    private static void copy(
            ObjectNode target, JsonNode source, String field) {
        target.set(field, source.path(field));
    }

    private static VerificationResult result(
            Outcome outcome,
            String reason,
            Coordinates coordinates,
            String eventFingerprint) {
        return new VerificationResult(
                outcome,
                reason,
                coordinates.runId(),
                coordinates.requestId(),
                coordinates.revision(),
                coordinates.eventType(),
                coordinates.evidenceBundleFingerprint(),
                eventFingerprint,
                coordinates.keyId());
    }

    private record Coordinates(
            String runId,
            String requestId,
            long revision,
            String eventType,
            String evidenceBundleFingerprint,
            String keyId
    ) {
        private static Coordinates from(JsonNode state) {
            JsonNode value = state == null
                    ? com.fasterxml.jackson.databind.node
                    .MissingNode.getInstance()
                    : state;
            JsonNode event = value.path("latestEvent");
            return new Coordinates(
                    value.path("runId").asText(),
                    value.path("requestId").asText(),
                    value.path("revision").asLong(0),
                    event.path("type").asText(),
                    value.path(
                            "evidenceBundleFingerprint").asText(),
                    event.path("evidenceSeal")
                            .path("keyId").asText());
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
