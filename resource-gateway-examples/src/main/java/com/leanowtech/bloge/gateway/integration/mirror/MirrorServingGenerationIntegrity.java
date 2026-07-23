package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunEvidenceSeal;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/**
 * Canonical sealing and independent verification for serving-generation tokens.
 *
 * <p>Signatures cover a domain-separated fingerprint rather than the raw material fingerprint,
 * preventing a valid signature from another protocol from being replayed as a serving-generation
 * authority decision. Verification recomputes both content addresses, resolves a locally pinned
 * authority key, verifies Ed25519 bytes, and then applies scope, purpose, dependency, time, and
 * horizon expectations.</p>
 */
public final class MirrorServingGenerationIntegrity {
    /** Signature domain separator. */
    public static final String SIGNATURE_DOMAIN =
            "RESOURCE_GATEWAY_MIRROR_SERVING_GENERATION_V1";

    private final ObjectMapper mapper;

    /**
     * Creates a canonical token integrity boundary.
     *
     * @param mapper canonical protocol mapper
     */
    public MirrorServingGenerationIntegrity(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Seals one authority token using an external Ed25519 signer.
     *
     * <p>This method is intended for authority adapters and protocol fixtures. Resource Gateway
     * serving code consumes and verifies externally issued tokens; it must not mint a local token
     * as a substitute for an unavailable authority.</p>
     *
     * @param material immutable generation material
     * @param authorityId exact authority identity
     * @param signer external signing authority
     * @return signed, content-addressed token
     */
    public MirrorServingGenerationToken seal(
            MirrorServingGenerationToken.Material material,
            String authorityId,
            VisualEvidenceSigner signer) {
        Objects.requireNonNull(material, "material");
        VisualEvidenceSigner exactSigner =
                Objects.requireNonNull(signer, "signer");
        if (!exactSigner.available()) {
            throw new IllegalArgumentException(
                    "serving-generation signer is unavailable");
        }
        String materialFingerprint = ProtocolFingerprint.of(mapper, material);
        String signatureFingerprint =
                signatureFingerprint(materialFingerprint);
        VisualRunEvidenceSeal signed = exactSigner.seal(signatureFingerprint);
        if (signed == null || !signed.signed()
                || !signatureFingerprint.equals(signed.materialFingerprint())
                || !"Ed25519".equals(signed.algorithm())) {
            throw new IllegalArgumentException(
                    "serving-generation signer returned an invalid signature");
        }
        VisualEvidenceSigner.Verification verification =
                exactSigner.verify(signed, signatureFingerprint);
        if (verification == null || !verification.valid()) {
            throw new IllegalArgumentException(
                    "serving-generation signature failed immediate verification");
        }
        MirrorServingGenerationToken unsigned =
                new MirrorServingGenerationToken(
                        "", "", materialFingerprint, material,
                        new MirrorServingGenerationToken.Seal(
                                authorityId, signed.keyId(), signed.algorithm(),
                                signed.signedAt(), signed.signature()));
        return unsigned.withTokenFingerprint(
                ProtocolFingerprint.of(mapper, unsigned));
    }

    /**
     * Independently verifies one token and its caller-required coordinates.
     *
     * @param token untrusted signed token
     * @param trust locally pinned authority-key policy
     * @param expectation expected scope, purpose, optional dependency, and horizon
     * @param now trusted verification time
     * @return verified payload-free generation coordinates
     * @throws IllegalArgumentException when any cryptographic or semantic check fails
     */
    public VerifiedGeneration verify(
            MirrorServingGenerationToken token,
            MirrorServingGenerationTrustProvider trust,
            Expectation expectation,
            Instant now) {
        MirrorServingGenerationToken exact =
                Objects.requireNonNull(token, "token");
        MirrorServingGenerationTrustProvider trustSource =
                Objects.requireNonNull(trust, "trust");
        Expectation expected = Objects.requireNonNull(
                expectation, "expectation");
        Instant verificationTime = Objects.requireNonNull(now, "now");

        verifyContent(exact);
        String materialFingerprint = exact.materialFingerprint();
        String tokenFingerprint = exact.tokenFingerprint();
        if (!expected.scope().equals(exact.material().scope())) {
            throw new IllegalArgumentException(
                    "serving-generation scope does not match");
        }
        if (!expected.authorizedPurpose().equals(
                exact.material().authorizedPurpose())) {
            throw new IllegalArgumentException(
                    "serving-generation purpose does not match");
        }
        if (!expected.dependencyClosureFingerprint().isBlank()
                && !expected.dependencyClosureFingerprint().equals(
                exact.material().dependencyClosureFingerprint())) {
            throw new IllegalArgumentException(
                    "serving-generation dependency closure does not match");
        }
        if (verificationTime.isBefore(
                exact.material().issuedAt().minus(
                        MirrorServingGenerationToken.MAXIMUM_SIGNING_SKEW))
                || !verificationTime.isBefore(exact.material().expiresAt())) {
            throw new IllegalArgumentException(
                    "serving-generation token is outside its validity window");
        }
        if (expected.requiredUntil() != null
                && exact.material().expiresAt().isBefore(
                expected.requiredUntil())) {
            throw new IllegalArgumentException(
                    "serving-generation token does not cover the required horizon");
        }

        MirrorServingGenerationTrustProvider.Resolution resolution;
        try {
            resolution = trustSource.resolve(
                    exact.seal().authorityId(), exact.seal().keyId());
        } catch (RuntimeException failure) {
            throw new TrustUnavailableException(
                    "serving-generation trust provider is unavailable");
        }
        if (resolution == null
                || resolution.outcome()
                == MirrorServingGenerationTrustProvider.Outcome.UNAVAILABLE) {
            throw new TrustUnavailableException(
                    "serving-generation trust provider is unavailable");
        }
        if (resolution.outcome()
                == MirrorServingGenerationTrustProvider.Outcome.NOT_FOUND) {
            throw new IllegalArgumentException(
                    "serving-generation authority key is not trusted");
        }
        MirrorServingGenerationTrustProvider.AuthorityKey key =
                resolution.key();
        if (key.state()
                == MirrorServingGenerationTrustProvider.KeyState.REVOKED) {
            throw new IllegalArgumentException(
                    "serving-generation authority key is revoked");
        }
        if (exact.seal().signedAt().isBefore(key.notBefore())
                || !exact.seal().signedAt().isBefore(key.notAfter())) {
            throw new IllegalArgumentException(
                    "serving-generation signature is outside the key window");
        }
        if (!key.authorityId().equals(exact.seal().authorityId())
                || !key.keyId().equals(exact.seal().keyId())
                || !key.algorithm().equals(exact.seal().algorithm())
                || !verifySignature(
                signatureFingerprint(materialFingerprint),
                exact.seal().signature(), key.encodedPublicKey())) {
            throw new IllegalArgumentException(
                    "serving-generation signature is invalid");
        }
        return new VerifiedGeneration(
                exact.tokenFingerprint(), exact.material().streamId(),
                exact.material().generation(),
                exact.material().revocationCursor(),
                exact.material().dependencyClosureFingerprint(),
                exact.material().expiresAt(),
                exact.material().maximumStaleness());
    }

    /**
     * Recomputes both token content addresses without making a trust decision.
     *
     * <p>This is used by public plan integrity checks that must detect persisted token tampering
     * even when the online authority trust provider is intentionally unavailable. Runtime
     * admission must additionally call {@link #verify}.</p>
     *
     * @param token untrusted token
     * @throws IllegalArgumentException when material or complete content changed
     */
    public void verifyContent(MirrorServingGenerationToken token) {
        MirrorServingGenerationToken exact =
                Objects.requireNonNull(token, "token");
        String materialFingerprint =
                ProtocolFingerprint.of(mapper, exact.material());
        if (!materialFingerprint.equals(exact.materialFingerprint())) {
            throw new IllegalArgumentException(
                    "serving-generation material fingerprint is invalid");
        }
        String tokenFingerprint = ProtocolFingerprint.of(
                mapper, exact.withTokenFingerprint(""));
        if (!tokenFingerprint.equals(exact.tokenFingerprint())) {
            throw new IllegalArgumentException(
                    "serving-generation token fingerprint is invalid");
        }
    }

    private String signatureFingerprint(String materialFingerprint) {
        return ProtocolFingerprint.of(mapper, Map.of(
                "domain", SIGNATURE_DOMAIN,
                "materialFingerprint", materialFingerprint));
    }

    private static boolean verifySignature(
            String signatureFingerprint,
            String encodedSignature,
            String encodedPublicKey) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(
                            Base64.getDecoder().decode(encodedPublicKey))));
            verifier.update(signatureFingerprint.getBytes(
                    StandardCharsets.UTF_8));
            return verifier.verify(
                    Base64.getDecoder().decode(encodedSignature));
        } catch (RuntimeException | java.security.GeneralSecurityException invalid) {
            return false;
        }
    }

    /**
     * Caller expectations for one token verification.
     *
     * @param scope exact enterprise scope
     * @param authorizedPurpose exact mirror purpose
     * @param dependencyClosureFingerprint exact dependency closure, or blank for a floor comparison
     * @param requiredUntil required serving horizon, or {@code null} for a current-floor comparison
     */
    public record Expectation(
            CapabilitySnapshot.Scope scope,
            String authorizedPurpose,
            String dependencyClosureFingerprint,
            Instant requiredUntil
    ) {
        /** Normalizes optional floor-comparison fields. */
        public Expectation {
            scope = Objects.requireNonNull(scope, "scope");
            authorizedPurpose = required(
                    authorizedPurpose, "authorizedPurpose");
            dependencyClosureFingerprint =
                    dependencyClosureFingerprint == null
                            ? "" : dependencyClosureFingerprint.trim();
            if (!dependencyClosureFingerprint.isBlank()
                    && !dependencyClosureFingerprint.matches(
                    "sha256:[a-f0-9]{64}")) {
                throw new IllegalArgumentException(
                        "dependencyClosureFingerprint is invalid");
            }
        }
    }

    /**
     * Minimal payload-free result consumed by admission and telemetry.
     *
     * @param tokenFingerprint exact complete token
     * @param streamId authority stream
     * @param generation monotonic generation
     * @param revocationCursor monotonic revocation cursor
     * @param dependencyClosureFingerprint exact dependency closure
     * @param expiresAt exclusive expiry
     * @param maximumStaleness signed current-floor cache bound
     */
    public record VerifiedGeneration(
            String tokenFingerprint,
            String streamId,
            long generation,
            long revocationCursor,
            String dependencyClosureFingerprint,
            Instant expiresAt,
            Duration maximumStaleness
    ) {
    }

    /**
     * Distinguishes an operational trust-source outage from invalid signed material.
     *
     * <p>Callers must map this failure to an unavailable admission outcome. Unknown, revoked,
     * out-of-window, or cryptographically invalid keys remain invalid-token failures.</p>
     */
    public static final class TrustUnavailableException
            extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private TrustUnavailableException(String message) {
            super(message);
        }
    }

    private static String required(String value, String field) {
        String exact = value == null ? "" : value.trim();
        if (exact.isBlank() || exact.length() > 512) {
            throw new IllegalArgumentException(
                    field + " must be non-blank and bounded");
        }
        return exact;
    }
}
