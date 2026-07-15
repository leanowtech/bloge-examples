package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pinned multi-key lifecycle snapshot used for release-grade offline evidence verification.
 *
 * @param schemaVersion public key-set protocol version
 * @param snapshotFingerprint externally pinnable canonical material fingerprint
 * @param provider signing authority name
 * @param generatedAt snapshot generation time
 * @param expiresAt policy freshness deadline
 * @param activeKeyId active evidence signing key
 * @param policyCompleteness complete history or current-state-only marker
 * @param keys public key policies
 * @param events ordered lifecycle facts
 * @param attestation snapshot material signature
 * @param rawSnapshot defensive complete payload
 */
public record EvidenceVerificationKeySet(
        String schemaVersion,
        String snapshotFingerprint,
        String provider,
        Instant generatedAt,
        Instant expiresAt,
        String activeKeyId,
        PolicyCompleteness policyCompleteness,
        List<KeyPolicy> keys,
        List<LifecycleEvent> events,
        SnapshotAttestation attestation,
        JsonNode rawSnapshot
) {
    /** Lifecycle-history completeness. */
    public enum PolicyCompleteness {
        /** The snapshot carries enough events for historical signing-time decisions. */
        COMPLETE,
        /** The snapshot carries current labels only and is insufficient for release verification. */
        CURRENT_STATE_ONLY
    }

    /** Public verification key state. */
    public enum KeyState {
        /** The key may create and verify evidence. */
        ACTIVE,
        /** The key may verify historical evidence but may not create new evidence. */
        VERIFY_ONLY,
        /** The key was administratively disabled. */
        DISABLED,
        /** The key was revoked by its authority. */
        REVOKED
    }

    /** Key lifecycle transition or compromise declaration. */
    public enum EventType {
        /** The key was created. */
        CREATED,
        /** The key became eligible to sign evidence. */
        ACTIVATED,
        /** The key stopped creating new evidence but retained historical verification use. */
        RETIRED,
        /** The key was administratively disabled. */
        DISABLED,
        /** The authority revoked the key. */
        REVOKED,
        /** The authority declared that the key may have been compromised. */
        COMPROMISE_DECLARED
    }

    /** Historical revocation behavior. */
    public enum RevocationMode {
        /** Evidence signed before the event effective time remains eligible. */
        PROSPECTIVE,
        /** Evidence is invalid from an explicitly declared earlier time. */
        RETROACTIVE
    }

    /**
     * Public key validity and current state.
     *
     * @param keyId stable provider key id
     * @param algorithm signature algorithm
     * @param encodedPublicKey base64 X.509 SubjectPublicKeyInfo
     * @param createdAt key creation time
     * @param notBefore earliest permitted signing time
     * @param notAfter exclusive latest permitted signing time
     * @param state current lifecycle state
     * @param providerKeyVersion opaque provider version
     */
    public record KeyPolicy(String keyId, String algorithm, String encodedPublicKey,
                            Instant createdAt, Instant notBefore, Instant notAfter,
                            KeyState state, String providerKeyVersion) {
        /** Normalizes public key material. */
        public KeyPolicy {
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            encodedPublicKey = normalized(encodedPublicKey);
            providerKeyVersion = normalized(providerKeyVersion);
            if (keyId.isBlank() || algorithm.isBlank() || encodedPublicKey.isBlank()
                    || createdAt == null || notBefore == null || state == null
                    || notBefore.isBefore(createdAt)
                    || (notAfter != null && !notAfter.isAfter(notBefore))) {
                throw new IllegalArgumentException("Evidence key policy is incomplete");
            }
        }
    }

    /**
     * Time-aware key lifecycle fact.
     *
     * @param sequence strictly increasing authority-local order
     * @param eventId stable event identifier
     * @param keyId affected verification key
     * @param type transition or compromise declaration
     * @param occurredAt authority recording time
     * @param effectiveAt policy effective time
     * @param revocationMode prospective or retroactive behavior for revocation events
     * @param invalidFrom earliest invalid signing time for retroactive revocation
     * @param reasonCode bounded machine-readable reason
     */
    public record LifecycleEvent(long sequence, String eventId, String keyId, EventType type,
                                 Instant occurredAt, Instant effectiveAt,
                                 RevocationMode revocationMode, Instant invalidFrom,
                                 String reasonCode) {
        /** Normalizes lifecycle identity. */
        public LifecycleEvent {
            eventId = normalized(eventId);
            keyId = normalized(keyId);
            reasonCode = normalized(reasonCode);
            if (sequence < 1 || eventId.isBlank() || keyId.isBlank() || type == null
                    || occurredAt == null || effectiveAt == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("Evidence key lifecycle event is incomplete");
            }
        }
    }

    /**
     * Detached key-set material signature.
     *
     * @param schemaVersion evidence-seal protocol version
     * @param materialFingerprint fingerprint of the canonical key-set material
     * @param algorithm signature algorithm
     * @param keyId active key that signed the snapshot
     * @param signedAt signature creation time
     * @param signature base64 detached signature bytes
     */
    public record SnapshotAttestation(String schemaVersion, String materialFingerprint,
                                      String algorithm, String keyId, Instant signedAt,
                                      String signature) {
        /** Normalizes complete signature material. */
        public SnapshotAttestation {
            schemaVersion = normalized(schemaVersion);
            materialFingerprint = normalized(materialFingerprint);
            algorithm = normalized(algorithm);
            keyId = normalized(keyId);
            signature = normalized(signature);
            if (schemaVersion.isBlank() || !fingerprint(materialFingerprint)
                    || algorithm.isBlank() || keyId.isBlank() || signedAt == null
                    || signature.isBlank()) {
                throw new IllegalArgumentException("Evidence key-set attestation is incomplete");
            }
        }
    }

    /** Normalizes immutable collections and validates the snapshot identity. */
    public EvidenceVerificationKeySet {
        schemaVersion = normalized(schemaVersion);
        snapshotFingerprint = normalized(snapshotFingerprint);
        provider = normalized(provider);
        activeKeyId = normalized(activeKeyId);
        keys = keys == null ? List.of() : keys.stream()
                .sorted(Comparator.comparing(KeyPolicy::keyId)).toList();
        events = events == null ? List.of() : events.stream()
                .sorted(Comparator.comparingLong(LifecycleEvent::sequence)).toList();
        if (!TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1.equals(schemaVersion)
                || !fingerprint(snapshotFingerprint) || provider.isBlank()
                || generatedAt == null || expiresAt == null || !expiresAt.isAfter(generatedAt)
                || activeKeyId.isBlank() || policyCompleteness == null || keys.isEmpty()
                || attestation == null || rawSnapshot == null || !rawSnapshot.isObject()) {
            throw new IllegalArgumentException("Evidence verification key set is incomplete");
        }
        rawSnapshot = rawSnapshot.deepCopy();
    }

    /**
     * Decodes one integration envelope and validates its exact payload kind and identity.
     *
     * @param envelope complete Resource Gateway integration envelope
     * @return schema-validated typed key-set snapshot
     */
    public static EvidenceVerificationKeySet fromEnvelope(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()
                || !"EVIDENCE_VERIFICATION_KEY_SET".equals(envelope.path("payloadKind").asText())
                || !TestingProtocol.EVIDENCE_VERIFICATION_KEY_SET_V1.equals(
                envelope.path("payloadSchemaVersion").asText())
                || !envelope.path("payload").isObject()) {
            throw new IllegalArgumentException("Evidence verification key-set envelope is invalid");
        }
        JsonNode value = envelope.path("payload");
        TestingProtocolSchemaValidator.require(value, "evidenceVerificationKeySet");
        List<KeyPolicy> keys = new ArrayList<>();
        value.path("keys").forEach(key -> keys.add(new KeyPolicy(key.path("keyId").asText(),
                key.path("algorithm").asText(), key.path("encodedPublicKey").asText(),
                instant(key.path("createdAt")), instant(key.path("notBefore")),
                nullableInstant(key.path("notAfter")), enumValue(KeyState.class,
                key.path("state").asText(), "key state"), key.path("providerKeyVersion").asText())));
        List<LifecycleEvent> events = new ArrayList<>();
        value.path("events").forEach(event -> events.add(new LifecycleEvent(
                event.path("sequence").asLong(), event.path("eventId").asText(),
                event.path("keyId").asText(), enumValue(EventType.class,
                event.path("type").asText(), "event type"), instant(event.path("occurredAt")),
                instant(event.path("effectiveAt")), nullableEnum(RevocationMode.class,
                event.path("revocationMode"), "revocation mode"),
                nullableInstant(event.path("invalidFrom")), event.path("reasonCode").asText())));
        JsonNode seal = value.path("attestation");
        SnapshotAttestation attestation = new SnapshotAttestation(seal.path("schemaVersion").asText(),
                seal.path("materialFingerprint").asText(), seal.path("algorithm").asText(),
                seal.path("keyId").asText(), instant(seal.path("signedAt")),
                seal.path("signature").asText());
        return new EvidenceVerificationKeySet(value.path("schemaVersion").asText(),
                value.path("snapshotFingerprint").asText(), value.path("provider").asText(),
                instant(value.path("generatedAt")), instant(value.path("expiresAt")),
                value.path("activeKeyId").asText(), enumValue(PolicyCompleteness.class,
                value.path("policyCompleteness").asText(), "policy completeness"), keys, events,
                attestation, value);
    }

    /**
     * Returns the exact schema-validated payload used for canonical fingerprint verification.
     *
     * @return defensive copy of the complete schema-validated snapshot
     */
    @Override
    public JsonNode rawSnapshot() {
        return rawSnapshot.deepCopy();
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Evidence key-set time is invalid");
        }
    }

    private static Instant nullableInstant(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? null : instant(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Unknown evidence key-set " + field);
        }
    }

    private static <E extends Enum<E>> E nullableEnum(Class<E> type, JsonNode value, String field) {
        return value == null || value.isNull() || value.isMissingNode()
                ? null : enumValue(type, value.asText(), field);
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
