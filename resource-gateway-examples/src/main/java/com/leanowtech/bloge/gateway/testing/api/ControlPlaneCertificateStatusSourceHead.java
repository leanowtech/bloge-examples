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
 * Signed exact head of the normalized certificate-status publication log.
 *
 * <p>The attestation is deliberately separate from an immutable status publication. A source may
 * advance after publication {@code n} was created, so embedding the mutable source head into that
 * publication would either become stale or change its canonical fingerprint. Independent head
 * material lets the external status authorities attest the current log head without rewriting the
 * publication chain. Resource Gateway must verify and durably floor this object before treating a
 * reported backlog as exact.</p>
 *
 * @param schemaVersion source-head envelope protocol version
 * @param material immutable signed source-head material
 * @param materialFingerprint canonical SHA-256 fingerprint of {@code material}
 * @param signatures distinct status-authority signatures over the material fingerprint
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ControlPlaneCertificateStatusSourceHead(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<ControlPlaneCertificateStatusPublication.AuthoritySignature> signatures) {

    /** Current source-head attestation envelope version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateStatusSourceHead.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates bounded envelope structure independently from deployment trust. */
    public ControlPlaneCertificateStatusSourceHead {
        schemaVersion = normalized(schemaVersion);
        material = Objects.requireNonNull(material, "material");
        materialFingerprint = normalized(materialFingerprint);
        List<ControlPlaneCertificateStatusPublication.AuthoritySignature> supplied =
                Objects.requireNonNull(signatures, "signatures");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !fingerprint(materialFingerprint)
                || supplied.isEmpty() || supplied.size() > 32
                || supplied.stream().anyMatch(Objects::isNull)) {
            throw invalid();
        }
        Set<String> authorities = new HashSet<>();
        if (supplied.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw invalid();
        }
        signatures = List.copyOf(supplied);
    }

    /**
     * Recomputes the canonical signed-material fingerprint.
     *
     * @param objectMapper canonical JSON baseline
     * @return whether the supplied fingerprint exactly identifies {@link #material()}
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(
                Objects.requireNonNull(objectMapper, "objectMapper"), material));
    }

    /**
     * Immutable statement of the exact externally authorized publication-log head.
     *
     * @param schemaVersion signed-material protocol version
     * @param trustDomain deployment-owned certificate-status trust domain
     * @param attestationId immutable external attestation identity
     * @param deploymentScopeId exact Resource Gateway deployment scope
     * @param headSequence current source-head sequence, including a configured zero baseline
     * @param headPublicationFingerprint exact source-head publication or baseline fingerprint
     * @param policyFingerprint exact accepted normalization and head-attestation policy revision
     * @param issuedAt attestation issuance time
     * @param expiresAt exclusive hard freshness deadline
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Material(
            String schemaVersion,
            String trustDomain,
            String attestationId,
            String deploymentScopeId,
            long headSequence,
            String headPublicationFingerprint,
            String policyFingerprint,
            Instant issuedAt,
            Instant expiresAt) {

        /** Current signed source-head material version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateStatusSourceHeadMaterial.v1";

        /** Validates exact binding, bounded identity, cursor, and hard freshness shape. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            attestationId = normalized(attestationId);
            deploymentScopeId = normalized(deploymentScopeId);
            headPublicationFingerprint = normalized(headPublicationFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !identifier(trustDomain) || !identifier(attestationId)
                    || !identifier(deploymentScopeId) || headSequence < 0
                    || !fingerprint(headPublicationFingerprint)
                    || !fingerprint(policyFingerprint)
                    || !databasePrecision(issuedAt) || !databasePrecision(expiresAt)
                    || !expiresAt.isAfter(issuedAt)) {
                throw invalid();
            }
        }
    }

    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    }

    private static boolean fingerprint(String value) {
        return value != null && FINGERPRINT.matcher(value).matches();
    }

    private static boolean databasePrecision(Instant value) {
        return value.getNano() % 1_000 == 0;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "Control-plane certificate status source head is invalid");
    }
}
