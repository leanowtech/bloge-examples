package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Externally attested, content-addressed page from one production outcome source stream.
 *
 * <p>The page is a transport and continuity proof, not a second Outcome authority. Every entry
 * carries one payload-free {@link AuthoritativeOutcomeObservation} candidate that must still pass
 * the existing observation authority and Resource Gateway signing boundary before inbox
 * admission. Live and backfill streams have independent chains, so a historical repair can never
 * rewind the live cursor.</p>
 */
public record AuthoritativeOutcomeSourcePage(
        String schemaVersion,
        String pageFingerprint,
        CapabilitySnapshot.Scope scope,
        String connectorId,
        long connectorGeneration,
        StreamKind streamKind,
        String streamId,
        MirrorArtifactRef controlCommandRef,
        long sequence,
        String previousPageFingerprint,
        MirrorArtifactRef previousCursorRef,
        MirrorArtifactRef nextCursorRef,
        SourceWatermark watermark,
        Instant producedAt,
        List<Entry> entries,
        VisualRunEvidenceSeal sourceSeal
) {
    /** Current production outcome source-page wire version. */
    public static final String SCHEMA_VERSION =
            "resourceGateway.authoritativeOutcomeSourcePage.v1";
    /** Artifact kind used by backfill command references. */
    public static final String CONTROL_COMMAND_KIND =
            "AUTHORITATIVE_OUTCOME_CONNECTOR_CONTROL_COMMAND";
    /** Artifact kind used for opaque, deployment-resolved cursor positions. */
    public static final String CURSOR_KIND =
            "AUTHORITATIVE_OUTCOME_SOURCE_CURSOR";
    /** Artifact kind used for source stream watermarks. */
    public static final String WATERMARK_KIND =
            "AUTHORITATIVE_OUTCOME_SOURCE_WATERMARK";
    /** Maximum mutations admitted in one source page. */
    public static final int MAXIMUM_ENTRIES = 500;
    /** Maximum canonical page bytes admitted to content addressing. */
    public static final int MAXIMUM_CANONICAL_BYTES = 16 * 1024 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9@._:/#-]{0,511}");
    private static final Pattern FINGERPRINT =
            Pattern.compile("sha256:[a-f0-9]{64}");

    /** Enforces stream, chain, cursor, watermark, and mutation closure. */
    public AuthoritativeOutcomeSourcePage {
        schemaVersion = version(schemaVersion);
        pageFingerprint = optionalFingerprint(
                pageFingerprint, "pageFingerprint");
        scope = Objects.requireNonNull(scope, "scope");
        connectorId = identifier(connectorId, "connectorId");
        if (connectorGeneration < 1 || sequence < 1) {
            throw invalid("connector generation and page sequence must be positive");
        }
        streamKind = Objects.requireNonNull(streamKind, "streamKind");
        streamId = identifier(streamId, "streamId");
        if (streamKind == StreamKind.LIVE) {
            if (!"live".equals(streamId) || controlCommandRef != null) {
                throw invalid("live stream must use the fixed id and no control command");
            }
        } else {
            controlCommandRef = requireKind(
                    controlCommandRef, CONTROL_COMMAND_KIND, "controlCommandRef");
        }
        previousPageFingerprint = fingerprint(
                previousPageFingerprint, "previousPageFingerprint");
        previousCursorRef = requireKind(
                previousCursorRef, CURSOR_KIND, "previousCursorRef");
        nextCursorRef = requireKind(
                nextCursorRef, CURSOR_KIND, "nextCursorRef");
        if (previousCursorRef.equals(nextCursorRef)) {
            throw invalid("source page must advance its opaque cursor reference");
        }
        watermark = Objects.requireNonNull(watermark, "watermark");
        producedAt = Objects.requireNonNull(producedAt, "producedAt");
        if (watermark.publishedAt().isAfter(producedAt)) {
            throw invalid("source watermark cannot be published after its page");
        }
        entries = entries == null ? List.of() : List.copyOf(entries);
        if (entries.isEmpty() || entries.size() > MAXIMUM_ENTRIES) {
            throw invalid("source page entry count is outside the bounded range");
        }
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = Objects.requireNonNull(entries.get(index), "entry");
            if (entry.ordinal() != index + 1) {
                throw invalid("source page mutation ordinals must be contiguous");
            }
            AuthoritativeOutcomeObservation observation = entry.observation();
            if (!scope.equals(observation.scope())
                    || observation.observationFingerprint().isBlank()
                    || observation.observationSeal().signed()) {
                throw invalid("source mutation must carry an addressed unsigned observation in scope");
            }
        }
        sourceSeal = sourceSeal == null
                ? VisualRunEvidenceSeal.unsigned() : sourceSeal;
    }

    /** Separate cursor chains admitted by the connector control plane. */
    public enum StreamKind {
        LIVE,
        BACKFILL
    }

    /** Source mutation semantics. */
    public enum Operation {
        UPSERT,
        REVOKE
    }

    /**
     * One exact observation successor carried by the source stream.
     *
     * @param ordinal one-based contiguous page position
     * @param operation append or externally authorized removal/correction
     * @param expectedPredecessorFingerprint blank for revision one, exact inbox head otherwise
     * @param affectedSourceRef exact source record being revoked, absent for an upsert
     * @param observation addressed but Resource Gateway-unsigned observation candidate
     */
    public record Entry(
            int ordinal,
            Operation operation,
            String expectedPredecessorFingerprint,
            MirrorArtifactRef affectedSourceRef,
            AuthoritativeOutcomeObservation observation
    ) {
        /** Enforces exact predecessor and revoke-source semantics. */
        public Entry {
            if (ordinal < 1 || ordinal > MAXIMUM_ENTRIES) {
                throw invalid("source mutation ordinal is outside the bounded range");
            }
            operation = Objects.requireNonNull(operation, "operation");
            observation = Objects.requireNonNull(observation, "observation");
            if (observation.observationFingerprint().isBlank()
                    || observation.observationSeal().signed()) {
                throw invalid(
                        "source mutation must carry an addressed unsigned observation");
            }
            expectedPredecessorFingerprint = optionalFingerprint(
                    expectedPredecessorFingerprint,
                    "expectedPredecessorFingerprint");
            boolean first = observation.revision() == 1;
            if (first != expectedPredecessorFingerprint.isBlank()) {
                throw invalid("source mutation predecessor does not match observation revision");
            }
            if (operation == Operation.REVOKE) {
                affectedSourceRef = requireKind(
                        affectedSourceRef,
                        "AUTHORITATIVE_OUTCOME_SOURCE_RECORD",
                        "affectedSourceRef");
                if (first) {
                    throw invalid("a source record cannot be revoked in observation revision one");
                }
            } else if (affectedSourceRef != null) {
                throw invalid("an upsert mutation cannot claim a revoked source record");
            }
        }
    }

    /**
     * Source-published event-time progress for one page chain.
     *
     * @param watermarkRef exact source watermark artifact
     * @param eventTimeThrough inclusive event-time coverage
     * @param publishedAt source publication time
     */
    public record SourceWatermark(
            MirrorArtifactRef watermarkRef,
            Instant eventTimeThrough,
            Instant publishedAt
    ) {
        /** Enforces a non-future event-time watermark. */
        public SourceWatermark {
            watermarkRef = requireKind(
                    watermarkRef, WATERMARK_KIND, "watermarkRef");
            eventTimeThrough = Objects.requireNonNull(
                    eventTimeThrough, "eventTimeThrough");
            publishedAt = Objects.requireNonNull(publishedAt, "publishedAt");
            if (eventTimeThrough.isAfter(publishedAt)) {
                throw invalid("source watermark cannot cover unpublished future event time");
            }
        }
    }

    /** Seals canonical page material with its content address, preserving an external seal. */
    public AuthoritativeOutcomeSourcePage seal(ObjectMapper mapper) {
        AuthoritativeOutcomeSourcePage material =
                withFingerprintAndSeal("", VisualRunEvidenceSeal.unsigned());
        return withFingerprintAndSeal(
                ProtocolFingerprint.ofBounded(
                        Objects.requireNonNull(mapper, "mapper"),
                        material,
                        MAXIMUM_CANONICAL_BYTES),
                sourceSeal);
    }

    /** Recomputes page structure and canonical content address. */
    public void verify(ObjectMapper mapper) {
        if (pageFingerprint.isBlank()
                || !pageFingerprint.equals(seal(mapper).pageFingerprint())) {
            throw invalid("authoritative outcome source page fingerprint mismatch");
        }
    }

    /** Returns identical page material with an externally produced detached seal. */
    public AuthoritativeOutcomeSourcePage withSourceSeal(
            VisualRunEvidenceSeal seal) {
        return withFingerprintAndSeal(
                pageFingerprint,
                Objects.requireNonNull(seal, "seal"));
    }

    private AuthoritativeOutcomeSourcePage withFingerprintAndSeal(
            String fingerprint,
            VisualRunEvidenceSeal seal) {
        return new AuthoritativeOutcomeSourcePage(
                schemaVersion, fingerprint, scope, connectorId,
                connectorGeneration, streamKind, streamId, controlCommandRef,
                sequence, previousPageFingerprint, previousCursorRef, nextCursorRef,
                watermark, producedAt, entries, seal);
    }

    private static String version(String value) {
        String exact = value == null || value.isBlank()
                ? SCHEMA_VERSION : value.trim();
        if (!SCHEMA_VERSION.equals(exact)) {
            throw invalid("unsupported authoritative outcome source page schemaVersion");
        }
        return exact;
    }

    private static String identifier(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static String fingerprint(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!FINGERPRINT.matcher(exact).matches()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static String optionalFingerprint(String value, String field) {
        String exact = Objects.requireNonNullElse(value, "").trim();
        if (!exact.isBlank() && !FINGERPRINT.matcher(exact).matches()) {
            throw invalid(field + " is invalid");
        }
        return exact;
    }

    private static MirrorArtifactRef requireKind(
            MirrorArtifactRef ref, String kind, String field) {
        MirrorArtifactRef exact = Objects.requireNonNull(ref, field);
        if (!kind.equals(exact.kind())) {
            throw invalid(field + " must reference " + kind);
        }
        return exact;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
