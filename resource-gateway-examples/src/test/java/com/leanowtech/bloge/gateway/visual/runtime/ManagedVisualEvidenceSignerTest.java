package com.leanowtech.bloge.gateway.visual.runtime;

import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ManagedVisualEvidenceSignerTest {
    private static final String FINGERPRINT = "sha256:" + "a".repeat(64);

    @Test
    void signsWithNonExportableProviderAndLocallyVerifiesReturnedSignature() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);

        VisualRunEvidenceSeal seal = signer.seal(FINGERPRINT);

        assertThat(seal.keyId()).isEqualTo("kms-key-1");
        assertThat(signer.verify(seal, FINGERPRINT).valid()).isTrue();
        assertThat(signer.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.providerType()).isEqualTo("MANAGED_KMS_HSM");
            assertThat(descriptor.managedKeyCustody()).isTrue();
            assertThat(descriptor.privateKeyExportable()).isFalse();
            assertThat(descriptor.activeKeyId()).isEqualTo("kms-key-1");
            assertThat(descriptor.successfulSignatureCount()).isOne();
            assertThat(descriptor.properties()).containsEntry("privateMaterialPresent", false)
                    .containsEntry("returnedSignatureLocallyVerified", true);
        });
        assertThat(signer.key("kms-key-1").orElseThrow().encodedPublicKey()).isNotBlank();
    }

    @Test
    void preservesHistoricalVerificationAcrossZeroDowntimeRotation() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        VisualRunEvidenceSeal oldSeal = signer.seal(FINGERPRINT);

        provider.rotate("kms-key-2");
        clock.advance(Duration.ofSeconds(2));
        VisualRunEvidenceSeal newSeal = signer.seal(FINGERPRINT);

        assertThat(newSeal.keyId()).isEqualTo("kms-key-2");
        assertThat(signer.verify(oldSeal, FINGERPRINT).status()).isEqualTo("VERIFIED");
        assertThat(signer.verify(newSeal, FINGERPRINT).status()).isEqualTo("VERIFIED");
        assertThat(signer.descriptor().verificationKeyCount()).isEqualTo(2);
    }

    @Test
    void retriesOnceWhenProviderRotatesBetweenDiscoveryAndSign() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        provider.rotateOnNextSign("kms-key-2");

        VisualRunEvidenceSeal seal = signer.seal(FINGERPRINT);

        assertThat(seal.keyId()).isEqualTo("kms-key-2");
        assertThat(provider.signCalls()).isEqualTo(2);
        assertThat(provider.fetchCalls()).isEqualTo(2);
        assertThat(signer.verify(seal, FINGERPRINT).valid()).isTrue();
    }

    @Test
    void revokedAndDisabledKeysFailWithDistinctHistoricalVerificationStates() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        VisualRunEvidenceSeal oldSeal = signer.seal(FINGERPRINT);
        provider.rotate("kms-key-2");

        provider.state("kms-key-1", "DISABLED");
        clock.advance(Duration.ofSeconds(2));
        assertThat(signer.verify(oldSeal, FINGERPRINT).status()).isEqualTo("KEY_DISABLED");

        provider.state("kms-key-1", "REVOKED");
        clock.advance(Duration.ofSeconds(2));
        assertThat(signer.verify(oldSeal, FINGERPRINT).status()).isEqualTo("KEY_REVOKED");
        assertThat(signer.resolveKey("kms-key-1").key().state()).isEqualTo("REVOKED");
    }

    @Test
    void verificationUsesBoundedPublicCacheButFailsClosedAfterAuthorityExpiry() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        provider.snapshotLifetime(Duration.ofSeconds(10));
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        VisualRunEvidenceSeal seal = signer.seal(FINGERPRINT);
        provider.failFetch(true);

        clock.advance(Duration.ofSeconds(2));
        assertThat(signer.verify(seal, FINGERPRINT).status()).isEqualTo("VERIFIED");
        assertThat(signer.descriptor().state()).isEqualTo("DEGRADED");

        clock.advance(Duration.ofSeconds(9));
        assertThat(signer.verify(seal, FINGERPRINT)).satisfies(verification -> {
            assertThat(verification.valid()).isFalse();
            assertThat(verification.status()).isEqualTo("UNAVAILABLE");
        });
        assertThat(signer.resolveKey("kms-key-1").status())
                .isEqualTo(VisualEvidenceSigner.KeyResolutionStatus.PROVIDER_UNAVAILABLE);
        assertThat(signer.available()).isFalse();
    }

    @Test
    void signingOutageNeverFallsBackToLocalPrivateMaterial() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        provider.failSign(true);

        assertThatThrownBy(() -> signer.seal(FINGERPRINT))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .hasMessageContaining("unavailable");
        assertThat(signer.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.failedSignatureCount()).isOne();
            assertThat(descriptor.state()).isEqualTo("DEGRADED");
            assertThat(descriptor.properties()).containsEntry("lastFailureCode", "PROVIDER_UNAVAILABLE");
        });
    }

    @Test
    void rejectsProviderSignatureThatDoesNotMatchPublishedPublicKey() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        provider.corruptSignature(true);

        assertThatThrownBy(() -> signer.seal(FINGERPRINT))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                .isEqualTo("PROVIDER_SIGNATURE_INVALID");
        assertThat(signer.descriptor().successfulSignatureCount()).isZero();
        assertThat(signer.descriptor().failedSignatureCount()).isOne();
    }

    @Test
    void concurrentCallersPerformOneDueKeyRefresh() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        clock.advance(Duration.ofSeconds(2));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                calls.add(signer::available);
            }
            assertThat(executor.invokeAll(calls)).allSatisfy(future -> assertThat(future.get()).isTrue());
        }
        assertThat(provider.fetchCalls()).isEqualTo(2);
    }

    @Test
    void concurrentUnknownKeyLookupsAreThrottledToOneProviderRefresh() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Callable<VisualEvidenceSigner.KeyResolutionStatus>> calls = new ArrayList<>();
            for (int index = 0; index < 24; index++) {
                int key = index;
                calls.add(() -> signer.resolveKey("unknown-" + key).status());
            }
            assertThat(executor.invokeAll(calls)).allSatisfy(future ->
                    assertThat(future.get()).isEqualTo(VisualEvidenceSigner.KeyResolutionStatus.NOT_FOUND));
        }
        assertThat(provider.fetchCalls()).isEqualTo(2);
    }

    @Test
    void rejectsInvalidKeySetsAndUnavailableBootstrap() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        provider.invalidSchema(true);
        assertThatThrownBy(() -> signer(provider, clock))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                .isEqualTo("KEY_SNAPSHOT_UNAVAILABLE");

        MutableProvider invertedWindow = new MutableProvider(clock);
        invertedWindow.addActive("kms-key-1");
        invertedWindow.invertedTimeWindow(true);
        assertThatThrownBy(() -> signer(invertedWindow, clock))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                .isEqualTo("KEY_SNAPSHOT_UNAVAILABLE");

        MutableProvider unavailable = new MutableProvider(clock);
        unavailable.addActive("kms-key-1");
        unavailable.failFetch(true);
        assertThatThrownBy(() -> signer(unavailable, clock))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure -> ((EvidenceSigningProviderException) failure).code())
                .isEqualTo("KEY_SNAPSHOT_UNAVAILABLE");
    }

    @Test
    void malformedRefreshInvalidatesHealthySnapshotInsteadOfUsingStaleTrust() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        VisualRunEvidenceSeal seal = signer.seal(FINGERPRINT);
        provider.invalidSchema(true);
        clock.advance(Duration.ofSeconds(2));

        assertThat(signer.verify(seal, FINGERPRINT).status()).isEqualTo("UNAVAILABLE");
        assertThat(signer.available()).isFalse();
        assertThat(signer.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.state()).isEqualTo("UNAVAILABLE");
            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.properties()).containsEntry("lastFailureCode", "INVALID_KEY_SNAPSHOT");
        });
    }

    private static ManagedVisualEvidenceSigner signer(MutableProvider provider, MutableClock clock) {
        return new ManagedVisualEvidenceSigner(provider,
                new ManagedVisualEvidenceSigner.Settings(Duration.ofSeconds(1), Duration.ofHours(1)), clock);
    }

    private static MutableClock clock() {
        return new MutableClock(Instant.parse("2026-07-13T00:00:00Z"));
    }

    private static final class MutableProvider implements ManagedEvidenceSigningProvider {
        private final MutableClock clock;
        private final Map<String, KeyPair> keyPairs = new LinkedHashMap<>();
        private final Map<String, String> states = new LinkedHashMap<>();
        private final AtomicInteger fetchCalls = new AtomicInteger();
        private final AtomicInteger signCalls = new AtomicInteger();
        private String activeKeyId = "";
        private String rotateOnNextSign = "";
        private Duration snapshotLifetime = Duration.ofMinutes(5);
        private boolean failFetch;
        private boolean failSign;
        private boolean corruptSignature;
        private boolean invalidSchema;
        private boolean invertedTimeWindow;

        private MutableProvider(MutableClock clock) {
            this.clock = clock;
        }

        void addActive(String keyId) throws Exception {
            keyPairs.put(keyId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
            states.put(keyId, "ACTIVE");
            activeKeyId = keyId;
        }

        void rotate(String keyId) throws Exception {
            states.put(activeKeyId, "VERIFY_ONLY");
            addActive(keyId);
        }

        void rotateOnNextSign(String keyId) {
            rotateOnNextSign = keyId;
        }

        void state(String keyId, String state) {
            states.put(keyId, state);
        }

        void snapshotLifetime(Duration value) {
            snapshotLifetime = value;
        }

        void failFetch(boolean value) {
            failFetch = value;
        }

        void failSign(boolean value) {
            failSign = value;
        }

        void corruptSignature(boolean value) {
            corruptSignature = value;
        }

        void invalidSchema(boolean value) {
            invalidSchema = value;
        }

        void invertedTimeWindow(boolean value) {
            invertedTimeWindow = value;
        }

        int fetchCalls() {
            return fetchCalls.get();
        }

        int signCalls() {
            return signCalls.get();
        }

        @Override
        public KeySet fetchKeys() {
            fetchCalls.incrementAndGet();
            if (failFetch) {
                throw new EvidenceSigningProviderException("PROVIDER_UNAVAILABLE",
                        "test key authority unavailable", true);
            }
            List<ManagedKey> keys = keyPairs.entrySet().stream().map(entry -> new ManagedKey(entry.getKey(),
                    "Ed25519", Base64.getEncoder().encodeToString(entry.getValue().getPublic().getEncoded()),
                    Instant.parse("2026-07-12T00:00:00Z"), states.get(entry.getKey()),
                    "version/" + entry.getKey())).toList();
            Instant generatedAt = invertedTimeWindow ? clock.instant().plusSeconds(10) : clock.instant();
            Instant expiresAt = invertedTimeWindow
                    ? clock.instant().plusSeconds(5) : clock.instant().plus(snapshotLifetime);
            return new KeySet(invalidSchema ? "invalid" : KeySet.SCHEMA_VERSION, generatedAt,
                    expiresAt, activeKeyId, keys);
        }

        @Override
        public SignatureResult sign(SignatureRequest request) {
            signCalls.incrementAndGet();
            if (failSign) {
                throw new EvidenceSigningProviderException("PROVIDER_UNAVAILABLE",
                        "test signing authority unavailable", true);
            }
            if (!rotateOnNextSign.isBlank()) {
                String next = rotateOnNextSign;
                rotateOnNextSign = "";
                try {
                    rotate(next);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
                throw new EvidenceSigningProviderException("KEY_VERSION_MISMATCH", "key rotated", true);
            }
            try {
                Signature signature = Signature.getInstance("Ed25519");
                signature.initSign(keyPairs.get(request.keyId()).getPrivate());
                signature.update(request.materialFingerprint().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] bytes = signature.sign();
                if (corruptSignature) {
                    bytes[0] ^= 1;
                }
                return new SignatureResult(SignatureResult.SCHEMA_VERSION, request.requestId(), request.keyId(),
                        request.algorithm(), request.materialFingerprint(), clock.instant(),
                        Base64.getEncoder().encodeToString(bytes));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public String providerName() {
            return "test-kms";
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
