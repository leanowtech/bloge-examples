package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Schema-validated bounded consistency page joined to a current evidence verification key set.
 *
 * @param schemaVersion trust-bundle protocol version
 * @param generatedAt server observation time
 * @param trustDomain governance trust domain
 * @param logId append-only log identity
 * @param afterSequence requested checkpoint sequence
 * @param throughSequence final publication sequence in this page
 * @param highWaterSequence observed log head sequence
 * @param headPublicationFingerprint observed head fingerprint
 * @param headPublication complete observed head
 * @param hasMore whether another page is required
 * @param publications contiguous proof page
 * @param keySet current signed evidence key set
 * @param rawBundle defensive complete schema-validated payload
 */
public record EvidenceKeySetTrustBundle(
        String schemaVersion,
        Instant generatedAt,
        String trustDomain,
        String logId,
        long afterSequence,
        long throughSequence,
        long highWaterSequence,
        String headPublicationFingerprint,
        Publication headPublication,
        boolean hasMore,
        List<Publication> publications,
        EvidenceVerificationKeySet keySet,
        JsonNode rawBundle
) {
    /** Pin state distributed by one governance publication. */
    public enum PinState {
        /** Exact key-set snapshot expected from the current evidence signer generation. */
        ACTIVE,
        /** Older or staged snapshot accepted only during a controlled overlap window. */
        OVERLAP,
        /** Snapshot permanently denied after compromise or governance withdrawal. */
        REVOKED
    }

    /**
     * Exact key-set fingerprint policy in one publication.
     *
     * @param snapshotFingerprint canonical evidence key-set snapshot fingerprint
     * @param state governed acceptance state
     * @param validFrom inclusive policy activation time
     * @param validUntil exclusive policy end, or null when unscheduled
     * @param revokedAt revocation declaration time, required for revoked pins
     * @param reasonCode bounded machine-readable revocation reason
     */
    public record SnapshotPin(
            String snapshotFingerprint,
            PinState state,
            Instant validFrom,
            Instant validUntil,
            Instant revokedAt,
            String reasonCode
    ) {
        /** Normalizes decoded pin metadata. */
        public SnapshotPin {
            snapshotFingerprint = normalized(snapshotFingerprint);
            reasonCode = normalized(reasonCode);
            if (!fingerprint(snapshotFingerprint) || state == null || validFrom == null) {
                throw new IllegalArgumentException("Evidence trust pin is incomplete");
            }
        }

        /**
         * Tests whether this pin authorizes its snapshot at an observation time.
         *
         * @param observedAt policy observation time
         * @return true for an active or overlap pin inside its validity window
         */
        public boolean acceptedAt(Instant observedAt) {
            return state != PinState.REVOKED && observedAt != null
                    && !observedAt.isBefore(validFrom)
                    && (validUntil == null || observedAt.isBefore(validUntil));
        }
    }

    /**
     * Detached governance authority signature.
     *
     * @param authorityId externally provisioned authority identity
     * @param algorithm signature algorithm
     * @param signature base64 detached signature bytes
     */
    public record AuthoritySignature(String authorityId, String algorithm, String signature) {
        /** Normalizes decoded public signature material. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            if (authorityId.isBlank() || algorithm.isBlank() || signature.isBlank()) {
                throw new IllegalArgumentException("Evidence trust authority signature is incomplete");
            }
        }
    }

    /**
     * One immutable externally authorized trust-log publication.
     *
     * @param schemaVersion trust-publication protocol version
     * @param publicationFingerprint canonical publication material fingerprint
     * @param trustDomain governance trust domain
     * @param logId append-only log identity
     * @param sequence contiguous one-based sequence
     * @param previousPublicationFingerprint preceding publication fingerprint
     * @param recoveryEpoch compromised-pin recovery generation
     * @param publishedAt governance authorization time
     * @param expiresAt exclusive policy freshness deadline
     * @param pins accepted and explicitly revoked key-set snapshots
     * @param signatures detached governance authority signatures
     * @param rawPublication defensive complete schema-validated publication
     */
    public record Publication(
            String schemaVersion,
            String publicationFingerprint,
            String trustDomain,
            String logId,
            long sequence,
            String previousPublicationFingerprint,
            long recoveryEpoch,
            Instant publishedAt,
            Instant expiresAt,
            List<SnapshotPin> pins,
            List<AuthoritySignature> signatures,
            JsonNode rawPublication
    ) {
        /** Normalizes canonical order and preserves exact schema-validated wire material. */
        public Publication {
            schemaVersion = normalized(schemaVersion);
            publicationFingerprint = normalized(publicationFingerprint);
            trustDomain = normalized(trustDomain);
            logId = normalized(logId);
            previousPublicationFingerprint = normalized(previousPublicationFingerprint);
            pins = pins == null ? List.of() : pins.stream()
                    .sorted(Comparator.comparing(SnapshotPin::snapshotFingerprint)).toList();
            signatures = signatures == null ? List.of() : signatures.stream()
                    .sorted(Comparator.comparing(AuthoritySignature::authorityId)).toList();
            if (!TestingProtocol.EVIDENCE_KEY_SET_TRUST_PUBLICATION_V1.equals(schemaVersion)
                    || !fingerprint(publicationFingerprint) || trustDomain.isBlank() || logId.isBlank()
                    || sequence < 1 || recoveryEpoch < 0 || publishedAt == null || expiresAt == null
                    || pins.isEmpty() || signatures.isEmpty() || rawPublication == null
                    || !rawPublication.isObject()) {
                throw new IllegalArgumentException("Evidence trust publication is incomplete");
            }
            rawPublication = rawPublication.deepCopy();
        }

        /**
         * Returns exact schema-validated material for canonical verification.
         *
         * @return defensive exact publication
         */
        @Override
        public JsonNode rawPublication() {
            return rawPublication.deepCopy();
        }
    }

    /** Normalizes immutable page state after authoritative schema validation. */
    public EvidenceKeySetTrustBundle {
        schemaVersion = normalized(schemaVersion);
        trustDomain = normalized(trustDomain);
        logId = normalized(logId);
        headPublicationFingerprint = normalized(headPublicationFingerprint);
        publications = publications == null ? List.of() : List.copyOf(publications);
        if (!TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1.equals(schemaVersion)
                || generatedAt == null || trustDomain.isBlank() || logId.isBlank()
                || afterSequence < 0 || throughSequence < afterSequence
                || highWaterSequence < 1 || !fingerprint(headPublicationFingerprint)
                || headPublication == null || keySet == null || rawBundle == null || !rawBundle.isObject()) {
            throw new IllegalArgumentException("Evidence trust bundle is incomplete");
        }
        rawBundle = rawBundle.deepCopy();
    }

    /**
     * Decodes one integration envelope and validates the exact bundle schema before projection.
     *
     * @param envelope complete Resource Gateway integration envelope
     * @return typed defensive trust bundle
     */
    public static EvidenceKeySetTrustBundle fromEnvelope(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()
                || !"EVIDENCE_KEY_SET_TRUST_BUNDLE".equals(envelope.path("payloadKind").asText())
                || !TestingProtocol.EVIDENCE_KEY_SET_TRUST_BUNDLE_V1.equals(
                envelope.path("payloadSchemaVersion").asText())
                || !envelope.path("payload").isObject()) {
            throw new IllegalArgumentException("Evidence trust-bundle envelope is invalid");
        }
        JsonNode value = envelope.path("payload");
        TestingProtocolSchemaValidator.requireRoot(
                value, TestingProtocol.EVIDENCE_TRUST_BUNDLE_SCHEMA_RESOURCE);
        List<Publication> publications = new ArrayList<>();
        value.path("publications").forEach(item -> publications.add(publication(item)));
        return new EvidenceKeySetTrustBundle(value.path("schemaVersion").asText(),
                instant(value.path("generatedAt")), value.path("trustDomain").asText(),
                value.path("logId").asText(), value.path("afterSequence").asLong(),
                value.path("throughSequence").asLong(), value.path("highWaterSequence").asLong(),
                value.path("headPublicationFingerprint").asText(),
                publication(value.path("headPublication")), value.path("hasMore").asBoolean(),
                publications, EvidenceVerificationKeySet.fromPayload(value.path("keySet")), value);
    }

    /**
     * Returns the complete schema-validated trust-bundle payload.
     *
     * @return defensive payload copy
     */
    @Override
    public JsonNode rawBundle() {
        return rawBundle.deepCopy();
    }

    private static Publication publication(JsonNode value) {
        List<SnapshotPin> pins = new ArrayList<>();
        value.path("pins").forEach(pin -> pins.add(new SnapshotPin(
                pin.path("snapshotFingerprint").asText(),
                enumValue(PinState.class, pin.path("state").asText()),
                instant(pin.path("validFrom")), nullableInstant(pin.path("validUntil")),
                nullableInstant(pin.path("revokedAt")), pin.path("reasonCode").asText())));
        List<AuthoritySignature> signatures = new ArrayList<>();
        value.path("signatures").forEach(signature -> signatures.add(new AuthoritySignature(
                signature.path("authorityId").asText(), signature.path("algorithm").asText(),
                signature.path("signature").asText())));
        return new Publication(value.path("schemaVersion").asText(),
                value.path("publicationFingerprint").asText(), value.path("trustDomain").asText(),
                value.path("logId").asText(), value.path("sequence").asLong(),
                value.path("previousPublicationFingerprint").asText(),
                value.path("recoveryEpoch").asLong(), instant(value.path("publishedAt")),
                instant(value.path("expiresAt")), pins, signatures, value);
    }

    private static Instant instant(JsonNode value) {
        try {
            return Instant.parse(value.asText());
        } catch (DateTimeParseException failure) {
            throw new IllegalArgumentException("Evidence trust time is invalid");
        }
    }

    private static Instant nullableInstant(JsonNode value) {
        return value == null || value.isNull() || value.isMissingNode() ? null : instant(value);
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, normalized(value));
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Evidence trust enum value is invalid");
        }
    }

    private static boolean fingerprint(String value) {
        return normalized(value).matches("sha256:[0-9a-f]{64}");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
