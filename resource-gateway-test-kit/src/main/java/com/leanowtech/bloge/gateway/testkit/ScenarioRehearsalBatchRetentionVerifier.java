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
 * Dependency-light offline verifier for a Scenario batch retention projection and deletion proof.
 *
 * <p>The verifier applies the packaged strict Schema, checks projection/event identity closure,
 * re-derives the latest event content address, applies verification-key lifecycle policy, and
 * verifies the detached Ed25519 seal. It proves Resource Gateway's logical deletion of the batch
 * job, item index, and batch evidence while preserving child Scenario evidence and audit facts.
 * It deliberately does not claim physical-media erasure or external WORM anchoring.</p>
 */
public final class ScenarioRehearsalBatchRetentionVerifier {
    /** Maximum canonical bytes admitted for one batch-retention event. */
    public static final int MAXIMUM_EVENT_BYTES = 64 * 1024;

    /** Creates a verifier with the fixed Scenario batch-retention v1 policy. */
    public ScenarioRehearsalBatchRetentionVerifier() {
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
     * @param jobId Scenario batch identity, or blank when unavailable
     * @param requestId batch request identity, or blank when unavailable
     * @param manifestFingerprint immutable batch closure, or blank when unavailable
     * @param revision latest retention revision, or zero when unavailable
     * @param eventType latest event type, or blank when unavailable
     * @param evidenceBundleFingerprint deleted or retained batch-evidence identity
     * @param eventFingerprint independently derived latest-event identity
     * @param keyId detached-seal key identity, or blank when unavailable
     */
    public record VerificationResult(
            Outcome outcome,
            String reasonCode,
            String jobId,
            String requestId,
            String manifestFingerprint,
            long revision,
            String eventType,
            String evidenceBundleFingerprint,
            String eventFingerprint,
            String keyId
    ) {
        /** Validates bounded log-safe verification output. */
        public VerificationResult {
            reasonCode = normalized(reasonCode);
            jobId = normalized(jobId);
            requestId = normalized(requestId);
            manifestFingerprint = normalized(manifestFingerprint);
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
                        "Scenario batch retention verification result is invalid");
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
         * Reports whether the verified event proves governed batch-aggregate deletion.
         *
         * @return true only for a verified signed logical-deletion proof
         */
        public boolean verifiedDeletionProof() {
            return verified() && "PURGED".equals(eventType);
        }
    }

    /**
     * Independently verifies one decoded Scenario batch-retention payload.
     *
     * @param state decoded v1 batch-retention projection
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
                            .SCENARIO_REHEARSAL_BATCH_RETENTION_STATE_SCHEMA_RESOURCE,
                    "RG.MIRROR.CLIENT.SCENARIO_BATCH_RETENTION_SCHEMA_INVALID");
        } catch (RuntimeException invalid) {
            return result(
                    Outcome.INVALID,
                    "SCENARIO_BATCH_RETENTION_SCHEMA_INVALID",
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
                        "SCENARIO_BATCH_RETENTION_FINGERPRINT_INVALID",
                        coordinates, eventFingerprint);
            }
            Instant occurredAt = instant(
                    event.path("occurredAt"));
            Instant signedAt = instant(
                    seal.path("signedAt"));
            if (signedAt.isBefore(occurredAt)) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_BATCH_RETENTION_SIGNING_TIME_INVALID",
                        coordinates, eventFingerprint);
            }
            if (key == null) {
                return result(
                        Outcome.KEY_UNAVAILABLE,
                        "SCENARIO_BATCH_RETENTION_KEY_UNAVAILABLE",
                        coordinates, eventFingerprint);
            }
            if (!key.keyId().equals(
                    seal.path("keyId").asText())
                    || !"Ed25519".equals(key.algorithm())
                    || !"Ed25519".equals(
                    seal.path("algorithm").asText())) {
                return result(
                        Outcome.POLICY_REJECTED,
                        "SCENARIO_BATCH_RETENTION_KEY_IDENTITY_REJECTED",
                        coordinates, eventFingerprint);
            }
            if (!key.verificationAllowed()
                    || signedAt.isBefore(
                    key.createdAt().minus(
                            EvidenceVerificationSupport
                                    .KEY_CREATION_SKEW))) {
                return result(
                        Outcome.POLICY_REJECTED,
                        "SCENARIO_BATCH_RETENTION_KEY_POLICY_REJECTED",
                        coordinates, eventFingerprint);
            }
            if (!EvidenceVerificationSupport.verifyEd25519(
                    eventFingerprint,
                    seal.path("signature").asText(),
                    key.encodedPublicKey())) {
                return result(
                        Outcome.INVALID,
                        "SCENARIO_BATCH_RETENTION_SIGNATURE_INVALID",
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
                    "SCENARIO_BATCH_RETENTION_PROOF_INVALID",
                    coordinates, "");
        }
    }

    static String eventFingerprint(JsonNode event) {
        ObjectNode material = JsonNodeFactory.instance.objectNode();
        material.put(
                "signatureDomain",
                "RESOURCE_GATEWAY_SCENARIO_BATCH_RETENTION_V1");
        copy(material, event, "schemaVersion");
        copy(material, event, "eventId");
        copy(material, event, "commandId");
        copy(material, event, "scope");
        copy(material, event, "requestId");
        copy(material, event, "jobId");
        copy(material, event, "manifestFingerprint");
        copy(material, event, "revision");
        copy(material, event, "type");
        copy(material, event, "retainUntil");
        copy(material, event, "occurredAt");
        copy(material, event, "actorId");
        copy(material, event, "reasonCode");
        copy(material, event, "holdId");
        copy(material, event, "evidenceBundleFingerprint");
        copy(material, event, "previousEventFingerprint");
        copy(material, event, "deletedJobCount");
        copy(material, event, "deletedItemCount");
        copy(material, event, "deletedBatchEvidenceCount");
        copy(material, event, "childEvidenceDisposition");
        copy(material, event, "auditDisposition");
        return EvidenceVerificationSupport.sha256Bounded(
                material, MAXIMUM_EVENT_BYTES);
    }

    private static void verifyProjectionClosure(
            JsonNode state, JsonNode event) {
        for (String field : Set.of(
                "scope", "requestId", "jobId",
                "manifestFingerprint",
                "evidenceBundleFingerprint", "revision",
                "retainUntil")) {
            if (!state.path(field).equals(
                    event.path(field))) {
                throw new IllegalArgumentException(
                        "batch retention projection closure is invalid");
            }
        }
        if (!state.path("updatedAt").equals(
                event.path("occurredAt"))) {
            throw new IllegalArgumentException(
                    "batch retention projection time closure is invalid");
        }
        Set<String> holds = new HashSet<>();
        String previous = "";
        for (JsonNode hold : state.path("activeHoldIds")) {
            String current = hold.asText();
            if (!holds.add(current)
                    || !previous.isEmpty()
                    && previous.compareTo(current) >= 0) {
                throw new IllegalArgumentException(
                        "batch retention hold closure is invalid");
            }
            previous = current;
        }
        boolean purged =
                "PURGED".equals(state.path("status").asText());
        if (purged != "PURGED".equals(
                event.path("type").asText())
                || purged && !holds.isEmpty()) {
            throw new IllegalArgumentException(
                    "batch retention deletion-proof closure is invalid");
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
                coordinates.jobId(),
                coordinates.requestId(),
                coordinates.manifestFingerprint(),
                coordinates.revision(),
                coordinates.eventType(),
                coordinates.evidenceBundleFingerprint(),
                eventFingerprint,
                coordinates.keyId());
    }

    private record Coordinates(
            String jobId,
            String requestId,
            String manifestFingerprint,
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
                    value.path("jobId").asText(),
                    value.path("requestId").asText(),
                    value.path("manifestFingerprint").asText(),
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
