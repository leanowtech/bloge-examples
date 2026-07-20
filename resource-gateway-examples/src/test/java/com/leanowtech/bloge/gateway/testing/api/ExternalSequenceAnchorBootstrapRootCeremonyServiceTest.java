package com.leanowtech.bloge.gateway.testing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.persistence.DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal;
import com.leanowtech.bloge.gateway.testing.persistence.TestRuntimeDatabase;
import org.junit.jupiter.api.AfterEach;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSequenceAnchorBootstrapRootCeremonyServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-21T01:00:00Z");
    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-bootstrap-roots";
    private static final String ROOT_DOMAIN = "external-notary-root.example";
    private static final String POLICY = "sha256:" + "a".repeat(64);
    private static final String GENESIS_POLICY = "sha256:" + "b".repeat(64);

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private Map<String, KeyPair> genesisKeys;
    private Map<String, KeyPair> incomingKeys;
    private ExternalSequenceAnchorBootstrapRootGenesis genesis;
    private ExternalSequenceAnchorBootstrapRootCeremonyProducer producer;
    private DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal journal;
    private ExternalSequenceAnchorBootstrapRootCeremonyService service;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-root-ceremony-service-"
                        + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "", 8));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        genesisKeys = keys();
        incomingKeys = keys();
        genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                materials(genesisKeys, "genesis"), GENESIS_POLICY);
        producer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
                objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), binding(),
                Set.of(POLICY), genesis);
        journal = journal();
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(producer, journal);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void makerCheckerFlowPersistsOutcomeAndTerminalReplayDoesNotCallSigners() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-complete");

        var proposal = service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        assertThat(proposal.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition.CREATED);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        approve(request.ceremonyId());

        var produced = service.execute(request.ceremonyId(), "worker-a", 30,
                authorities(authorizers), authorities(incoming));
        assertThat(produced.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(produced.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.PRODUCED);
        assertThat(produced.snapshot().attemptCount()).isEqualTo(1L);
        assertThat(authorizers).allSatisfy(signer -> {
            assertThat(signer.callCount).isEqualTo(1);
            assertThat(signer.generatedCount).isEqualTo(1);
        });

        var replayed = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal()).execute(request.ceremonyId(), "worker-b", 30,
                authorities(authorizers), authorities(incoming));
        assertThat(replayed.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus
                        .IDEMPOTENT_REPLAY);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
    }

    @Test
    void crashAfterRemoteSigningRecoversByExactRequestReplay() throws Exception {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-crash-recovery");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());
        var abandoned = journal.acquire(
                new ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand(
                        ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand
                                .SCHEMA_VERSION,
                        request.ceremonyId(), "dead-worker", 1));

        producer.begin(request, authorities(authorizers), authorities(incoming));
        Thread.sleep(1_100L);
        var recovered = service.execute(request.ceremonyId(), "recovery-worker", 30,
                authorities(authorizers), authorities(incoming));

        assertThat(abandoned.claim().claimVersion()).isEqualTo(1L);
        assertThat(recovered.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(recovered.snapshot().claimVersion()).isEqualTo(2L);
        assertThat(recovered.snapshot().attemptCount()).isEqualTo(2L);
        assertThat(authorizers).allSatisfy(signer -> {
            assertThat(signer.callCount).isEqualTo(2);
            assertThat(signer.generatedCount).isEqualTo(1);
            assertThat(signer.responses).hasSize(1);
        });
        assertThat(incoming).allSatisfy(signer -> {
            assertThat(signer.callCount).isEqualTo(2);
            assertThat(signer.generatedCount).isEqualTo(1);
        });
    }

    @Test
    void signerCohortDriftAfterApprovalFailsBeforeAnySignatureAndReopensApproval()
            throws Exception {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-drift");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        List<IdempotentSigner> drifted = new ArrayList<>(incoming);
        IdempotentSigner expected = drifted.getLast();
        drifted.set(drifted.size() - 1, new IdempotentSigner(expected.authorityId,
                expected.keyId, KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
                false));
        var result = service.execute(request.ceremonyId(), "worker-a", 30,
                authorities(authorizers), authorities(drifted));

        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNER_BINDING_INVALID);
        assertThat(result.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(drifted).allSatisfy(signer -> assertThat(signer.callCount).isZero());
    }

    @Test
    void signerOutageIsRecordedAndAHealthyExactCohortCanRetry() {
        List<IdempotentSigner> degraded = signers(genesisKeys, "genesis",
                Set.of("root-1", "root-2"));
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-retry");
        service.propose(request, "maker-a", 300, authorities(degraded),
                authorities(incoming));
        approve(request.ceremonyId());

        var failed = service.execute(request.ceremonyId(), "worker-a", 30,
                authorities(degraded), authorities(incoming));
        assertThat(failed.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());

        List<IdempotentSigner> healthy = signers(genesisKeys, "genesis", Set.of());
        var recovered = service.execute(request.ceremonyId(), "worker-b", 30,
                authorities(healthy), authorities(incoming));
        assertThat(recovered.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(recovered.snapshot().attemptCount()).isEqualTo(2L);
        assertThat(recovered.snapshot().lastFailure()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);
    }

    private void approve(String ceremonyId) {
        assertThat(service.approve(
                new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand(
                        ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand
                                .SCHEMA_VERSION,
                        ceremonyId, "approve-" + ceremonyId, "checker-a", 300))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition.APPROVED);
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest request(
            String ceremonyId) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                ceremonyId, genesis.materialFingerprint(objectMapper),
                materials(incomingKeys, "incoming"), POLICY, NOW, NOW,
                NOW.plusSeconds(3600));
    }

    private ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding binding() {
        return new ConfiguredExternalSequenceAnchorBootstrapRootTrustStore.ExpectedBinding(
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                Duration.ofDays(30), Duration.ofSeconds(5),
                Duration.ofSeconds(30), 32);
    }

    private List<ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial> materials(
            Map<String, KeyPair> keys, String prefix) {
        return keys.entrySet().stream()
                .map(entry -> new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                        entry.getKey(), prefix + "-key-" + entry.getKey().substring(5),
                        Base64.getEncoder().encodeToString(
                                entry.getValue().getPublic().getEncoded()),
                        NOW.minusSeconds(3600), NOW.plus(Duration.ofDays(40)), true, false))
                .sorted(Comparator.comparing(
                        ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial::authorityId))
                .toList();
    }

    private static List<IdempotentSigner> signers(
            Map<String, KeyPair> keys, String prefix, Set<String> unavailable) {
        return keys.entrySet().stream()
                .map(entry -> new IdempotentSigner(entry.getKey(),
                        prefix + "-key-" + entry.getKey().substring(5), entry.getValue(),
                        unavailable.contains(entry.getKey())))
                .sorted(Comparator.comparing(signer -> signer.authorityId))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private static List<ExternalSequenceAnchorBootstrapRootSigningAuthority> authorities(
            List<IdempotentSigner> signers) {
        return new ArrayList<>(signers);
    }

    private DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal journal() {
        var result = new DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
                database.jdbc(), objectMapper, SCOPE, ROOT_SET,
                database.transactionManager());
        result.init();
        return result;
    }

    private static Map<String, KeyPair> keys() throws Exception {
        Map<String, KeyPair> result = new HashMap<>();
        for (int index = 1; index <= 4; index++) {
            result.put("root-" + index,
                    KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
        }
        return result;
    }

    private static final class IdempotentSigner
            implements ExternalSequenceAnchorBootstrapRootSigningAuthority {
        private final String authorityId;
        private final String keyId;
        private final KeyPair keyPair;
        private final boolean unavailable;
        private final Map<String, SignatureRequest> requests = new LinkedHashMap<>();
        private final Map<String, SignatureResponse> responses = new LinkedHashMap<>();
        private int callCount;
        private int generatedCount;

        private IdempotentSigner(
                String authorityId,
                String keyId,
                KeyPair keyPair,
                boolean unavailable) {
            this.authorityId = authorityId;
            this.keyId = keyId;
            this.keyPair = keyPair;
            this.unavailable = unavailable;
        }

        @Override
        public Descriptor descriptor() {
            return new Descriptor(Descriptor.SCHEMA_VERSION, authorityId, keyId, "Ed25519",
                    Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        }

        @Override
        public SignatureResponse sign(SignatureRequest request) {
            callCount++;
            SignatureRequest existing = requests.putIfAbsent(request.requestId(), request);
            if (existing != null && !existing.equals(request)) {
                throw new IllegalArgumentException("idempotency identity was reused");
            }
            if (unavailable) {
                throw new IllegalStateException("provider details must not escape");
            }
            return responses.computeIfAbsent(request.requestId(), ignored -> generated(request));
        }

        private SignatureResponse generated(SignatureRequest request) {
            try {
                generatedCount++;
                Signature signer = Signature.getInstance("Ed25519");
                signer.initSign(keyPair.getPrivate());
                signer.update(request.materialFingerprint().getBytes(StandardCharsets.UTF_8));
                return new SignatureResponse(SignatureResponse.SCHEMA_VERSION,
                        request.requestId(), authorityId, keyId, "Ed25519",
                        request.materialFingerprint(), request.issuedAt(),
                        Base64.getEncoder().encodeToString(signer.sign()));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }
    }
}
