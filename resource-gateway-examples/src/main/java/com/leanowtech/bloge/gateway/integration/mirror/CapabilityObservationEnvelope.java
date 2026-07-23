package com.leanowtech.bloge.gateway.integration.mirror;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Signed, payload-free description of one observed capability invocation.
 *
 * <p>The envelope never carries a request, response, business key, secret, or free-form exception
 * message. It points only to payloads that an external vault has already sanitized and finalized,
 * plus exact schemas, sanitization proofs, purpose grants, and correlation fingerprints. Resource
 * Gateway therefore cannot accidentally persist raw customer data while admitting observations
 * into a governed mirror corpus.</p>
 *
 * @param schemaVersion observation wire version
 * @param observationFingerprint canonical fingerprint of the complete signed envelope
 * @param material immutable invocation facts
 * @param seal detached producer signature over the domain-separated material fingerprint
 */
public record CapabilityObservationEnvelope(
        String schemaVersion,
        String observationFingerprint,
        Material material,
        Seal seal
) {
    /** Current observation protocol version. */
    public static final String SCHEMA_VERSION = "resourceGateway.capabilityObservation.v1";
    /** Artifact kind used by exact observation references. */
    public static final String ARTIFACT_KIND = "CAPABILITY_OBSERVATION";
    /** Maximum producer delay from occurrence to signature. */
    public static final Duration MAXIMUM_ISSUANCE_DELAY = Duration.ofMinutes(15);

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}");

    /** Validates the untrusted envelope syntax without treating its signature as trusted. */
    public CapabilityObservationEnvelope {
        schemaVersion = version(schemaVersion);
        observationFingerprint = fingerprint(
                observationFingerprint, "observationFingerprint");
        material = Objects.requireNonNull(material, "material");
        seal = Objects.requireNonNull(seal, "seal");
        if (seal.signedAt().isBefore(material.occurredAt())
                || Duration.between(material.occurredAt(), seal.signedAt())
                .compareTo(MAXIMUM_ISSUANCE_DELAY) > 0) {
            throw new IllegalArgumentException(
                    "observation signature is outside its issuance window");
        }
        if (!material.dataUseGrant().activeAt(material.occurredAt())
                || !material.dataUseGrant().activeAt(seal.signedAt())) {
            throw new IllegalArgumentException(
                    "observation is outside the data-use grant window");
        }
        if (!material.request().retentionUntil().isAfter(seal.signedAt())
                || material.response() != null
                && !material.response().retentionUntil().isAfter(seal.signedAt())) {
            throw new IllegalArgumentException(
                    "observation payload references expire before admission");
        }
    }

    /**
     * Returns the immutable content address used by admissions and downstream corpus revisions.
     *
     * @return exact observation artifact reference
     */
    public MirrorArtifactRef artifactRef() {
        return new MirrorArtifactRef(
                ARTIFACT_KIND, material.observationId(), 1, observationFingerprint);
    }

    /**
     * Immutable facts supplied by a trusted observation producer.
     *
     * @param observationId globally stable idempotency identity inside the complete scope
     * @param scope complete enterprise namespace
     * @param capabilityRef exact observed capability revision
     * @param occurredAt trusted invocation occurrence time
     * @param trace distributed trace coordinates
     * @param request already-sanitized request payload reference
     * @param response already-sanitized response payload reference, or {@code null} on error
     * @param error normalized payload-free error, or {@code null} on response
     * @param latencyMillis bounded invocation duration
     * @param stateCorrelation optional payload-free state transition correlation
     * @param outcomeCorrelationRef optional exact downstream outcome correlation
     * @param dataUseGrant exact purpose and permitted-use grant
     */
    public record Material(
            String observationId,
            CapabilitySnapshot.Scope scope,
            MirrorArtifactRef capabilityRef,
            Instant occurredAt,
            TraceCoordinates trace,
            PayloadReference request,
            PayloadReference response,
            NormalizedError error,
            long latencyMillis,
            StateCorrelation stateCorrelation,
            MirrorArtifactRef outcomeCorrelationRef,
            DataUseGrant dataUseGrant
    ) {
        /** Enforces a closed payload-free success-or-error observation shape. */
        public Material {
            observationId = identifier(observationId, "observationId");
            scope = Objects.requireNonNull(scope, "scope");
            capabilityRef = exactRef(
                    capabilityRef, "CAPABILITY", "capabilityRef");
            occurredAt = time(occurredAt, "occurredAt");
            trace = Objects.requireNonNull(trace, "trace");
            request = Objects.requireNonNull(request, "request");
            if ((response == null) == (error == null)) {
                throw new IllegalArgumentException(
                        "observation requires exactly one response or normalized error");
            }
            if (latencyMillis < 0 || latencyMillis > Duration.ofDays(1).toMillis()) {
                throw new IllegalArgumentException("latencyMillis is outside the protocol bound");
            }
            if (outcomeCorrelationRef != null) {
                outcomeCorrelationRef = exactRef(
                        outcomeCorrelationRef, "OUTCOME_CORRELATION",
                        "outcomeCorrelationRef");
            }
            dataUseGrant = Objects.requireNonNull(dataUseGrant, "dataUseGrant");
        }
    }

    /**
     * Distributed trace ordering coordinates with no span attributes or business payload.
     *
     * @param traceId trace identifier
     * @param spanId invocation span identifier
     * @param sequence non-negative sequence inside the trace
     */
    public record TraceCoordinates(String traceId, String spanId, long sequence) {
        /** Validates stable trace coordinates. */
        public TraceCoordinates {
            traceId = identifier(traceId, "traceId");
            spanId = identifier(spanId, "spanId");
            if (sequence < 0) {
                throw new IllegalArgumentException("trace sequence must be non-negative");
            }
        }
    }

    /**
     * Reference to one already-sanitized and externally finalized payload.
     *
     * @param payloadRef exact sanitized payload content address
     * @param sanitizationProofRef exact field-classification and sanitization proof
     * @param schemaRef exact payload JSON Schema
     * @param sizeBytes sanitized payload size
     * @param mediaType closed payload representation
     * @param classification highest post-sanitization data classification
     * @param vaultRegion exact residency region of the referenced payload
     * @param retentionUntil exclusive payload availability bound
     */
    public record PayloadReference(
            MirrorArtifactRef payloadRef,
            MirrorArtifactRef sanitizationProofRef,
            MirrorArtifactRef schemaRef,
            long sizeBytes,
            String mediaType,
            Classification classification,
            String vaultRegion,
            Instant retentionUntil
    ) {
        /** Rejects mutable, raw, oversized, or unclassified payload coordinates. */
        public PayloadReference {
            payloadRef = exactRef(
                    payloadRef, "SANITIZED_PAYLOAD", "payloadRef");
            sanitizationProofRef = exactRef(
                    sanitizationProofRef, "PAYLOAD_SANITIZATION_PROOF",
                    "sanitizationProofRef");
            schemaRef = exactRef(schemaRef, "JSON_SCHEMA", "schemaRef");
            if (sizeBytes < 0 || sizeBytes > 64L * 1024 * 1024) {
                throw new IllegalArgumentException(
                        "sanitized payload size is outside the protocol bound");
            }
            mediaType = required(mediaType, "mediaType", 128);
            if (!"application/json".equals(mediaType)) {
                throw new IllegalArgumentException(
                        "capability observations require application/json payloads");
            }
            classification = Objects.requireNonNull(classification, "classification");
            vaultRegion = identifier(vaultRegion, "vaultRegion");
            retentionUntil = time(retentionUntil, "retentionUntil");
        }
    }

    /** Post-sanitization data classification used by admission policy. */
    public enum Classification {
        /** Data approved for public disclosure. */
        PUBLIC,
        /** Non-public operational data. */
        INTERNAL,
        /** Sensitive business data requiring controlled access. */
        CONFIDENTIAL,
        /** Highest supported class requiring explicit restricted-data policy. */
        RESTRICTED
    }

    /**
     * Normalized error facts that deliberately exclude provider messages and stack traces.
     *
     * @param errorClass closed producer-defined error class
     * @param errorCode stable provider-independent error code
     * @param retryable whether the observed failure was classified as retryable
     * @param detailsFingerprint fingerprint of separately governed normalized details
     */
    public record NormalizedError(
            String errorClass,
            String errorCode,
            boolean retryable,
            String detailsFingerprint
    ) {
        /** Enforces a bounded payload-free error representation. */
        public NormalizedError {
            errorClass = identifier(errorClass, "errorClass");
            errorCode = identifier(errorCode, "errorCode");
            detailsFingerprint = fingerprint(
                    detailsFingerprint, "detailsFingerprint");
        }
    }

    /**
     * Fingerprint-only correlation for stateful capability trajectories.
     *
     * @param entityType stable entity category, never an entity identifier
     * @param businessKeyFingerprint one-way tenant-scoped business-key token
     * @param stateBeforeFingerprint exact normalized pre-state content address
     * @param stateAfterFingerprint exact normalized post-state content address
     */
    public record StateCorrelation(
            String entityType,
            String businessKeyFingerprint,
            String stateBeforeFingerprint,
            String stateAfterFingerprint
    ) {
        /** Rejects raw business keys and mutable state coordinates. */
        public StateCorrelation {
            entityType = identifier(entityType, "entityType");
            businessKeyFingerprint = fingerprint(
                    businessKeyFingerprint, "businessKeyFingerprint");
            stateBeforeFingerprint = fingerprint(
                    stateBeforeFingerprint, "stateBeforeFingerprint");
            stateAfterFingerprint = fingerprint(
                    stateAfterFingerprint, "stateAfterFingerprint");
        }
    }

    /**
     * Exact legal and operational authorization for observation use.
     *
     * @param grantRef immutable data-use grant artifact
     * @param purpose dedicated ingest purpose
     * @param allowedUses canonically ordered permitted corpus uses
     * @param validFrom inclusive grant validity bound
     * @param expiresAt exclusive grant validity bound
     */
    public record DataUseGrant(
            MirrorArtifactRef grantRef,
            String purpose,
            List<AllowedUse> allowedUses,
            Instant validFrom,
            Instant expiresAt
    ) {
        /** Validates exact grant identity, purpose, ordering, and validity. */
        public DataUseGrant {
            grantRef = exactRef(grantRef, "DATA_USE_GRANT", "grantRef");
            purpose = required(purpose, "purpose", 128);
            if (!"MIRROR_CORPUS_INGESTION".equals(purpose)) {
                throw new IllegalArgumentException(
                        "observation grant purpose must be MIRROR_CORPUS_INGESTION");
            }
            allowedUses = orderedUses(allowedUses);
            validFrom = time(validFrom, "validFrom");
            expiresAt = time(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(validFrom)) {
                throw new IllegalArgumentException("data-use grant window is invalid");
            }
        }

        /**
         * Reports whether the grant covers one exact instant.
         *
         * @param instant instant to evaluate
         * @return true from the inclusive start until the exclusive end
         */
        public boolean activeAt(Instant instant) {
            return instant != null && !instant.isBefore(validFrom) && instant.isBefore(expiresAt);
        }
    }

    /** Closed uses that an observation grant may authorize. */
    public enum AllowedUse {
        /** Curate and quality-control the tenant's governed corpus. */
        CORPUS_CURATION,
        /** Resolve exact request fingerprints to recorded capability behavior. */
        EXACT_REPLAY,
        /** Calibrate result distributions and confidence. */
        OUTCOME_CALIBRATION,
        /** Learn multi-step state transitions for the tenant. */
        TRAJECTORY_MODELING
    }

    /**
     * Detached producer signature over canonical domain-separated material.
     *
     * @param materialFingerprint exact signed material fingerprint
     * @param algorithm fixed signature algorithm
     * @param keyId producer authority key id
     * @param issuer exact producer authority identity
     * @param signedAt signature issuance time
     * @param signature canonical base64 Ed25519 signature
     */
    public record Seal(
            String materialFingerprint,
            String algorithm,
            String keyId,
            String issuer,
            Instant signedAt,
            String signature
    ) {
        /** Validates detached signature syntax without claiming trust. */
        public Seal {
            materialFingerprint = fingerprint(
                    materialFingerprint, "materialFingerprint");
            algorithm = required(algorithm, "algorithm", 32);
            keyId = identifier(keyId, "keyId");
            issuer = identifier(issuer, "issuer");
            signedAt = time(signedAt, "signedAt");
            signature = canonicalBase64(
                    required(signature, "signature", 4_096), "signature");
            if (!"Ed25519".equals(algorithm)) {
                throw new IllegalArgumentException(
                        "capability observations require Ed25519");
            }
        }
    }

    private static List<AllowedUse> orderedUses(List<AllowedUse> values) {
        if (values == null || values.isEmpty()
                || values.size() > AllowedUse.values().length) {
            throw new IllegalArgumentException("allowedUses is invalid");
        }
        List<AllowedUse> copy = new ArrayList<>(values);
        copy.replaceAll(value -> Objects.requireNonNull(value, "allowedUse"));
        List<AllowedUse> sorted = copy.stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        if (!copy.equals(sorted)) {
            throw new IllegalArgumentException("allowedUses must use canonical order");
        }
        for (int index = 1; index < copy.size(); index++) {
            if (copy.get(index - 1) == copy.get(index)) {
                throw new IllegalArgumentException("allowedUses must be unique");
            }
        }
        return List.copyOf(copy);
    }

    private static MirrorArtifactRef exactRef(
            MirrorArtifactRef value, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(value, field);
        if (!kind.equals(exact.kind())) {
            throw new IllegalArgumentException(field + " must reference " + kind);
        }
        return exact;
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank() ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw new IllegalArgumentException("unsupported capability observation schemaVersion");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = required(value, field, 71);
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " must be a canonical SHA-256 value");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = required(value, field, 512);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException(field + " contains unsupported characters");
        }
        return exact;
    }

    private static String required(String value, String field, int maximum) {
        String exact = value == null ? "" : value.trim();
        if (exact.isEmpty() || exact.length() > maximum) {
            throw new IllegalArgumentException(field + " must be bounded and non-blank");
        }
        return exact;
    }

    private static Instant time(Instant value, String field) {
        Instant exact = Objects.requireNonNull(value, field);
        if (Instant.EPOCH.equals(exact)) {
            throw new IllegalArgumentException(field + " must not be epoch");
        }
        return exact;
    }

    private static String canonicalBase64(String value, String field) {
        try {
            byte[] decoded = Base64.getDecoder().decode(value);
            if (decoded.length == 0
                    || !value.equals(Base64.getEncoder().encodeToString(decoded))) {
                throw new IllegalArgumentException(field + " must be canonical base64");
            }
            return value;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " must be canonical base64", invalid);
        }
    }
}
