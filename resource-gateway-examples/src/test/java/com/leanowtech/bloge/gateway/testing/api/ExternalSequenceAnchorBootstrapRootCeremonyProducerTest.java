package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootCeremonyProducerTest {

    private static final Instant NOW = Instant.parse("2026-07-21T00:00:00Z");
    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-bootstrap-roots";
    private static final String ROOT_DOMAIN = "external-notary-root.example";
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String GENESIS_POLICY = "sha256:" + "b".repeat(64);

    private ObjectMapper objectMapper;
    private Map<String, KeyPair> genesisKeys;
    private Map<String, KeyPair> generationOneKeys;
    private Map<String, KeyPair> generationTwoKeys;
    private ExternalSequenceAnchorBootstrapRootGenesis genesis;
    private ExternalSequenceAnchorBootstrapRootCeremonyProducer producer;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        genesisKeys = keys();
        generationOneKeys = keys();
        generationTwoKeys = keys();
        genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                materials(genesisKeys, "genesis", NOW.minusSeconds(3600),
                        NOW.plus(Duration.ofDays(20))), GENESIS_POLICY);
        producer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), binding(),
                Set.of(POLICY), genesis);
    }

    @Test
    void beginsAndAppendsACompleteChainThroughOpaqueSigners() {
        List<TestSigner> genesisSigners = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> firstSigners = signers(generationOneKeys, "one", Map.of());
        var first = producer.begin(request("ceremony-1",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(genesisSigners), authorities(firstSigners));
        List<TestSigner> secondSigners = signers(generationTwoKeys, "two", Map.of());
        var second = producer.append(first.bundle(),
                request("ceremony-2", first.bundle().headMaterialFingerprint(),
                        generationTwoKeys, "two", NOW, NOW, NOW.plusSeconds(7200)),
                authorities(firstSigners), authorities(secondSigners));

        assertThat(first.bundle().transitions()).hasSize(1);
        assertThat(second.bundle().transitions()).hasSize(2);
        assertThat(second.bundle().transitions().getLast().material().sequence()).isEqualTo(2);
        assertThat(first.signingAttempts()).hasSize(8)
                .allMatch(attempt -> attempt.status()
                        == ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus
                        .SIGNED);
        assertThat(firstSigners).allSatisfy(signer -> assertThat(signer.requests)
                .extracting(ExternalSequenceAnchorBootstrapRootSigningAuthority
                        .SignatureRequest::role)
                .containsExactly(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT,
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                .AUTHORIZING_ROOT));

        var verified = new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), binding(), Set.of(POLICY),
                genesis, new NoOpFloor(), second.bundle());
        assertThat(verified.snapshot().headSequence()).isEqualTo(2);
        assertThat(objectMapper.valueToTree(second).toString())
                .doesNotContain("privateKey", "credential", "providerEndpoint");
    }

    @Test
    void sameCommandProducesByteIdenticalBundleAndDeterministicSignerRequestIds()
            throws Exception {
        var command = request("ceremony-idempotent",
                genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                NOW, NOW, NOW.plusSeconds(3600));
        List<TestSigner> firstGenesis = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> firstIncoming = signers(generationOneKeys, "one", Map.of());
        List<TestSigner> retryGenesis = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> retryIncoming = signers(generationOneKeys, "one", Map.of());

        var first = producer.begin(command, authorities(firstGenesis),
                authorities(firstIncoming));
        var retry = producer.begin(command, authorities(retryGenesis),
                authorities(retryIncoming));

        assertThat(objectMapper.writeValueAsBytes(retry.bundle()))
                .isEqualTo(objectMapper.writeValueAsBytes(first.bundle()));
        assertThat(requestIds(retryGenesis, retryIncoming))
                .containsExactlyElementsOf(requestIds(firstGenesis, firstIncoming));
    }

    @Test
    void toleratesOneUnavailableAndOneInvalidSignerWhileReportingDegradation() {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis",
                Map.of("root-1", Mode.UNAVAILABLE));
        List<TestSigner> incoming = signers(generationOneKeys, "one",
                Map.of("root-4", Mode.WRONG_SIGNATURE));

        var outcome = producer.begin(request("ceremony-degraded",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming));

        assertThat(outcome.bundle().transitions().getFirst()
                .authorizingRootSignatures()).hasSize(3);
        assertThat(outcome.bundle().transitions().getFirst()
                .incomingRootSignatures()).hasSize(3);
        assertThat(outcome.signingAttempts())
                .extracting(ExternalSequenceAnchorBootstrapRootCeremonyProducer
                        .SigningAttempt::status)
                .contains(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus
                                .UNAVAILABLE,
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus
                                .INVALID_SIGNATURE);
    }

    @Test
    void rejectsInvalidSignerEchoButStillCompletesWithAValidQuorum() {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis",
                Map.of("root-2", Mode.WRONG_RESPONSE));

        var outcome = producer.begin(request("ceremony-invalid-response",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers),
                authorities(signers(generationOneKeys, "one", Map.of())));

        assertThat(outcome.bundle().transitions().getFirst()
                .authorizingRootSignatures()).hasSize(3);
        assertThat(outcome.signingAttempts())
                .anySatisfy(attempt -> {
                    assertThat(attempt.authorityId()).isEqualTo("root-2");
                    assertThat(attempt.status()).isEqualTo(
                            ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus
                                    .INVALID_RESPONSE);
                });
    }

    @Test
    void failsBeforeIncomingSigningWhenOldRootQuorumIsUnavailable() {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis",
                Map.of("root-1", Mode.UNAVAILABLE, "root-2", Mode.UNAVAILABLE));
        List<TestSigner> incoming = signers(generationOneKeys, "one", Map.of());

        assertThatThrownBy(() -> producer.begin(request("ceremony-no-quorum",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                            .FailureReason.SIGNING_QUORUM_UNAVAILABLE);
                            assertThat(failure.role()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                            .AUTHORIZING_ROOT);
                            assertThat(failure.acceptedSignatures()).isEqualTo(2);
                            assertThat(failure.requiredSignatures()).isEqualTo(3);
                        });
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void rejectsInsufficientAuthorizerCohortWithItsRoleBeforeSigning() {
        List<TestSigner> allAuthorizers = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> authorizers = new ArrayList<>(allAuthorizers.subList(0, 2));
        List<TestSigner> incoming = signers(generationOneKeys, "one", Map.of());

        assertThatThrownBy(() -> producer.begin(request("ceremony-short-cohort",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                            .FailureReason.PREFLIGHT_QUORUM_UNAVAILABLE);
                            assertThat(failure.role()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                            .AUTHORIZING_ROOT);
                            assertThat(failure.acceptedSignatures()).isEqualTo(2);
                            assertThat(failure.requiredSignatures()).isEqualTo(3);
                        });
        assertThat(allAuthorizers)
                .allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void stalePredecessorAndRejectedPolicyHaveNoSigningSideEffects() {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> incoming = signers(generationOneKeys, "one", Map.of());
        var stale = request("ceremony-stale", "sha256:" + "c".repeat(64),
                generationOneKeys, "one", NOW, NOW, NOW.plusSeconds(3600));

        assertThatThrownBy(() -> producer.begin(stale,
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.STALE_PREDECESSOR));
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());

        var rejectedProducer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), binding(),
                Set.of("sha256:" + "d".repeat(64)), genesis);
        assertThatThrownBy(() -> rejectedProducer.begin(
                request("ceremony-policy", genesis.materialFingerprint(objectMapper),
                        generationOneKeys, "one", NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.POLICY_NOT_ACCEPTED));
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void invalidLifecycleAndClockAreRejectedBeforeSigning() {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> incoming = signers(generationOneKeys, "one", Map.of());
        var tooLong = request("ceremony-too-long",
                genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                NOW, NOW, NOW.plus(Duration.ofDays(31)));

        assertThatThrownBy(() -> producer.begin(tooLong,
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.INVALID_SUCCESSOR_LIFECYCLE));
        var oldCommand = request("ceremony-old-clock",
                genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                NOW.minusSeconds(6), NOW, NOW.plusSeconds(3600));
        assertThatThrownBy(() -> producer.begin(oldCommand,
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.CLOCK_OUT_OF_BOUNDS));
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void signerSetMismatchIsRejectedBeforeAnyAuthorityIsCalled() throws Exception {
        List<TestSigner> authorizers = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> incoming = signers(generationOneKeys, "one", Map.of());
        TestSigner original = incoming.getLast();
        KeyPair unrelated = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        incoming.set(incoming.size() - 1, new TestSigner(original.authorityId,
                original.keyId, unrelated, Mode.GOOD));

        assertThatThrownBy(() -> producer.begin(request("ceremony-mismatch",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                            .FailureReason.SIGNER_BINDING_INVALID);
                            assertThat(failure.role()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                            .INCOMING_ROOT);
                        });
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void tamperedCurrentChainCannotDriveASecondCeremony() {
        var first = producer.begin(request("ceremony-valid",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(signers(genesisKeys, "genesis", Map.of())),
                authorities(signers(generationOneKeys, "one", Map.of())));
        ExternalSequenceAnchorBootstrapRootTransition valid =
                first.bundle().transitions().getFirst();
        var badSignature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                valid.authorizingRootSignatures().getFirst().authorityId(),
                valid.authorizingRootSignatures().getFirst().keyId(), "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
        List<TestSuiteStabilityServingInventory.AuthoritySignature> corrupted =
                new ArrayList<>(valid.authorizingRootSignatures());
        corrupted.set(0, badSignature);
        var tamperedTransition = new ExternalSequenceAnchorBootstrapRootTransition(
                valid.schemaVersion(), valid.material(), valid.materialFingerprint(),
                corrupted, valid.incomingRootSignatures());
        var tamperedBundle = new ExternalSequenceAnchorBootstrapRootBundle(
                first.bundle().schemaVersion(), first.bundle().genesisMaterialFingerprint(),
                List.of(tamperedTransition), first.bundle().headMaterialFingerprint());
        List<TestSigner> authorizers = signers(generationOneKeys, "one", Map.of());
        List<TestSigner> incoming = signers(generationTwoKeys, "two", Map.of());

        assertThatThrownBy(() -> producer.append(tamperedBundle,
                request("ceremony-after-tamper", tamperedBundle.headMaterialFingerprint(),
                        generationTwoKeys, "two", NOW, NOW, NOW.plusSeconds(7200)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.INVALID_CURRENT_CHAIN));
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void scheduledSuccessorIsProducedForItsActivationInstant() {
        Instant activation = NOW.plusSeconds(120);
        var outcome = producer.begin(request("ceremony-scheduled",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, activation, NOW.plusSeconds(7200)),
                authorities(signers(genesisKeys, "genesis", Map.of())),
                authorities(signers(generationOneKeys, "one", Map.of())));

        assertThat(outcome.bundle().transitions().getFirst().material().notBefore())
                .isEqualTo(activation);
        var verified = new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore(
                objectMapper, Clock.fixed(activation, ZoneOffset.UTC), binding(),
                Set.of(POLICY), genesis, new NoOpFloor(), outcome.bundle());
        assertThat(verified.descriptor().available()).isTrue();
    }

    @Test
    void successorCannotActivateAfterTheCurrentQuorumHorizon() {
        var first = producer.begin(request("ceremony-horizon-first",
                        genesis.materialFingerprint(objectMapper), generationOneKeys, "one",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(signers(genesisKeys, "genesis", Map.of())),
                authorities(signers(generationOneKeys, "one", Map.of())));
        List<TestSigner> authorizers = signers(generationOneKeys, "one", Map.of());
        List<TestSigner> incoming = signers(generationTwoKeys, "two", Map.of());

        assertThatThrownBy(() -> producer.append(first.bundle(),
                request("ceremony-horizon-gap", first.bundle().headMaterialFingerprint(),
                        generationTwoKeys, "two", NOW, NOW.plusSeconds(3601),
                        NOW.plusSeconds(7200)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> assertThat(failure.reason()).isEqualTo(
                                ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                        .FailureReason.INVALID_SUCCESSOR_LIFECYCLE));
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    @Test
    void oversizedSignerCohortIsRejectedBeforeOldRootsAreCalled() throws Exception {
        Map<String, KeyPair> manyKeys = keys(33);
        List<TestSigner> authorizers = signers(genesisKeys, "genesis", Map.of());
        List<TestSigner> incoming = signers(manyKeys, "many", Map.of());

        assertThatThrownBy(() -> producer.begin(request("ceremony-too-many-signers",
                        genesis.materialFingerprint(objectMapper), manyKeys, "many",
                        NOW, NOW, NOW.plusSeconds(3600)),
                authorities(authorizers), authorities(incoming)))
                .isInstanceOfSatisfying(
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyException
                                .class,
                        failure -> {
                            assertThat(failure.reason()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootCeremonyProducer
                                            .FailureReason.SIGNER_BINDING_INVALID);
                            assertThat(failure.role()).isEqualTo(
                                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                            .INCOMING_ROOT);
                        });
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.requests).isEmpty());
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest request(
            String ceremonyId,
            String predecessor,
            Map<String, KeyPair> incoming,
            String prefix,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt) {
        Instant keyExpiry = expiresAt.isAfter(NOW.plus(Duration.ofDays(40)))
                ? expiresAt.plusSeconds(3600) : NOW.plus(Duration.ofDays(40));
        return new ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                ceremonyId, predecessor,
                materials(incoming, prefix, NOW.minusSeconds(3600), keyExpiry),
                POLICY, issuedAt, notBefore, expiresAt);
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding() {
        return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding(
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                Duration.ofDays(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 32);
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> materials(
            Map<String, KeyPair> keys,
            String prefix,
            Instant notBefore,
            Instant expiresAt) {
        return keys.entrySet().stream()
                .map(entry -> new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                        entry.getKey(), prefix + "-key-" + entry.getKey().substring(5),
                        Base64.getEncoder().encodeToString(
                                entry.getValue().getPublic().getEncoded()),
                        notBefore, expiresAt, true, false))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial::authorityId))
                .toList();
    }

    private List<TestSigner> signers(
            Map<String, KeyPair> keys, String prefix, Map<String, Mode> modes) {
        return keys.entrySet().stream()
                .map(entry -> new TestSigner(entry.getKey(),
                        prefix + "-key-" + entry.getKey().substring(5), entry.getValue(),
                        modes.getOrDefault(entry.getKey(), Mode.GOOD)))
                .sorted(Comparator.comparing(signer -> signer.authorityId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities(
            List<TestSigner> signers) {
        return new ArrayList<>(signers);
    }

    @SafeVarargs
    private static List<String> requestIds(List<TestSigner>... groups) {
        return java.util.Arrays.stream(groups).flatMap(List::stream)
                .flatMap(signer -> signer.requests.stream())
                .map(ExternalSequenceAnchorBootstrapRootSigningAuthority.SignatureRequest
                        ::requestId)
                .sorted().toList();
    }

    private static Map<String, KeyPair> keys() throws Exception {
        return keys(4);
    }

    private static Map<String, KeyPair> keys(int count) throws Exception {
        Map<String, KeyPair> result = new HashMap<>();
        for (int index = 1; index <= count; index++) {
            result.put("root-" + index,
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        }
        return result;
    }

    private enum Mode {
        GOOD,
        UNAVAILABLE,
        WRONG_RESPONSE,
        WRONG_SIGNATURE
    }

    private static final class TestSigner
            implements ExternalSequenceAnchorBootstrapRootSigningAuthority {
        private final String authorityId;
        private final String keyId;
        private final KeyPair keyPair;
        private final Mode mode;
        private final List<SignatureRequest> requests = new ArrayList<>();

        private TestSigner(String authorityId, String keyId, KeyPair keyPair, Mode mode) {
            this.authorityId = authorityId;
            this.keyId = keyId;
            this.keyPair = keyPair;
            this.mode = mode;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION, authorityId, keyId, "Ed25519",
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        }

        @Override
        public SignatureResponse sign(SignatureRequest request) {
            requests.add(request);
            if (mode == Mode.UNAVAILABLE) {
                throw new IllegalStateException("provider details must not escape");
            }
            try {
                KeyPair signingKey = mode == Mode.WRONG_SIGNATURE
                        ? KeyPairGenerator.getInstance("Ed25519").generateKeyPair() : keyPair;
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(signingKey.getPrivate());
                signer.update(request.materialFingerprint().getBytes(StandardCharsets.UTF_8));
                String responseFingerprint = mode == Mode.WRONG_RESPONSE
                        ? "sha256:" + "f".repeat(64) : request.materialFingerprint();
                return new SignatureResponse(SignatureResponse.SCHEMA_VERSION,
                        request.requestId(), authorityId, keyId, "Ed25519",
                        responseFingerprint, request.issuedAt(),
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    private static final class NoOpFloor
            implements ExternalSequenceAnchorBootstrapRootPublicationFloor {
        @Override
        public void accept(VerifiedChain chain) {
        }

        @Override
        public boolean durable() {
            return true;
        }
    }
}
