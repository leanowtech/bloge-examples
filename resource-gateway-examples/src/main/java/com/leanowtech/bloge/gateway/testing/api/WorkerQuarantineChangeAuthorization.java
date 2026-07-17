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
 * Detached enterprise change authorization for one exact worker-quarantine mutation.
 *
 * <p>The envelope carries no ticket text, tenant id, actor id, claim token, credential, or business
 * payload. An external governance system signs opaque scope and subject fingerprints instead. The
 * Resource Gateway must verify the envelope through an independently configured trust store before
 * treating it as authorization.</p>
 *
 * @param schemaVersion authorization envelope protocol version
 * @param material immutable authorization facts signed by external authorities
 * @param materialFingerprint canonical SHA-256 fingerprint of {@code material}
 * @param signatures distinct external-authority signatures over the material fingerprint
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record WorkerQuarantineChangeAuthorization(
        String schemaVersion,
        Material material,
        String materialFingerprint,
        List<AuthoritySignature> signatures) {

    /** Current external change-authorization envelope protocol version. */
    public static final String SCHEMA_VERSION =
            "bloge.workerQuarantineChangeAuthorization.v1";

    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    /** Validates the bounded envelope independently from trust-policy verification. */
    public WorkerQuarantineChangeAuthorization {
        schemaVersion = normalized(schemaVersion);
        material = Objects.requireNonNull(material, "material");
        materialFingerprint = normalized(materialFingerprint);
        List<AuthoritySignature> supplied = Objects.requireNonNull(signatures, "signatures");
        if (!SCHEMA_VERSION.equals(schemaVersion)
                || !FINGERPRINT.matcher(materialFingerprint).matches()
                || supplied.isEmpty() || supplied.size() > 32
                || supplied.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Worker quarantine change-authorization envelope is invalid");
        }
        Set<String> authorities = new HashSet<>();
        if (supplied.stream().anyMatch(signature ->
                !authorities.add(signature.authorityId()))) {
            throw new IllegalArgumentException(
                    "Worker quarantine change authorization repeats an authority");
        }
        signatures = List.copyOf(supplied);
    }

    /**
     * Recomputes the canonical material fingerprint.
     *
     * @param objectMapper canonical JSON baseline
     * @return whether the supplied material fingerprint is exact
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.of(objectMapper, material));
    }

    /**
     * Payload-free immutable facts approved by the external governance system.
     *
     * @param schemaVersion signed-material protocol version
     * @param trustDomain deployment-owned governance trust domain
     * @param authorizationId immutable external work-order or approval identity
     * @param action closed mutation action, currently {@code WORKER_QUARANTINE_DISCARD}
     * @param scopeFingerprint identity-derived Resource Gateway scope fingerprint
     * @param subjectFingerprint exact quarantine claim, reason, and mutation fingerprint
     * @param policyFingerprint exact external approval-policy revision fingerprint
     * @param issuedAt external authorization issuance time
     * @param notBefore inclusive authorization activation time
     * @param expiresAt exclusive authorization expiry time
     */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Material(
            String schemaVersion,
            String trustDomain,
            String authorizationId,
            String action,
            String scopeFingerprint,
            String subjectFingerprint,
            String policyFingerprint,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {

        /** Current signed-material protocol version. */
        public static final String SCHEMA_VERSION =
                "bloge.workerQuarantineChangeAuthorizationMaterial.v1";

        /** Only destructive action authorized by this protocol revision. */
        public static final String DISCARD_ACTION = "WORKER_QUARANTINE_DISCARD";

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");

        /** Rejects ambiguous identities, unsupported actions, and impossible time windows. */
        public Material {
            schemaVersion = normalized(schemaVersion);
            trustDomain = normalized(trustDomain);
            authorizationId = normalized(authorizationId);
            action = normalized(action);
            scopeFingerprint = normalized(scopeFingerprint);
            subjectFingerprint = normalized(subjectFingerprint);
            policyFingerprint = normalized(policyFingerprint);
            issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
            notBefore = Objects.requireNonNull(notBefore, "notBefore");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (!SCHEMA_VERSION.equals(schemaVersion)
                    || !IDENTIFIER.matcher(trustDomain).matches()
                    || !IDENTIFIER.matcher(authorizationId).matches()
                    || !DISCARD_ACTION.equals(action)
                    || !FINGERPRINT.matcher(scopeFingerprint).matches()
                    || !FINGERPRINT.matcher(subjectFingerprint).matches()
                    || !FINGERPRINT.matcher(policyFingerprint).matches()
                    || !hasDatabasePrecision(issuedAt)
                    || !hasDatabasePrecision(notBefore)
                    || !hasDatabasePrecision(expiresAt)
                    || !expiresAt.isAfter(issuedAt) || !expiresAt.isAfter(notBefore)) {
                throw new IllegalArgumentException(
                        "Worker quarantine change-authorization material is invalid");
            }
        }

        private static boolean hasDatabasePrecision(Instant instant) {
            return instant.getNano() % 1_000 == 0;
        }
    }

    /**
     * One detached Ed25519 authority signature.
     *
     * @param authorityId stable external governance authority identity
     * @param keyId stable verification-key identity within that authority
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

        /** Validates bounded authority identity and exact signature encoding. */
        public AuthoritySignature {
            authorityId = normalized(authorityId);
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signedAt = Objects.requireNonNull(signedAt, "signedAt");
            signature = normalized(signature);
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || !IDENTIFIER.matcher(keyId).matches()
                    || !"Ed25519".equals(algorithm) || !validSignature(signature)) {
                throw new IllegalArgumentException(
                        "Worker quarantine change-authorization signature is invalid");
            }
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
}
