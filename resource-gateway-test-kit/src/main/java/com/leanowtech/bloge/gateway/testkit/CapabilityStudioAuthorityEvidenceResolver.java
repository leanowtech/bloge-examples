package com.leanowtech.bloge.gateway.testkit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceCoordinate;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceKind;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolution;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolutionRequest;
import com.leanowtech.bloge.gateway.testkit.CapabilityStudioStageAcceptanceAuthorityVerifier.ResolvedEvidence;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Resolves strict, payload-free authority evidence envelopes for Stage Acceptance v2.
 *
 * <p>The caller-owned source retrieves an envelope by the exact request coordinate. This class
 * then bounds the document, validates its schema, and confirms that its reference identity and
 * coordinate exactly match the request. Deterministically malformed or drifting artifacts are
 * reported as not found because they are not valid artifacts at the requested coordinate;
 * transient source failures are reported as unavailable.</p>
 *
 * <p>The coordinate fingerprint identifies the externally stored primary evidence. It is not an
 * envelope fingerprint. Authenticity of the envelope facts remains the responsibility of the
 * independently configured evidence issuer policy.</p>
 */
public final class CapabilityStudioAuthorityEvidenceResolver
        implements CapabilityStudioStageAcceptanceAuthorityVerifier.EvidenceResolver {
    /** Schema version accepted by this resolver. */
    public static final String SCHEMA_VERSION =
            "resource-gateway.capability-studio.authority-evidence-envelope.v1";

    /** Maximum serialized envelope size accepted before schema parsing. */
    public static final int MAXIMUM_ENVELOPE_BYTES = 64 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ArtifactSource source;

    /**
     * Creates a resolver backed by one caller-owned artifact source.
     *
     * @param source exact-coordinate artifact source
     */
    public CapabilityStudioAuthorityEvidenceResolver(ArtifactSource source) {
        this.source = Objects.requireNonNull(source, "source is required");
    }

    /** Source-level outcomes before envelope validation. */
    public enum ArtifactStatus {
        /** The source returned an envelope candidate. */
        AVAILABLE,
        /** The exact coordinate is deterministically absent. */
        NOT_FOUND,
        /** The source could not determine whether the coordinate exists. */
        UNAVAILABLE
    }

    /**
     * Source response that defensively snapshots an envelope candidate.
     *
     * @param status source-level status
     * @param envelope envelope candidate for {@link ArtifactStatus#AVAILABLE}
     */
    public record ArtifactRead(ArtifactStatus status, JsonNode envelope) {
        /**
         * Validates and snapshots one source response.
         *
         * @param status source-level status
         * @param envelope envelope candidate when available
         */
        public ArtifactRead {
            status = Objects.requireNonNull(status, "artifact status is required");
            if (status == ArtifactStatus.AVAILABLE) {
                envelope = Objects.requireNonNull(envelope,
                        "available artifact envelope is required").deepCopy();
            } else if (envelope != null) {
                throw new IllegalArgumentException(
                        "non-available artifact read must not contain an envelope");
            }
        }

        /**
         * Creates an available source response.
         *
         * @param envelope retrieved envelope candidate
         * @return available response
         */
        public static ArtifactRead available(JsonNode envelope) {
            return new ArtifactRead(ArtifactStatus.AVAILABLE, envelope);
        }

        /**
         * Creates a deterministic not-found source response.
         *
         * @return not-found response
         */
        public static ArtifactRead notFound() {
            return new ArtifactRead(ArtifactStatus.NOT_FOUND, null);
        }

        /**
         * Creates an unavailable source response.
         *
         * @return unavailable response
         */
        public static ArtifactRead unavailable() {
            return new ArtifactRead(ArtifactStatus.UNAVAILABLE, null);
        }

        /**
         * Returns a defensive copy of the envelope candidate.
         *
         * @return copied envelope, or {@code null} when not available
         */
        @Override
        public JsonNode envelope() {
            return envelope == null ? null : envelope.deepCopy();
        }

        /**
         * Returns a redacted description that never includes the envelope.
         *
         * @return redacted source response description
         */
        @Override
        public String toString() {
            return "ArtifactRead[status=" + status + ", envelope=REDACTED]";
        }
    }

    /** Exact-coordinate storage or transport boundary supplied by the caller. */
    @FunctionalInterface
    public interface ArtifactSource {
        /**
         * Reads one envelope candidate by its exact authority request.
         *
         * @param request exact evidence or signature request
         * @return bounded source response
         */
        ArtifactRead read(ResolutionRequest request);
    }

    /**
     * Resolves and validates one authority evidence envelope.
     *
     * @param request exact evidence or signature request
     * @return available facts, deterministic not-found, or transient unavailable
     */
    @Override
    public EvidenceResolution resolve(ResolutionRequest request) {
        if (request == null) {
            return EvidenceResolution.unavailable();
        }

        ArtifactRead read;
        try {
            read = source.read(request);
        } catch (RuntimeException failure) {
            return EvidenceResolution.unavailable();
        }
        if (read == null || read.status() == ArtifactStatus.UNAVAILABLE) {
            return EvidenceResolution.unavailable();
        }
        if (read.status() == ArtifactStatus.NOT_FOUND) {
            return EvidenceResolution.notFound();
        }

        try {
            JsonNode envelope = read.envelope();
            if (!envelope.isObject()
                    || JSON.writeValueAsBytes(envelope).length > MAXIMUM_ENVELOPE_BYTES
                    || !CapabilityStudioSchemaSupport.validate(envelope,
                    CapabilityStudioSchemaSupport.AUTHORITY_EVIDENCE_ENVELOPE_V1_RESOURCE)
                    .isEmpty()
                    || !matchesRequest(envelope, request)) {
                return EvidenceResolution.notFound();
            }
            return EvidenceResolution.available(toResolvedEvidence(envelope));
        } catch (JsonProcessingException | DateTimeParseException | IllegalArgumentException failure) {
            return EvidenceResolution.notFound();
        } catch (RuntimeException failure) {
            return EvidenceResolution.unavailable();
        }
    }

    private static boolean matchesRequest(JsonNode envelope, ResolutionRequest request) {
        JsonNode coordinate = envelope.path("coordinate");
        return envelope.path("schemaVersion").asText().equals(SCHEMA_VERSION)
                && envelope.path("referenceKind").asText().equals(request.kind().name())
                && envelope.path("referenceKey").asText().equals(request.key())
                && coordinate.path("exactRef").asText().equals(request.coordinate().exactRef())
                && coordinate.path("fingerprint").asText()
                .equals(request.coordinate().fingerprint());
    }

    private static ResolvedEvidence toResolvedEvidence(JsonNode envelope) {
        JsonNode coordinate = envelope.path("coordinate");
        JsonNode bindings = envelope.path("bindings");
        JsonNode observationWindow = envelope.path("observationWindow");
        JsonNode seal = envelope.path("seal");

        Instant observedFrom = Instant.parse(observationWindow.path("from").asText());
        Instant observedThrough = Instant.parse(observationWindow.path("through").asText());
        Instant signedAt = Instant.parse(seal.path("signedAt").asText());
        Instant expiresAt = Instant.parse(seal.path("expiresAt").asText());
        if (expiresAt.isBefore(signedAt)) {
            throw new IllegalArgumentException("authority evidence seal window is invalid");
        }

        return new ResolvedEvidence(
                new EvidenceCoordinate(coordinate.path("exactRef").asText(),
                        coordinate.path("fingerprint").asText()),
                EvidenceKind.valueOf(envelope.path("evidenceKind").asText()),
                envelope.path("issuerRef").asText(),
                envelope.path("scope").asText(),
                bindings.path("candidateArtifactFingerprint").asText(),
                bindings.path("candidateIntentFingerprint").asText(),
                bindings.path("environmentFingerprint").asText(),
                observedFrom,
                observedThrough,
                bindings.path("evidenceClosureFingerprint").asText(),
                seal.path("keyId").asText(),
                seal.path("algorithm").asText(),
                seal.path("materialFingerprint").asText(),
                signedAt,
                expiresAt,
                seal.path("signature").asText());
    }
}
