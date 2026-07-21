package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;

import java.time.Instant;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Signed, payload-free command to rotate one exact control-plane TLS target.
 *
 * <p>The event binds a contiguous successor to its deployment scope, target identity, active
 * material predecessor, activation window, governed policy, and resolved settings fingerprint. It
 * carries only a safe material lookup identifier; keystore paths, certificate bytes, private keys,
 * passwords, and secret references remain behind the deployment-owned material source.</p>
 *
 * @param schemaVersion event envelope protocol version
 * @param material immutable rotation facts signed by external authorities
 * @param materialFingerprint canonical SHA-256 fingerprint of {@code material}
 * @param signatures distinct external-authority signatures over the material fingerprint
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record ControlPlaneCertificateRotationEvent(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<AuthoritySignature> signatures) {

    /** Current rotation-event envelope protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.controlPlaneCertificateRotationEvent.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates bounded envelope structure independently from deployment trust. */
    public ControlPlaneCertificateRotationEvent {
        schemaVersion = normalized(schemaVersion);
        material = Objects.requireNonNull(material, "material");
        materialFingerprint = normalized(materialFingerprint);
        List<AuthoritySignature> supplied = Objects.requireNonNull(signatures, "signatures");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(materialFingerprint).matches()
                || supplied.isEmpty() || supplied.size() > 32
                || supplied.stream().anyMatch(Objects::isNull)) {
            throw invalid("Control-plane certificate rotation event is invalid");
        }
        Set<String> authorities = new HashSet<>();
        if (supplied.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw invalid("Control-plane certificate rotation event repeats an authority");
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
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Immutable facts authorizing one contiguous certificate generation.
     *
     * @param schemaVersion signed-material protocol version
     * @param trustDomain deployment-owned certificate-rotation trust domain
     * @param eventId immutable external change identity
     * @param deploymentScopeId exact Resource Gateway deployment scope
     * @param targetId exact independently governed control-plane target
     * @param generation contiguous successor generation, beginning at two
     * @param previousMaterialFingerprint exact active predecessor settings fingerprint
     * @param materialId safe deployment-owned lookup identifier, never a path or secret reference
     * @param settingsFingerprint exact fingerprint returned by the material source
     * @param policyFingerprint exact accepted certificate-rotation policy revision
     * @param issuedAt external event issuance time
     * @param notBefore inclusive event acceptance time
     * @param activateAt deterministic cross-replica activation time
     * @param expiresAt exclusive event acceptance and activation deadline
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Material(
            String schemaVersion,
            String trustDomain,
            String eventId,
            String deploymentScopeId,
            String targetId,
            long generation,
            String previousMaterialFingerprint,
            String materialId,
            String settingsFingerprint,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant activateAt,
            Instant expiresAt) {

        /** Current signed-material protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.controlPlaneCertificateRotationEventMaterial.v1";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern MATERIAL_ID =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,254}");

        /** Rejects ambiguous identities, non-successors, and impossible time windows. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            eventId = normalized(eventId);
            deploymentScopeId = normalized(deploymentScopeId);
            targetId = normalized(targetId);
            previousMaterialFingerprint = normalized(previousMaterialFingerprint);
            materialId = normalized(materialId);
            settingsFingerprint = normalized(settingsFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            activateAt = Objects.requireNonNull(activateAt, "activateAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !identifier(trustDomain) || !identifier(eventId)
                    || !identifier(deploymentScopeId) || !identifier(targetId)
                    || generation < 2 || !FINGERPRINT.matcher(
                    previousMaterialFingerprint).matches()
                    || !MATERIAL_ID.matcher(materialId).matches()
                    || !FINGERPRINT.matcher(settingsFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !databasePrecision(issuedAt) || !databasePrecision(notBefore)
                    || !databasePrecision(activateAt) || !databasePrecision(expiresAt)
                    || notBefore.isBefore(issuedAt) || activateAt.isBefore(notBefore)
                    || !expiresAt.isAfter(activateAt)) {
                throw invalid("Control-plane certificate rotation event material is invalid");
            }
        }

        private static boolean identifier(String value) {
            return IDENTIFIER.matcher(value).matches();
        }

        private static boolean databasePrecision(Instant value) {
            return value.getNano() % 1_000 == 0;
        }
    }

    /**
     * One detached Ed25519 rotation-authority signature.
     *
     * @param authorityId stable external rotation authority identity
     * @param keyId rotation-aware verification-key identity within the authority
     * @param algorithm signature algorithm, fixed to Ed25519
     * @param signedAt signature creation time
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthoritySignature(
            String authorityId,
            String keyId,
            String algorithm,
            Instant signedAt,
            String signature) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Validates bounded identity, algorithm, time precision, and signature encoding. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signedAt = Objects.requireNonNull(signedAt, "signedAt");
            signature = normalized(signature);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !"Ed25519".equals(algorithm) || !databasePrecision(signedAt)
                    || !validSignature(signature)) {
                throw invalid("Control-plane certificate rotation signature is invalid");
            }
        }

        private static boolean databasePrecision(Instant value) {
            return value.getNano() % 1_000 == 0;
        }

        private static boolean validSignature(String encoded) {
            if (encoded.isBlank() || encoded.length() > 128) {
                return false;
            }
            try {
                return Base64.getDecoder().decode(encoded).length == 64;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
