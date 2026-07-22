package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One fingerprint-chained delivery page of independently signed certificate-rotation events.
 *
 * <p>The page fingerprint protects ordering and exact replay between an authenticated event source
 * and one Resource Gateway replica. It is not an authorization signature: every contained event
 * still passes its independent M-of-N authority verification before it can change a durable
 * certificate floor. A source therefore cannot turn transport authentication into rotation
 * authority.</p>
 *
 * @param schemaVersion page envelope protocol version
 * @param material immutable page material identified by {@code pageFingerprint}
 * @param pageFingerprint canonical SHA-256 fingerprint of {@code material}
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ControlPlaneCertificateRotationEventPage(
        String schemaVersion,
        Material material,
        String pageFingerprint) {

    /** Current event-page envelope protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateRotationEventPage.v1";
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Rejects incomplete page envelopes before any cursor or runtime mutation. */
    public ControlPlaneCertificateRotationEventPage {
        schemaVersion = normalized(schemaVersion);
        material = Objects.requireNonNull(material, "material");
        pageFingerprint = normalized(pageFingerprint);
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(pageFingerprint).matches()) {
            throw invalid();
        }
    }

    /**
     * Recomputes the canonical page material fingerprint.
     *
     * @param objectMapper canonical protocol mapper
     * @return whether the supplied page fingerprint exactly identifies the material
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return pageFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material));
    }

    /**
     * Immutable page-chain facts returned by the authenticated event source.
     *
     * @param schemaVersion page material protocol version
     * @param deploymentScopeId exact signed-event deployment scope
     * @param sequence contiguous page sequence beginning after the configured durable baseline
     * @param previousPageFingerprint exact fingerprint of the replica's committed cursor head
     * @param issuedAt authenticated source publication time
     * @param expiresAt exclusive page acceptance deadline
     * @param events one through fifteen independently signed events for distinct product targets
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Material(
            String schemaVersion,
            String deploymentScopeId,
            long sequence,
            String previousPageFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            List<ControlPlaneCertificateRotationEvent> events) {

        /** Current event-page material protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationEventPageMaterial.v1";
        /** Maximum events in one page, equal to the closed product target inventory. */
        public static final int MAXIMUM_EVENTS = 15;
        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects page gaps, ambiguous target ordering, and impossible validity windows. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            deploymentScopeId = normalized(deploymentScopeId);
            previousPageFingerprint = normalized(previousPageFingerprint);
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            List<ControlPlaneCertificateRotationEvent> supplied =
                    Objects.requireNonNull(events, "events");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(deploymentScopeId).matches()
                    || sequence < 1
                    || !FINGERPRINT.matcher(previousPageFingerprint).matches()
                    || !databasePrecision(issuedAt) || !databasePrecision(expiresAt)
                    || !expiresAt.isAfter(issuedAt)
                    || supplied.isEmpty() || supplied.size() > MAXIMUM_EVENTS
                    || supplied.stream().anyMatch(Objects::isNull)) {
                throw invalid();
            }
            Set<String> targets = new HashSet<>();
            for (ControlPlaneCertificateRotationEvent event : supplied) {
                if (!deploymentScopeId.equals(event.material().deploymentScopeId())
                        || !targets.add(event.material().targetId())) {
                    throw invalid();
                }
            }
            events = List.copyOf(supplied);
        }

        private static boolean databasePrecision(Instant value) {
            return value.getNano() % 1_000 == 0;
        }
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate rotation event page is invalid");
    }
}
