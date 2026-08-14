package com.leanowtech.bloge.gateway.visual.runtime;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/** Stable Ed25519 signer for committed protocol fixtures only. */
public final class DeterministicVisualEvidenceSigner implements VisualEvidenceSigner {
    private static final String ALGORITHM = "Ed25519";
    private static final String KEY_ID = "fixture-ed25519:package-governance-v1";
    private static final Instant CREATED_AT = Instant.parse("2026-08-14T17:00:00Z");

    private final KeyPair keyPair;
    private final Clock clock;

    /** Creates the stable fixture signer at the caller-controlled signing time. */
    public DeterministicVisualEvidenceSigner(Clock clock) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        try {
            SecureRandom random = SecureRandom.getInstance("SHA1PRNG");
            random.setSeed("resource-gateway-package-governance-fixture-v1"
                    .getBytes(StandardCharsets.UTF_8));
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(new NamedParameterSpec(ALGORITHM), random);
            this.keyPair = generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to create deterministic fixture signer",
                    exception);
        }
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(keyPair.getPrivate());
            signer.update(materialFingerprint.getBytes(StandardCharsets.UTF_8));
            return new VisualRunEvidenceSeal("", materialFingerprint, ALGORITHM, KEY_ID,
                    clock.instant(), Base64.getEncoder().encodeToString(signer.sign()));
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign fixture material", exception);
        }
    }

    @Override
    public Verification verify(
            VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        if (seal == null || !seal.signed()
                || !KEY_ID.equals(seal.keyId())
                || !actualMaterialFingerprint.equals(seal.materialFingerprint())) {
            return new Verification(false, "INVALID", "fixture seal mismatch");
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(keyPair.getPublic());
            verifier.update(actualMaterialFingerprint.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(seal.signature()))
                    ? new Verification(true, "VERIFIED", "")
                    : new Verification(false, "INVALID", "fixture signature mismatch");
        } catch (java.security.GeneralSecurityException | IllegalArgumentException exception) {
            return new Verification(false, "INVALID", "fixture signature cannot be verified");
        }
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        if (!KEY_ID.equals(keyId)) {
            return Optional.empty();
        }
        return Optional.of(new VerificationKey("", KEY_ID, ALGORITHM,
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                CREATED_AT, "ACTIVE", "FIXTURE"));
    }

    @Override
    public boolean available() {
        return true;
    }
}
