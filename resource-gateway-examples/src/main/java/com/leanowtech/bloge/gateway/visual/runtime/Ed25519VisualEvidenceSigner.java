package com.leanowtech.bloge.gateway.visual.runtime;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Ed25519 implementation shared by in-memory and persistent signing authorities.
 */
final class Ed25519VisualEvidenceSigner implements VisualEvidenceSigner {

    private static final String ALGORITHM = "Ed25519";
    private static final Instant POLICY_HORIZON = Instant.parse("9999-12-31T23:59:59Z");

    private final KeyPair activeKeyPair;
    private final VerificationKey activeKey;
    private final Map<String, VerificationMaterial> verificationKeys;

    Ed25519VisualEvidenceSigner(KeyPair activeKeyPair,
                                String keyId,
                                Instant createdAt,
                                String provider,
                                Map<String, VerificationMaterial> verificationKeys) {
        this.activeKeyPair = activeKeyPair;
        this.activeKey = new VerificationKey("", keyId, ALGORITHM,
                Base64.getEncoder().encodeToString(activeKeyPair.getPublic().getEncoded()), createdAt,
                "ACTIVE", provider);
        this.verificationKeys = new LinkedHashMap<>(verificationKeys == null ? Map.of() : verificationKeys);
        this.verificationKeys.putIfAbsent(keyId, new VerificationMaterial(activeKey, activeKeyPair.getPublic()));
    }

    @Override
    public VisualRunEvidenceSeal seal(String materialFingerprint) {
        try {
            Signature signer = Signature.getInstance(ALGORITHM);
            signer.initSign(activeKeyPair.getPrivate());
            signer.update(bytes(materialFingerprint));
            return new VisualRunEvidenceSeal("", materialFingerprint, ALGORITHM, activeKey.keyId(), Instant.now(),
                    Base64.getEncoder().encodeToString(signer.sign()));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign visual run evidence", exception);
        }
    }

    @Override
    public Verification verify(VisualRunEvidenceSeal seal, String actualMaterialFingerprint) {
        if (seal == null || !seal.signed()) {
            return new Verification(false, "UNSIGNED", "Run evidence has no persisted signature.");
        }
        if (!seal.materialFingerprint().equals(actualMaterialFingerprint)) {
            return new Verification(false, "INVALID", "Run evidence material fingerprint does not match its seal.");
        }
        VerificationMaterial material = verificationKeys.get(seal.keyId());
        if (material == null) {
            return new Verification(false, "KEY_UNAVAILABLE", "Verification key is not available: " + seal.keyId());
        }
        try {
            Signature verifier = Signature.getInstance(ALGORITHM);
            verifier.initVerify(material.publicKey());
            verifier.update(bytes(seal.materialFingerprint()));
            boolean valid = verifier.verify(Base64.getDecoder().decode(seal.signature()));
            return valid
                    ? new Verification(true, "VERIFIED", "")
                    : new Verification(false, "INVALID", "Run evidence signature verification failed.");
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            return new Verification(false, "INVALID", "Run evidence signature could not be decoded or verified.");
        }
    }

    @Override
    public Optional<VerificationKey> key(String keyId) {
        VerificationMaterial material = verificationKeys.get(keyId);
        return material == null ? Optional.empty() : Optional.of(material.descriptor());
    }

    @Override
    public KeySetResolution resolveKeySet() {
        List<VerificationKey> ordered = verificationKeys.values().stream()
                .map(VerificationMaterial::descriptor)
                .sorted(Comparator.comparing(VerificationKey::createdAt)
                        .thenComparing(VerificationKey::keyId))
                .toList();
        List<EvidenceVerificationKeySet.KeyPolicy> policies = ordered.stream()
                .map(key -> new EvidenceVerificationKeySet.KeyPolicy(key.keyId(), key.algorithm(),
                        key.encodedPublicKey(), key.createdAt(), key.createdAt(), null,
                        key.keyId().equals(activeKey.keyId())
                                ? EvidenceVerificationKeySet.KeyState.ACTIVE
                                : EvidenceVerificationKeySet.KeyState.VERIFY_ONLY,
                        key.keyId()))
                .toList();
        boolean complete = policies.size() == 1
                && policies.getFirst().state() == EvidenceVerificationKeySet.KeyState.ACTIVE;
        java.util.ArrayList<EvidenceVerificationKeySet.LifecycleEvent> events = new java.util.ArrayList<>();
        long sequence = 0;
        List<EvidenceVerificationKeySet.KeyPolicy> eventPolicies = complete ? policies : List.of();
        for (EvidenceVerificationKeySet.KeyPolicy key : eventPolicies) {
            events.add(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    "created:" + key.keyId(), key.keyId(),
                    EvidenceVerificationKeySet.EventType.CREATED, key.createdAt(), key.createdAt(),
                    null, null, "KEY_CREATED"));
            EvidenceVerificationKeySet.EventType eventType = key.state()
                    == EvidenceVerificationKeySet.KeyState.ACTIVE
                    ? EvidenceVerificationKeySet.EventType.ACTIVATED
                    : EvidenceVerificationKeySet.EventType.RETIRED;
            events.add(new EvidenceVerificationKeySet.LifecycleEvent(++sequence,
                    eventType.name().toLowerCase(java.util.Locale.ROOT) + ":" + key.keyId(),
                    key.keyId(), eventType, key.createdAt(), key.createdAt(), null, null,
                    eventType == EvidenceVerificationKeySet.EventType.ACTIVATED
                            ? "KEY_ACTIVATED" : "KEY_RETIRED"));
        }
        return KeySetResolution.available(new EvidenceVerificationKeySet.Source(activeKey.provider(),
                activeKey.createdAt(), POLICY_HORIZON, activeKey.keyId(),
                complete ? EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE
                        : EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY,
                policies, events));
    }

    @Override
    public boolean available() {
        return true;
    }

    VerificationKey activeKey() {
        return activeKey;
    }

    int verificationKeyCount() {
        return verificationKeys.size();
    }

    private static byte[] bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
    }

    record VerificationMaterial(VerificationKey descriptor, java.security.PublicKey publicKey) {
    }
}
