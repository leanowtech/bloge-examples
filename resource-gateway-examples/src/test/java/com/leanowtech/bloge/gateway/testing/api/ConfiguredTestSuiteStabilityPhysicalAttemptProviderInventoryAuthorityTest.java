package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.ExpectedBinding;
import com.leanowtech.bloge.gateway.testing.api.ConfiguredTestSuiteStabilityServingInventoryAuthority.AuthorityKey;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAttemptCancellationReceipt.IsolationMode;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Binding;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.Material;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventory.ProviderDeployment;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-22T00:00:00Z");
    private static final String POLICY = "sha256:" + "b".repeat(64);
    private static final String ARTIFACT_A = "sha256:" + "a".repeat(64);
    private static final String ARTIFACT_B = "sha256:" + "c".repeat(64);
    private static final String SCOPE = "physical-attempt-providers";
    private static final String COHORT = "rg-release-2026-07-22";
    private static final String PROTOCOL = "bloge.physical-attempt-provider.v1";

    private ObjectMapper objectMapper;
    private KeyPair authorityA;
    private KeyPair authorityB;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority providerA;
    private TestSuiteStabilityPhysicalAttemptObservationAuthority providerB;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        authorityA = generator.generateKeyPair();
        authorityB = generator.generateKeyPair();
        providerA = mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class);
        providerB = mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class);
        when(providerA.descriptor()).thenReturn(bindingA().descriptor());
        when(providerB.descriptor()).thenReturn(bindingB().descriptor());
    }

    @Test
    void verifiesQuorumAndPublishesOnlyAggregateInventoryFacts() throws Exception {
        var authority = authority(inventory(material(17),
                signer("authority-a", "key-a", authorityA),
                signer("authority-b", "key-b", authorityB)), 2,
                List.of(key("authority-a", "key-a", authorityA),
                        key("authority-b", "key-b", authorityB)),
                adapters(providerA, providerB), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isTrue();
            assertThat(observed.revision()).isEqualTo(17);
            assertThat(observed.bindings()).containsExactly(bindingA(), bindingB());
            assertThat(observed.validSignatureCount()).isEqualTo(2);
        });
        assertThat(authority.descriptor()).satisfies(descriptor -> {
            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.providerBindingCount()).isEqualTo(2);
            assertThat(descriptor.properties())
                    .containsEntry("privateMaterialPresent", false)
                    .containsEntry("dynamicInventory", false)
                    .doesNotContainKeys("providerId", "deploymentId", "cohortId",
                            "materialFingerprint", "policyFingerprint", "keyId");
        });
        verifyNoInteractions(providerA, providerB);
    }

    @Test
    void resolvesExactBindingAndVerifiesAdapterDescriptorInsideCallerBoundary()
            throws Exception {
        var authority = validAuthority();

        assertThat(authority.resolve("provider-a", "deployment-1").descriptor())
                .isEqualTo(bindingA().descriptor());
    }

    @Test
    void rejectsUnknownDeploymentAndRuntimeDescriptorDrift() throws Exception {
        var authority = validAuthority();
        assertThatThrownBy(() -> authority.resolve("provider-a", "deployment-old"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed inventory");

        when(providerA.descriptor()).thenReturn(new Binding(
                Binding.SCHEMA_VERSION, "provider-a", "deployment-1", ARTIFACT_A,
                "observation-key-drifted", List.of(IsolationMode.PROCESS),
                5_000, 86_400_000).descriptor());
        assertThatThrownBy(() -> authority.resolve(
                "provider-a", "deployment-1").descriptor())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed inventory");
    }

    @Test
    void exactAdapterCoverageRejectsMissingAndUnattestedRuntimeAdapters() throws Exception {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        List<AuthorityKey> keys = List.of(key("authority-a", "key-a", authorityA));

        assertThatThrownBy(() -> authority(inventory, 1, keys,
                Map.of(bindingA().identity(), providerA), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no exact runtime adapter");

        Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority> extra =
                new java.util.HashMap<>(adapters(providerA, providerB));
        extra.put(new ProviderDeployment("provider-c", "deployment-1"),
                mock(TestSuiteStabilityPhysicalAttemptObservationAuthority.class));
        assertThatThrownBy(() -> authority(inventory, 1, keys, extra,
                Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("absent from the signed");
    }

    @Test
    void rejectsTamperedMaterialBadSignatureAndInsufficientThreshold() throws Exception {
        TestSuiteStabilityPhysicalAttemptProviderInventory valid = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        var tampered = new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material(18), valid.materialFingerprint(), valid.signatures());

        assertThatThrownBy(() -> authority(tampered, 1,
                List.of(key("authority-a", "key-a", authorityA)),
                adapters(providerA, providerB), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("binding");

        TestSuiteStabilityPhysicalAttemptProviderInventory badSignature =
                inventory(material(17), signer("authority-a", "key-a", authorityB));
        assertThatThrownBy(() -> authority(badSignature, 1,
                List.of(key("authority-a", "key-a", authorityA)),
                adapters(providerA, providerB), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature verification");

        assertThatThrownBy(() -> authority(valid, 2,
                List.of(key("authority-a", "key-a", authorityA),
                        key("authority-b", "key-b", authorityB)),
                adapters(providerA, providerB), Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("threshold");
    }

    @Test
    void rejectsWrongTrustScopeCohortProtocolAndPolicyBindings() throws Exception {
        TestSuiteStabilityPhysicalAttemptProviderInventory inventory = inventory(material(17),
                signer("authority-a", "key-a", authorityA));
        for (ExpectedBinding wrong : List.of(
                new ExpectedBinding("wrong.example", SCOPE, COHORT, PROTOCOL, Set.of(POLICY)),
                new ExpectedBinding("provider.inventory.example", "wrong-scope", COHORT,
                        PROTOCOL, Set.of(POLICY)),
                new ExpectedBinding("provider.inventory.example", SCOPE, "wrong-cohort",
                        PROTOCOL, Set.of(POLICY)),
                new ExpectedBinding("provider.inventory.example", SCOPE, COHORT,
                        "wrong.protocol", Set.of(POLICY)),
                new ExpectedBinding("provider.inventory.example", SCOPE, COHORT, PROTOCOL,
                        Set.of("sha256:" + "d".repeat(64))))) {
            assertThatThrownBy(() -> newAuthority(inventory, wrong,
                    Clock.fixed(NOW, ZoneOffset.UTC)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("binding");
        }
    }

    @Test
    void hardExpiryClosesResolutionAndAlreadyResolvedWrapper() throws Exception {
        MutableClock clock = new MutableClock(NOW);
        var authority = authority(inventory(material(17),
                signer("authority-a", "key-a", authorityA)), 1,
                List.of(key("authority-a", "key-a", authorityA)),
                adapters(providerA, providerB), clock);
        TestSuiteStabilityPhysicalAttemptObservationAuthority resolved =
                authority.resolve("provider-a", "deployment-1");

        clock.advance(Duration.ofHours(2));

        assertThat(authority.observation()).satisfies(observed -> {
            assertThat(observed.available()).isFalse();
            assertThat(observed.status()).isEqualTo("EXPIRED");
        });
        assertThatThrownBy(resolved::descriptor)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("generation is unavailable");
        assertThatThrownBy(() -> authority.resolve("provider-a", "deployment-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void fencedAdapterRejectsObservationForAnotherProviderBeforeDelegateIo() throws Exception {
        var authority = validAuthority();
        TestSuiteStabilityPhysicalAttemptObservationAuthority resolved =
                authority.resolve("provider-a", "deployment-1");
        TestSuiteStabilityPhysicalAttemptObservationCommand command =
                mock(TestSuiteStabilityPhysicalAttemptObservationCommand.class);
        when(command.identity()).thenReturn(identity("provider-b", "deployment-2"));

        assertThatThrownBy(() -> resolved.observe(command))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signed provider binding");
        verifyNoInteractions(providerB);
    }

    @Test
    void protocolRejectsNonCanonicalOrDuplicateBindingsAndAuthorities() throws Exception {
        assertThatThrownBy(() -> material(List.of(bindingB(), bindingA())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");
        assertThatThrownBy(() -> material(List.of(bindingA(), bindingA())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("material");

        Material material = material(17);
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        var first = signer("authority-a", "key-a", authorityA).sign(fingerprint);
        var second = signer("authority-a", "key-b", authorityB).sign(fingerprint);
        assertThatThrownBy(() -> new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material, fingerprint, List.of(first, second)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("envelope");
    }

    @Test
    void descriptorReadDoesNotTouchProviderEvenWhenRepeatedConcurrently() throws Exception {
        var authority = validAuthority();
        AtomicInteger failures = new AtomicInteger();

        List<Thread> readers = java.util.stream.IntStream.range(0, 16)
                .mapToObj(index -> Thread.ofPlatform().start(() -> {
                    try {
                        for (int read = 0; read < 100; read++) {
                            assertThat(authority.descriptor().available()).isTrue();
                        }
                    } catch (Throwable failure) {
                        failures.incrementAndGet();
                    }
                })).toList();
        for (Thread reader : readers) {
            reader.join(Duration.ofSeconds(5));
            assertThat(reader.isAlive()).isFalse();
        }

        assertThat(failures).hasValue(0);
        verifyNoInteractions(providerA, providerB);
    }

    private ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority
            validAuthority() throws Exception {
        return authority(inventory(material(17),
                signer("authority-a", "key-a", authorityA)), 1,
                List.of(key("authority-a", "key-a", authorityA)),
                adapters(providerA, providerB), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority newAuthority(
            TestSuiteStabilityPhysicalAttemptProviderInventory inventory,
            ExpectedBinding expected,
            Clock clock) {
        return new ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, clock, expected, 1,
                List.of(key("authority-a", "key-a", authorityA)), inventory,
                adapters(providerA, providerB));
    }

    private ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority authority(
            TestSuiteStabilityPhysicalAttemptProviderInventory inventory,
            int threshold,
            List<AuthorityKey> keys,
            Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
                    adapters,
            Clock clock) {
        return new ConfiguredTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority(
                objectMapper, clock,
                new ExpectedBinding("provider.inventory.example", SCOPE, COHORT, PROTOCOL,
                        Set.of(POLICY)),
                threshold, keys, inventory, adapters);
    }

    private TestSuiteStabilityPhysicalAttemptProviderInventory inventory(
            Material material, Signer... signers) {
        String fingerprint = ProtocolFingerprint.of(objectMapper, material);
        List<TestSuiteStabilityServingInventory.AuthoritySignature> signatures =
                java.util.Arrays.stream(signers).map(signer -> signer.sign(fingerprint))
                        .sorted(Comparator.comparing(
                                TestSuiteStabilityServingInventory.AuthoritySignature::authorityId))
                        .toList();
        return new TestSuiteStabilityPhysicalAttemptProviderInventory(
                TestSuiteStabilityPhysicalAttemptProviderInventory.SCHEMA_VERSION,
                material, fingerprint, signatures);
    }

    private static Material material(long revision) {
        return material(List.of(bindingA(), bindingB()), revision,
                NOW.minusSeconds(60), NOW.minusSeconds(60), NOW.plusSeconds(3_600));
    }

    private static Material material(List<Binding> bindings) {
        return material(bindings, 17, NOW.minusSeconds(60), NOW.minusSeconds(60),
                NOW.plusSeconds(3_600));
    }

    private static Material material(
            List<Binding> bindings,
            long revision,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        return new Material(Material.SCHEMA_VERSION, "provider.inventory.example",
                "provider-inventory-17", revision, SCOPE, COHORT, PROTOCOL, POLICY,
                bindings, issuedAt, notBefore, expiresAt);
    }

    private static Binding bindingA() {
        return new Binding(Binding.SCHEMA_VERSION, "provider-a", "deployment-1",
                ARTIFACT_A, "observation-key-a", List.of(IsolationMode.PROCESS),
                5_000, 86_400_000);
    }

    private static Binding bindingB() {
        return new Binding(Binding.SCHEMA_VERSION, "provider-b", "deployment-2",
                ARTIFACT_B, "observation-key-b",
                List.of(IsolationMode.CONTAINER, IsolationMode.VM),
                10_000, 172_800_000);
    }

    private static Map<ProviderDeployment, TestSuiteStabilityPhysicalAttemptObservationAuthority>
            adapters(
            TestSuiteStabilityPhysicalAttemptObservationAuthority first,
            TestSuiteStabilityPhysicalAttemptObservationAuthority second) {
        return Map.of(bindingA().identity(), first, bindingB().identity(), second);
    }

    private static AuthorityKey key(String authorityId, String keyId, KeyPair pair) {
        return new AuthorityKey(authorityId, keyId, pair.getPublic(),
                Instant.MIN, Instant.MAX, true, false);
    }

    private static TestSuiteStabilityPhysicalAttemptIdentity identity(
            String providerId, String deploymentId) {
        String fingerprint = "sha256:" + "e".repeat(64);
        return new TestSuiteStabilityPhysicalAttemptIdentity(
                TestSuiteStabilityPhysicalAttemptIdentity.SCHEMA_VERSION,
                "stability-attempt-" + "e".repeat(64), fingerprint,
                "tenant-a", "test", "stability-job-" + "f".repeat(64),
                "sha256:" + "1".repeat(64), "worker-a", 1,
                "sha256:" + "2".repeat(64), providerId, deploymentId,
                IsolationMode.PROCESS);
    }

    private static Signer signer(String authorityId, String keyId, KeyPair pair) {
        return new Signer(authorityId, keyId, pair);
    }

    private record Signer(String authorityId, String keyId, KeyPair keyPair) {
        private TestSuiteStabilityServingInventory.AuthoritySignature sign(String fingerprint) {
            try {
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(fingerprint.getBytes(StandardCharsets.UTF_8));
                return new TestSuiteStabilityServingInventory.AuthoritySignature(
                        authorityId, keyId, "Ed25519", NOW.minusSeconds(30),
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
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
            return now;
        }
    }
}
