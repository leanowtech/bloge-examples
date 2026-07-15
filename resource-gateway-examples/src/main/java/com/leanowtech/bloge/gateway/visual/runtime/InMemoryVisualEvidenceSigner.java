package com.leanowtech.bloge.gateway.visual.runtime;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory Ed25519 signing authority for focused tests and embedded use. */
public final class InMemoryVisualEvidenceSigner implements VisualEvidenceSigner {
    private final VisualEvidenceSigner delegate;

    public InMemoryVisualEvidenceSigner() {
        this.delegate = create();
    }

    private static VisualEvidenceSigner create() {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return new Ed25519VisualEvidenceSigner(keyPair, "memory-ed25519:" + UUID.randomUUID(), Instant.now(),
                    "IN_MEMORY", Map.of());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to initialize in-memory evidence signer", exception);
        }
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        return delegate.seal(materialFingerprint);
    }

    @Override
    public Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        return delegate.verify(seal, actualMaterialFingerprint);
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        return delegate.key(keyId);
    }

    @Override
    public KeySetResolution resolveKeySet() {
        return delegate.resolveKeySet();
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public Descriptor descriptor() {
        Ed25519VisualEvidenceSigner signer = (Ed25519VisualEvidenceSigner) delegate;
        KeySetResolution keySet = signer.resolveKeySet();
        return new Descriptor("", "LOCAL_MEMORY", "IN_MEMORY", true, "HEALTHY",
                signer.activeKey().keyId(), false, true, signer.verificationKeyCount(),
                signer.activeKey().createdAt(), null, 0, 0,
                Map.of("productionReady", false,
                        "keySetPolicyAvailable", true,
                        "keySetPolicyCompleteness", keySet.keySet().policyCompleteness().name()));
    }
}
