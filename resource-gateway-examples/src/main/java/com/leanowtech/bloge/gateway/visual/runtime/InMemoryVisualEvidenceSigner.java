package com.leanowtech.bloge.gateway.visual.runtime;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** In-memory Ed25519 signing authority for focused tests and embedded use. */
public final class InMemoryVisualEvidenceSigner implements VisualEvidenceSigner {
    private final VisualEvidenceSigner delegate;

    /** Creates an ephemeral signer using the system UTC clock. */
    public InMemoryVisualEvidenceSigner() {
        this.delegate = create(Clock.systemUTC());
    }

    /**
     * Creates an ephemeral signer with a caller-controlled signing clock.
     *
     * <p>The overload is useful for deterministic protocol and replay tests. Production signing
     * authorities should use managed key custody and a trusted clock rather than this in-memory
     * implementation.</p>
     *
     * @param clock clock used for key creation and signature timestamps
     * @return deterministic ephemeral signer
     */
    public static InMemoryVisualEvidenceSigner usingClock(Clock clock) {
        return new InMemoryVisualEvidenceSigner(
                java.util.Objects.requireNonNull(clock, "clock"));
    }

    private InMemoryVisualEvidenceSigner(Clock clock) {
        this.delegate = create(java.util.Objects.requireNonNull(clock, "clock"));
    }

    private static VisualEvidenceSigner create(Clock clock) {
        try {
            KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            Instant createdAt = clock.instant();
            return new Ed25519VisualEvidenceSigner(keyPair,
                    "memory-ed25519:" + UUID.randomUUID(), createdAt,
                    "IN_MEMORY", Map.of(), clock);
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
