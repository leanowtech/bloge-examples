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
    private static final String OTHER_FINGERPRINT =
            "sha256:" + "b".repeat(64);

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
    void stableIdempotencyKeyReplaysTheOriginalSignatureAcrossKeyRotation()
            throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);

        VisualRunEvidenceSeal original = signer.seal(
                FINGERPRINT,
                "scenario-batch-finalization:job-1");
        provider.rotate("kms-key-2");
        clock.advance(Duration.ofMinutes(2));
        VisualRunEvidenceSeal replay = signer.seal(
                FINGERPRINT,
                "scenario-batch-finalization:job-1");

        assertThat(replay).isEqualTo(original);
        assertThat(provider.signCalls()).isEqualTo(2);
        assertThat(signer.verify(replay, FINGERPRINT).valid())
                .isTrue();
        assertThatThrownBy(() -> signer.seal(
                OTHER_FINGERPRINT,
                "scenario-batch-finalization:job-1"))
                .isInstanceOf(EvidenceSigningProviderException.class)
                .extracting(failure ->
                        ((EvidenceSigningProviderException) failure)
                                .code())
                .isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void rejectsMalformedManagedSigningIdempotencyKeyBeforeProviderIo()
            throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);

        assertThatThrownBy(() -> signer.seal(
                FINGERPRINT, "contains whitespace"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotency");
        assertThat(provider.signCalls()).isZero();
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
    void exportsAtomicCompleteLifecycleSnapshotAcrossRotation() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.completePolicy(true);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);
        provider.rotate("kms-key-2");
        clock.advance(Duration.ofSeconds(2));

        VisualEvidenceSigner.KeySetResolution resolution = signer.resolveKeySet();

        assertThat(resolution.status()).isEqualTo(VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE);
        assertThat(resolution.keySet().activeKeyId()).isEqualTo("kms-key-2");
        assertThat(resolution.keySet().policyCompleteness())
                .isEqualTo(EvidenceVerificationKeySet.PolicyCompleteness.COMPLETE);
        assertThat(resolution.keySet().keys())
                .extracting(EvidenceVerificationKeySet.KeyPolicy::keyId,
                        EvidenceVerificationKeySet.KeyPolicy::state)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("kms-key-1",
                                EvidenceVerificationKeySet.KeyState.VERIFY_ONLY),
                        org.assertj.core.groups.Tuple.tuple("kms-key-2",
                                EvidenceVerificationKeySet.KeyState.ACTIVE));
        assertThat(resolution.keySet().events())
                .extracting(EvidenceVerificationKeySet.LifecycleEvent::type)
                .contains(EvidenceVerificationKeySet.EventType.RETIRED,
                        EvidenceVerificationKeySet.EventType.ACTIVATED);
        assertThat(signer.descriptor().properties())
                .containsEntry("keySetPolicyCompleteness", "COMPLETE")
                .containsEntry("keySetPolicyAvailable", true);
    }

    @Test
    void legacyProviderSnapshotRemainsExplicitlyCurrentStateOnly() throws Exception {
        MutableClock clock = clock();
        MutableProvider provider = new MutableProvider(clock);
        provider.legacySchema(true);
        provider.addActive("kms-key-1");
        ManagedVisualEvidenceSigner signer = signer(provider, clock);

        VisualEvidenceSigner.KeySetResolution resolution = signer.resolveKeySet();

        assertThat(resolution.status()).isEqualTo(VisualEvidenceSigner.KeyResolutionStatus.AVAILABLE);
        assertThat(resolution.keySet().policyCompleteness())
                .isEqualTo(EvidenceVerificationKeySet.PolicyCompleteness.CURRENT_STATE_ONLY);
        assertThat(resolution.keySet().events()).isEmpty();
        assertThat(signer.descriptor().properties())
                .containsEntry("keySetPolicyCompleteness", "CURRENT_STATE_ONLY");
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
        private final Map<String, SignatureResult> signatures =
                new LinkedHashMap<>();
        private String activeKeyId = "";
        private String rotateOnNextSign = "";
        private Duration snapshotLifetime = Duration.ofMinutes(5);
        private boolean failFetch;
        private boolean failSign;
        private boolean corruptSignature;
        private boolean invalidSchema;
        private boolean invertedTimeWindow;
        private boolean completePolicy;
        private boolean legacySchema;

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

        void completePolicy(boolean value) {
            completePolicy = value;
        }

        void legacySchema(boolean value) {
            legacySchema = value;
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
            List<KeyLifecycleEvent> events = new ArrayList<>();
            long sequence = 0;
            if (completePolicy) {
                for (ManagedKey key : keys) {
                    events.add(new KeyLifecycleEvent(++sequence, "created:" + key.keyId(), key.keyId(),
                            "CREATED", key.createdAt(), key.createdAt(), "", null, "KEY_CREATED"));
                    if ("VERIFY_ONLY".equals(key.state())) {
                        events.add(new KeyLifecycleEvent(++sequence, "activated:" + key.keyId(), key.keyId(),
                                "ACTIVATED", key.createdAt(), key.createdAt(), "", null, "KEY_ACTIVATED"));
                    }
                    String type = switch (key.state()) {
                        case "ACTIVE" -> "ACTIVATED";
                        case "VERIFY_ONLY" -> "RETIRED";
                        case "DISABLED" -> "DISABLED";
                        case "REVOKED" -> "REVOKED";
                        default -> throw new IllegalStateException("Unexpected state " + key.state());
                    };
                    boolean revoked = "REVOKED".equals(type);
                    events.add(new KeyLifecycleEvent(++sequence,
                            type.toLowerCase() + ":" + key.keyId(), key.keyId(), type,
                            revoked || "RETIRED".equals(type) ? clock.instant() : key.createdAt(),
                            revoked || "RETIRED".equals(type) ? clock.instant() : key.createdAt(),
                            revoked ? "PROSPECTIVE" : "", null, "KEY_" + type));
                }
            }
            String schemaVersion = invalidSchema ? "invalid" : legacySchema
                    ? KeySet.SCHEMA_VERSION_V1 : KeySet.SCHEMA_VERSION;
            return new KeySet(schemaVersion, generatedAt,
                    expiresAt, activeKeyId, keys,
                    completePolicy ? "COMPLETE" : "CURRENT_STATE_ONLY", events);
        }

        @Override
        public SignatureResult sign(SignatureRequest request) {
            signCalls.incrementAndGet();
            SignatureResult replay = signatures.get(
                    request.requestId());
            if (replay != null) {
                if (!replay.materialFingerprint().equals(
                        request.materialFingerprint())) {
                    throw new EvidenceSigningProviderException(
                            "IDEMPOTENCY_CONFLICT",
                            "test signing id identifies different material",
                            false);
                }
                return replay;
            }
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
                SignatureResult result = new SignatureResult(SignatureResult.SCHEMA_VERSION, request.requestId(), request.keyId(),
                        request.algorithm(), request.materialFingerprint(), clock.instant(),
                        Base64.getEncoder().encodeToString(bytes));
                signatures.put(request.requestId(), result);
                return result;
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
