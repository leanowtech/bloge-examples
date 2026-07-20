package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Authenticated remote delivery port for one complete bootstrap-root chain.
 *
 * <p>The caller supplies the immutable content-addressed request obtained from
 * {@link ExternalSequenceAnchorBootstrapRootPublicationOutbox}. A conforming implementation must
 * conditionally compare the expected predecessor, make the publication id idempotent, and return
 * either an exactly bound success receipt or an authenticated conflict. Transport failures and
 * malformed responses are bounded exceptions and never expose endpoint, key, or provider text.</p>
 *
 * <p>This port does not make the remote publisher Byzantine fault tolerant. A single signed
 * publisher can still equivocate across clients unless a separately certified HA or transparency
 * layer prevents it.</p>
 */
public interface ExternalSequenceAnchorBootstrapRootPublisher extends AutoCloseable {

    /**
     * Publishes or exactly replays one complete chain.
     *
     * @param request content-addressed request under a live local outbox fence
     * @return exact stable publication receipt
     * @throws PublisherException for bounded transport, response, or conflict outcomes
     */
    ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt publish(
            ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationRequest request);

    /**
     * Returns static capability and current key-lifecycle readiness without remote I/O.
     *
     * @return identity-free publisher capability
     */
    Descriptor descriptor();

    /**
     * Returns aggregate process-local outcomes without request or provider identities.
     *
     * @return immutable payload-free runtime projection
     */
    Snapshot snapshot();

    /** Releases implementation-owned transport resources, if any. */
    @Override
    default void close() {
        // Most HTTP clients have no closeable resource owned by this port.
    }

    /** Remote publication outcome represented by a signed response. */
    enum ResponseDecision {
        /** This request atomically advanced the published complete chain. */
        PUBLISHED,

        /** The exact publication id and content had already been committed. */
        IDEMPOTENT_REPLAY,

        /** The authenticated current head cannot accept the requested predecessor or content. */
        CONFLICT
    }

    /** Bounded publisher failure classification safe for control flow and aggregation. */
    enum FailureReason {
        /** Transport, timeout, or configured publisher readiness was unavailable. */
        UNAVAILABLE,

        /** HTTP, JSON, signature, freshness, or request binding was invalid. */
        INVALID_RESPONSE,

        /** A valid signed response proved a conflicting remote head. */
        AUTHENTICATED_CONFLICT
    }

    /**
     * Canonical signed response material returned by the remote publisher.
     *
     * @param schemaVersion material protocol generation
     * @param decision applied, replayed, or conflicting remote decision
     * @param trustDomain configured response-signing trust domain
     * @param publisherId configured logical publisher identity
     * @param keyId configured response-signing key identity
     * @param requestFingerprint exact canonical request fingerprint
     * @param publicationId content-addressed publication identity
     * @param scopeId exact Resource Gateway fleet scope
     * @param rootSetId exact bootstrap-root chain identity
     * @param sequence requested complete-chain sequence
     * @param expectedPreviousMaterialFingerprint requested predecessor head
     * @param bundleFingerprint requested complete bundle fingerprint
     * @param headMaterialFingerprint requested head fingerprint
     * @param observedSequence publisher's current sequence after the decision
     * @param observedHeadMaterialFingerprint publisher's current head after the decision
     * @param publishedAt stable original completion instant, absent only for conflict
     * @param signedAt fresh response-signing instant
     * @param expiresAt exclusive response-authentication expiry
     */
    record ResponseMaterial(
            String schemaVersion,
            ResponseDecision decision,
            String trustDomain,
            String publisherId,
            String keyId,
            String requestFingerprint,
            String publicationId,
            String scopeId,
            String rootSetId,
            long sequence,
            String expectedPreviousMaterialFingerprint,
            String bundleFingerprint,
            String headMaterialFingerprint,
            long observedSequence,
            String observedHeadMaterialFingerprint,
            Instant publishedAt,
            Instant signedAt,
            Instant expiresAt) {

        /** Current publisher response material generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublisherResponseMaterial.v1";

        /** Enforces exact success shape and a meaningful conflict shape. */
        public ResponseMaterial {
            schemaVersion = normalized(schemaVersion);
            decision = Objects.requireNonNull(decision, "decision");
            trustDomain = identifier(trustDomain, "trustDomain");
            publisherId = identifier(publisherId, "publisherId");
            keyId = identifier(keyId, "keyId");
            requestFingerprint = fingerprint(requestFingerprint, "requestFingerprint");
            publicationId = identifier(publicationId, "publicationId");
            scopeId = identifier(scopeId, "scopeId");
            rootSetId = identifier(rootSetId, "rootSetId");
            expectedPreviousMaterialFingerprint = fingerprint(
                    expectedPreviousMaterialFingerprint,
                    "expectedPreviousMaterialFingerprint");
            bundleFingerprint = fingerprint(bundleFingerprint, "bundleFingerprint");
            headMaterialFingerprint = fingerprint(
                    headMaterialFingerprint, "headMaterialFingerprint");
            observedHeadMaterialFingerprint = fingerprint(
                    observedHeadMaterialFingerprint,
                    "observedHeadMaterialFingerprint");
            boolean success = decision != ResponseDecision.CONFLICT;
            boolean exactSuccess = observedSequence == sequence
                    && observedHeadMaterialFingerprint.equals(headMaterialFingerprint)
                    && publishedAt != null;
            boolean meaningfulConflict = decision == ResponseDecision.CONFLICT
                    && publishedAt == null && (
                    observedSequence > sequence
                            || observedSequence == sequence
                            && !observedHeadMaterialFingerprint.equals(
                            headMaterialFingerprint)
                            || observedSequence == sequence - 1L
                            && !observedHeadMaterialFingerprint.equals(
                            expectedPreviousMaterialFingerprint));
            if (!SCHEMA_VERSION.equals(schemaVersion) || sequence < 1L
                    || observedSequence < 0L || success != exactSuccess
                    || decision == ResponseDecision.CONFLICT && !meaningfulConflict
                    || !wholeSecond(signedAt) || !wholeSecond(expiresAt)
                    || !expiresAt.isAfter(signedAt)
                    || publishedAt != null && (!wholeSecond(publishedAt)
                    || publishedAt.isAfter(signedAt))) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher response material is invalid");
            }
        }

        /**
         * Converts an authenticated successful response to the stable durable receipt.
         *
         * @return exact outbox receipt
         */
        public ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt
                toReceipt() {
            if (decision == ResponseDecision.CONFLICT) {
                throw new IllegalStateException(
                        "A publisher conflict cannot become a publication receipt");
            }
            return new ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt(
                    ExternalSequenceAnchorBootstrapRootPublicationOutbox.PublicationReceipt
                            .SCHEMA_VERSION,
                    decision == ResponseDecision.PUBLISHED
                            ? ExternalSequenceAnchorBootstrapRootPublicationOutbox
                            .PublicationReceiptStatus.PUBLISHED
                            : ExternalSequenceAnchorBootstrapRootPublicationOutbox
                            .PublicationReceiptStatus.IDEMPOTENT_REPLAY,
                    publicationId, sequence, bundleFingerprint, headMaterialFingerprint,
                    publishedAt);
        }
    }

    /**
     * Detached Ed25519 envelope over canonical response material.
     *
     * @param schemaVersion envelope protocol generation
     * @param material complete signed response facts
     * @param materialFingerprint canonical SHA-256 material identity
     * @param signatureBase64 detached 64-byte Ed25519 signature over the fingerprint text
     */
    record SignedResponse(
            String schemaVersion,
            ResponseMaterial material,
            String materialFingerprint,
            String signatureBase64) {

        /** Current signed publisher response envelope generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublisherResponse.v1";

        /** Rejects incomplete or malformed response envelopes. */
        public SignedResponse {
            schemaVersion = normalized(schemaVersion);
            material = Objects.requireNonNull(material, "material");
            materialFingerprint = fingerprint(materialFingerprint, "materialFingerprint");
            signatureBase64 = normalized(signatureBase64);
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !validSignatureShape(signatureBase64)) {
                throw new IllegalArgumentException(
                        "Bootstrap-root signed publisher response is invalid");
            }
        }

        /**
         * Checks the canonical material identity before signature verification.
         *
         * @param objectMapper canonical JSON baseline
         * @return whether the claimed fingerprint exactly matches the material
         */
        public boolean fingerprintVerified(ObjectMapper objectMapper) {
            return materialFingerprint.equals(ProtocolFingerprint.of(
                    Objects.requireNonNull(objectMapper, "objectMapper"), material));
        }
    }

    /**
     * Identity-free publisher capability projection.
     *
     * @param schemaVersion descriptor generation
     * @param available whether configured response trust is currently usable
     * @param strictHttps whether production endpoints require HTTPS
     * @param signedResponses whether every accepted response is signature verified
     * @param contentAddressedIdempotency whether publication id derives from exact content
     * @param conditionalPredecessor whether remote writes compare the expected predecessor
     * @param staticResponseKey whether response-key changes require process reconfiguration
     * @param maximumRequestBytes hard serialized request bound
     */
    record Descriptor(
            String schemaVersion,
            boolean available,
            boolean strictHttps,
            boolean signedResponses,
            boolean contentAddressedIdempotency,
            boolean conditionalPredecessor,
            boolean staticResponseKey,
            int maximumRequestBytes) {

        /** Current publisher descriptor generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublisherDescriptor.v1";

        /** Enforces truthful mandatory protocol capabilities and a finite payload bound. */
        public Descriptor {
            schemaVersion = normalized(schemaVersion);
            if (!SCHEMA_VERSION.equals(schemaVersion) || !strictHttps || !signedResponses
                    || !contentAddressedIdempotency || !conditionalPredecessor
                    || maximumRequestBytes < 1 || maximumRequestBytes > 4 * 1024 * 1024) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher descriptor is invalid");
            }
        }
    }

    /**
     * Payload-free process-local publisher projection.
     *
     * @param schemaVersion snapshot generation
     * @param available whether the configured key lifecycle is currently usable
     * @param status bounded current adapter status
     * @param publishedCount newly applied successful responses
     * @param replayCount exact idempotent replay responses
     * @param conflictCount authenticated conflict responses
     * @param failureCount unavailable or invalid responses
     * @param lastSuccessfulPublicationAt latest locally authenticated success
     */
    record Snapshot(
            String schemaVersion,
            boolean available,
            String status,
            long publishedCount,
            long replayCount,
            long conflictCount,
            long failureCount,
            Instant lastSuccessfulPublicationAt) {

        /** Current publisher snapshot generation. */
        public static final String SCHEMA_VERSION =
                "bloge.externalSequenceAnchorBootstrapRootPublisherSnapshot.v1";

        /** Enforces bounded status and monotonic aggregate counters. */
        public Snapshot {
            schemaVersion = normalized(schemaVersion);
            status = identifier(status, "status");
            if (!SCHEMA_VERSION.equals(schemaVersion) || publishedCount < 0L
                    || replayCount < 0L || conflictCount < 0L || failureCount < 0L) {
                throw new IllegalArgumentException(
                        "Bootstrap-root publisher snapshot is invalid");
            }
        }
    }

    /** Bounded publisher failure that deliberately excludes remote diagnostics and identity. */
    final class PublisherException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        /** Bounded reason without provider diagnostics. */
        private final FailureReason reason;

        /**
         * Creates one identity-free failure.
         *
         * @param reason bounded control-flow reason
         */
        public PublisherException(FailureReason reason) {
            super("External bootstrap-root publisher was "
                    + Objects.requireNonNull(reason, "reason").name()
                    .toLowerCase(java.util.Locale.ROOT));
            this.reason = reason;
        }

        /**
         * Returns the bounded reason without provider text.
         *
         * @return unavailable, invalid response, or authenticated conflict
         */
        public FailureReason reason() {
            return reason;
        }
    }

    private static boolean validSignatureShape(String value) {
        try {
            return value.length() <= 128
                    && Base64.getDecoder().decode(value).length == 64;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    private static boolean wholeSecond(Instant value) {
        return value != null && value.getNano() == 0;
    }

    private static String identifier(String value, String field) {
        String result = normalized(value);
        if (!IDENTIFIER.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String fingerprint(String value, String field) {
        String result = normalized(value);
        if (!FINGERPRINT.matcher(result).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return result;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /** Shared bounded publisher protocol identity grammar. */
    Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

    /** Shared lowercase SHA-256 protocol fingerprint grammar. */
    Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
}
