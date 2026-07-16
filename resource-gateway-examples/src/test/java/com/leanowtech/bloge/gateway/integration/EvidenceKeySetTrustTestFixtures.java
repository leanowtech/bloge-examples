package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

final class EvidenceKeySetTrustTestFixtures {
    static final String TRUST_DOMAIN = "corp.example/evidence";
    static final String LOG_ID = "resource-gateway/prod";
    static final Instant PUBLISHED_AT = Instant.parse("2026-07-16T00:00:00Z");
    static final String SNAPSHOT_A = "sha256:" + "a".repeat(64);
    static final String SNAPSHOT_B = "sha256:" + "b".repeat(64);
    static final String SNAPSHOT_C = "sha256:" + "c".repeat(64);

    private EvidenceKeySetTrustTestFixtures() {
    }

    static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    static ConfiguredEvidenceKeySetTrustStore store(
            ObjectMapper mapper, int threshold, List<Authority> authorities) {
        return new ConfiguredEvidenceKeySetTrustStore(mapper, TRUST_DOMAIN, LOG_ID, threshold,
                authorities.stream().map(authority ->
                        new ConfiguredEvidenceKeySetTrustStore.AuthorityKey(
                                authority.authorityId(), authority.keyPair().getPublic(),
                                Instant.MIN, Instant.MAX,
                                true, false)).toList());
    }

    static EvidenceKeySetTrustPublication publication(
            ObjectMapper mapper, long sequence, String previousFingerprint, long recoveryEpoch,
            Instant publishedAt, List<EvidenceKeySetTrustPublication.SnapshotPin> pins,
            List<Authority> signers) {
        EvidenceKeySetTrustPublication.Material material =
                new EvidenceKeySetTrustPublication.Material("", TRUST_DOMAIN, LOG_ID, sequence,
                        previousFingerprint, recoveryEpoch, publishedAt,
                        publishedAt.plusSeconds(600), pins);
        String fingerprint = EvidenceKeySetTrustPublication.fingerprint(mapper, material);
        List<EvidenceKeySetTrustPublication.AuthoritySignature> signatures = signers.stream()
                .map(authority -> new EvidenceKeySetTrustPublication.AuthoritySignature(
                        authority.authorityId(), "Ed25519", sign(authority.keyPair(), fingerprint)))
                .toList();
        return new EvidenceKeySetTrustPublication("", fingerprint, TRUST_DOMAIN, LOG_ID,
                sequence, previousFingerprint, recoveryEpoch, publishedAt,
                publishedAt.plusSeconds(600), pins, signatures);
    }

    static EvidenceKeySetTrustPublication.SnapshotPin active(String fingerprint) {
        return new EvidenceKeySetTrustPublication.SnapshotPin(fingerprint,
                EvidenceKeySetTrustPublication.PinState.ACTIVE,
                PUBLISHED_AT.minusSeconds(60), null, null, "");
    }

    static EvidenceKeySetTrustPublication.SnapshotPin overlap(String fingerprint) {
        return new EvidenceKeySetTrustPublication.SnapshotPin(fingerprint,
                EvidenceKeySetTrustPublication.PinState.OVERLAP,
                PUBLISHED_AT.minusSeconds(60), PUBLISHED_AT.plusSeconds(3600), null, "");
    }

    static EvidenceKeySetTrustPublication.SnapshotPin revoked(String fingerprint, Instant revokedAt) {
        return new EvidenceKeySetTrustPublication.SnapshotPin(fingerprint,
                EvidenceKeySetTrustPublication.PinState.REVOKED,
                PUBLISHED_AT.minusSeconds(60), revokedAt, revokedAt, "KEY_COMPROMISED");
    }

    private static String sign(KeyPair keyPair, String fingerprint) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    record Authority(String authorityId, KeyPair keyPair) {
    }
}
