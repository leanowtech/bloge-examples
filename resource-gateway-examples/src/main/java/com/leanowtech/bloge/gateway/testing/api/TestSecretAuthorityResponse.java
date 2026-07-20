package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.runtime.ResolvedTestSecrets;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Short-lived signed response from an external test-secret authority.
 *
 * <p>The detached Ed25519 signature covers {@link #materialFingerprint()}, whose canonical
 * material includes every request coordinate, decision, exact secret version, binding and value.
 * Secret-bearing material is consumed only in memory and must never enter logs, evidence, plans,
 * checkpoints or diagnostics. A denial is definitive only after the same signature checks pass.</p>
 *
 * @param schemaVersion exact authority response generation
 * @param requestId echoed authority-call identity
 * @param challenge echoed replay challenge
 * @param requestFingerprint echoed request closure fingerprint
 * @param contextFingerprint echoed exact resolution context fingerprint
 * @param decision signed authority decision
 * @param failureCode stable policy code, present only for {@link Decision#DENIED}
 * @param authorityId exact external authority identity
 * @param authorityGeneration exact policy/key generation used to resolve values
 * @param decisionId unique authority decision identity
 * @param issuedAt authority issue time
 * @param expiresAt exclusive short-lived response expiry
 * @param secrets exact secret closure, present only for {@link Decision#AUTHORIZED}
 * @param materialFingerprint canonical fingerprint of all preceding semantic fields
 * @param signature detached Ed25519 signature over {@code materialFingerprint}
 */
public record TestSecretAuthorityResponse(
        String schemaVersion,
        String requestId,
        String challenge,
        String requestFingerprint,
        String contextFingerprint,
        Decision decision,
        String failureCode,
        String authorityId,
        String authorityGeneration,
        String decisionId,
        Instant issuedAt,
        Instant expiresAt,
        Map<String, SecretMaterial> secrets,
        String materialFingerprint,
        SignatureBlock signature) {

    /** Current external authority to Resource Gateway protocol version. */
    public static final String SCHEMA_VERSION = "bloge.testSecretAuthorityResponse.v1";

    private static final int MAXIMUM_SECRET_BYTES = 1024 * 1024;
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#@-]{0,254}");
    private static final Pattern ALIAS =
            Pattern.compile("[A-Za-z_][A-Za-z0-9._:/-]{0,127}");
    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43,128}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_.-]{0,127}");

    /** Signed response outcomes. Both require complete trust verification before consumption. */
    public enum Decision {
        /** The exact requested closure is authorized and complete. */
        AUTHORIZED,
        /** Current policy denies the exact request. */
        DENIED
    }

    /** Validates bounded shape and decision-dependent fields before trust evaluation. */
    public TestSecretAuthorityResponse {
        schemaVersion = normalized(schemaVersion);
        requestId = normalized(requestId);
        challenge = normalized(challenge);
        requestFingerprint = normalized(requestFingerprint).toLowerCase(Locale.ROOT);
        contextFingerprint = normalized(contextFingerprint).toLowerCase(Locale.ROOT);
        failureCode = normalized(failureCode).toUpperCase(Locale.ROOT);
        authorityId = normalized(authorityId);
        authorityGeneration = normalized(authorityGeneration);
        decisionId = normalized(decisionId);
        materialFingerprint = normalized(materialFingerprint).toLowerCase(Locale.ROOT);
        decision = Objects.requireNonNull(decision, "decision");
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        signature = Objects.requireNonNull(signature, "signature");
        TreeMap<String, SecretMaterial> ordered = new TreeMap<>();
        long totalBytes = 0;
        for (Map.Entry<String, SecretMaterial> entry
                : (secrets == null ? Map.<String, SecretMaterial>of() : secrets).entrySet()) {
            SecretMaterial secret = Objects.requireNonNull(entry.getValue(), "secret");
            if (!normalized(entry.getKey()).equals(secret.alias())
                    || ordered.putIfAbsent(secret.alias(), secret) != null) {
                throw invalid();
            }
            totalBytes += secret.value().getBytes(StandardCharsets.UTF_8).length;
            if (totalBytes > MAXIMUM_SECRET_BYTES) {
                throw invalid();
            }
        }
        secrets = Collections.unmodifiableMap(ordered);
        boolean validDecision = decision == Decision.AUTHORIZED
                ? failureCode.isBlank() && !secrets.isEmpty() && secrets.size() <= 100
                : CODE.matcher(failureCode).matches() && secrets.isEmpty();
        if (!SCHEMA_VERSION.equals(schemaVersion) || !IDENTIFIER.matcher(requestId).matches()
                || !CHALLENGE.matcher(challenge).matches()
                || !FINGERPRINT.matcher(requestFingerprint).matches()
                || !FINGERPRINT.matcher(contextFingerprint).matches() || !validDecision
                || !IDENTIFIER.matcher(authorityId).matches()
                || !IDENTIFIER.matcher(authorityGeneration).matches()
                || !IDENTIFIER.matcher(decisionId).matches()
                || !expiresAt.isAfter(issuedAt)
                || !FINGERPRINT.matcher(materialFingerprint).matches()) {
            throw invalid();
        }
    }

    /**
     * Recomputes the secret-bearing canonical material fingerprint before signature verification.
     *
     * @param objectMapper canonical protocol mapper
     * @return true only when every semantic response field is covered
     */
    public boolean fingerprintVerified(ObjectMapper objectMapper) {
        return materialFingerprint.equals(ProtocolFingerprint.ofBounded(
                Objects.requireNonNull(objectMapper, "objectMapper"), material(), 2 * 1024 * 1024));
    }

    /** @return canonical response material covered by the detached signature */
    public Material material() {
        return new Material(schemaVersion, requestId, challenge, requestFingerprint,
                contextFingerprint, decision, failureCode, authorityId, authorityGeneration,
                decisionId, issuedAt, expiresAt, secrets);
    }

    /**
     * Converts a verified authorized wire response to a run-scoped runtime closure.
     *
     * <p>The caller must verify the detached signature and exact request binding first. The normal
     * {@link TestSecretResolutionService} then independently verifies every returned binding.</p>
     *
     * @return runtime-only resolved closure
     */
    public ResolvedTestSecrets toResolvedSecrets() {
        if (decision != Decision.AUTHORIZED) {
            throw new IllegalStateException("A denied test-secret response has no value closure");
        }
        TreeMap<String, ResolvedTestSecrets.Secret> resolved = new TreeMap<>();
        secrets.forEach((alias, secret) -> resolved.put(alias, new ResolvedTestSecrets.Secret(
                secret.alias(), secret.reference(), secret.version(),
                secret.bindingFingerprint(), secret.value())));
        return new ResolvedTestSecrets(ResolvedTestSecrets.SCHEMA_VERSION, contextFingerprint,
                authorityId, authorityGeneration, issuedAt, expiresAt, resolved);
    }

    /** Canonical response material independently reproducible by the external authority. */
    public record Material(
            String schemaVersion,
            String requestId,
            String challenge,
            String requestFingerprint,
            String contextFingerprint,
            Decision decision,
            String failureCode,
            String authorityId,
            String authorityGeneration,
            String decisionId,
            Instant issuedAt,
            Instant expiresAt,
            Map<String, SecretMaterial> secrets) {
    }

    /**
     * One exact resolved wire dependency.
     *
     * @param alias requested runtime alias
     * @param reference exact opaque reference supplied by the fixture
     * @param version immutable resolved provider version
     * @param bindingFingerprint context/authority/reference/version binding
     * @param value runtime-only plaintext value
     */
    public record SecretMaterial(
            String alias,
            String reference,
            String version,
            String bindingFingerprint,
            String value) {

        /** Validates one bounded dependency without echoing supplied material in errors. */
        public SecretMaterial {
            alias = normalized(alias);
            reference = normalized(reference);
            version = normalized(version);
            bindingFingerprint = normalized(bindingFingerprint).toLowerCase(Locale.ROOT);
            if (!ALIAS.matcher(alias).matches() || !validReference(reference)
                    || !IDENTIFIER.matcher(version).matches()
                    || !FINGERPRINT.matcher(bindingFingerprint).matches()
                    || value == null || value.length() > 65_536) {
                throw invalid();
            }
        }
    }

    /**
     * Rotation-aware detached Ed25519 signature.
     *
     * @param keyId configured verification-key identity
     * @param algorithm exact signature algorithm
     * @param signature base64-encoded 64-byte Ed25519 signature
     */
    public record SignatureBlock(String keyId, String algorithm, String signature) {

        /** Rejects ambiguous algorithms and malformed signature bytes. */
        public SignatureBlock {
            keyId = normalized(keyId);
            algorithm = normalized(algorithm);
            signature = normalized(signature);
            try {
                if (!IDENTIFIER.matcher(keyId).matches() || !"Ed25519".equals(algorithm)
                        || Base64.getDecoder().decode(signature).length != 64) {
                    throw invalid();
                }
            } catch (IllegalArgumentException malformed) {
                throw new IllegalArgumentException(
                        "Invalid test-secret authority response", malformed);
            }
        }
    }

    private static boolean validReference(String reference) {
        if (reference.isBlank() || reference.length() > 1_024) {
            return false;
        }
        try {
            URI uri = new URI(reference);
            String scheme = uri.getScheme() == null
                    ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return uri.isAbsolute() && !uri.isOpaque() && !scheme.isBlank()
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank()
                    && !Set.of("data", "file", "http", "javascript").contains(scheme)
                    && uri.getRawUserInfo() == null && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
        } catch (URISyntaxException malformed) {
            return false;
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid test-secret authority response");
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
