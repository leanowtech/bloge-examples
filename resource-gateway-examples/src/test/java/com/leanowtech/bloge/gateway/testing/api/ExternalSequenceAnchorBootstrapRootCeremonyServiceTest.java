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
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalSequenceAnchorBootstrapRootCeremonyServiceTest {

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
    private Instant now;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-root-ceremony-service-"
                        + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "", 8));
        now = database.jdbc().queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant()
                .truncatedTo(ChronoUnit.SECONDS);
        objectMapper = new ObjectMapper().findAndRegisterModules();
        genesisKeys = keys();
        incomingKeys = keys();
        genesis = new ExternalSequenceAnchorBootstrapRootGenesis(
                ExternalSequenceAnchorBootstrapRootGenesis.SCHEMA_VERSION,
                SCOPE, ROOT_SET, ROOT_DOMAIN, 3, 1,
                materials(genesisKeys, "genesis"), GENESIS_POLICY);
        producer = new ExternalSequenceAnchorBootstrapRootCeremonyProducer(
                objectMapper, Clock.fixed(now, ZoneOffset.UTC), binding(),
                Set.of(POLICY), genesis);
        journal = journal();
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(producer, journal);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.close();
        }
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

        try (var replayService = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal())) {
            var replayed = replayService.execute(request.ceremonyId(), "worker-b", 30,
                    authorities(authorizers), authorities(incoming));
            assertThat(replayed.status()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus
                            .IDEMPOTENT_REPLAY);
        }
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
    }

    @Test
    void invalidAutoHeartbeatLeaseFailsBeforeAcquiringAnAttempt() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-invalid-auto-lease");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        assertThatThrownBy(() -> service.execute(request.ceremonyId(), "worker-a", 2,
                authorities(authorizers), authorities(incoming)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("three through 300");
        assertThat(journal.snapshot(request.ceremonyId())).isPresent().get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
                    assertThat(snapshot.attemptCount()).isZero();
                });
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
    }

    @Test
    void automaticHeartbeatKeepsASlowHealthySignerExclusiveAndCommitsItsSuccessorFence()
            throws Exception {
        service.close();
        var responseLossJournal = new FaultInjectingHeartbeatJournal(
                journal, HeartbeatFault.RESPONSE_LOSS);
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, responseLossJournal,
                new ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator(
                        responseLossJournal));
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        CountDownLatch signerEntered = new CountDownLatch(1);
        CountDownLatch signerRelease = new CountDownLatch(1);
        IdempotentSigner first = authorizers.getFirst();
        authorizers.set(0, new IdempotentSigner(first.authorityId, first.keyId,
                first.keyPair, false, 0L, signerEntered, signerRelease));
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-heartbeat-service");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionResult result;
        try (var worker = Executors.newSingleThreadExecutor()) {
            var execution = worker.submit(() -> service.execute(
                    request.ceremonyId(), "worker-a", 3,
                    authorities(authorizers), authorities(incoming)));
            try {
                assertThat(signerEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(responseLossJournal.heartbeatRecovered.await(
                        5, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseTime(responseLossJournal.initialClaim.claimUntil());

                var rival = journal().acquire(
                        new ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand(
                                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                        .AcquisitionCommand.SCHEMA_VERSION,
                                request.ceremonyId(), "worker-b", 30));
                assertThat(rival.disposition()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                .AcquisitionDisposition.BUSY);
                assertThat(rival.snapshot().heartbeatCount()).isPositive();
            } finally {
                signerRelease.countDown();
            }
            result = execution.get(5, TimeUnit.SECONDS);
        }

        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(result.snapshot().attemptCount()).isEqualTo(1L);
        assertThat(result.snapshot().heartbeatCount()).isPositive();
        assertThat(result.snapshot().claimVersion())
                .isEqualTo(result.snapshot().heartbeatCount() + 1L);
        assertThat((long) responseLossJournal.heartbeatCalls.get())
                .isEqualTo(result.snapshot().heartbeatCount() + 1L);
    }

    private void awaitDatabaseTime(Instant threshold) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Instant observed;
        do {
            observed = database.jdbc().queryForObject(
                    "SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
            if (!observed.isBefore(threshold)) {
                return;
            }
            Thread.sleep(10L);
        } while (System.nanoTime() < deadline);
        assertThat(observed).isAfterOrEqualTo(threshold);
    }

    @Test
    void claimAtApprovalHorizonDoesNotFailBecauseItCannotBeExtended() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        IdempotentSigner first = authorizers.getFirst();
        authorizers.set(0, new IdempotentSigner(first.authorityId, first.keyId,
                first.keyPair, false, 1_500L, new CountDownLatch(1)));
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-approval-horizon");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        assertThat(service.approve(
                new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand(
                        ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand
                                .SCHEMA_VERSION,
                        request.ceremonyId(), "approve-horizon", "checker-a", 3))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition.APPROVED);

        var result = service.execute(request.ceremonyId(), "worker-a", 3,
                authorities(authorizers), authorities(incoming));

        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(result.snapshot().claimUntil()).isEqualTo(
                result.snapshot().approvalUntil());
        assertThat(result.snapshot().heartbeatCount()).isZero();
    }

    @Test
    void malformedHeartbeatSuccessorDiscardsTheGeneratedOutcome() throws Exception {
        service.close();
        var malformedJournal = new FaultInjectingHeartbeatJournal(
                journal, HeartbeatFault.MALFORMED_SUCCESSOR);
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, malformedJournal,
                new ExternalSequenceAnchorBootstrapRootCeremonyLeaseCoordinator(
                        malformedJournal));
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        IdempotentSigner first = authorizers.getFirst();
        authorizers.set(0, new IdempotentSigner(first.authorityId, first.keyId,
                first.keyPair, false, 1_500L, new CountDownLatch(1)));
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-malformed-heartbeat");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        var result = service.execute(request.ceremonyId(), "worker-a", 3,
                authorities(authorizers), authorities(incoming));

        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus
                        .FENCE_REJECTED);
        assertThat(result.snapshot().outcome()).isNull();
        assertThat(journal.snapshot(request.ceremonyId())).isPresent().get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.EXECUTING);
                    assertThat(snapshot.outcome()).isNull();
                    assertThat(snapshot.claimVersion()).isEqualTo(2L);
                });
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

    @Test
    void timedOutSignerIsUnavailableButRemainingQuorumCanCommitWithinTheBound() {
        service.close();
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal,
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMillis(100), 4));
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        IdempotentSigner first = authorizers.getFirst();
        authorizers.set(0, new IdempotentSigner(first.authorityId, first.keyId,
                first.keyPair, false, 1_000L, new CountDownLatch(1)));
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-bounded-signer-timeout");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        long startedAt = System.nanoTime();
        var result = service.execute(request.ceremonyId(), "worker-a", 30,
                authorities(authorizers), authorities(incoming));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(result.snapshot().outcome().signingAttempts()).anySatisfy(attempt -> {
            assertThat(attempt.authorityId()).isEqualTo("root-1");
            assertThat(attempt.role()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.AUTHORIZING_ROOT);
            assertThat(attempt.status()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.UNAVAILABLE);
        });
        assertThat(service.signerCallSnapshot()).satisfies(snapshot -> {
            assertThat(snapshot.timedOutCalls()).isEqualTo(1L);
            assertThat(snapshot.activeCalls()).isZero();
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void signerTimeoutQuorumFailureReopensApprovedAttemptWithoutPartialArtifact() {
        service.close();
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal,
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                        Duration.ofSeconds(1), Duration.ofMillis(100), 4));
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        for (int index = 0; index < 2; index++) {
            IdempotentSigner signer = authorizers.get(index);
            authorizers.set(index, new IdempotentSigner(signer.authorityId, signer.keyId,
                    signer.keyPair, false, 1_000L, new CountDownLatch(1)));
        }
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-timeout-quorum-failure");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        var result = service.execute(request.ceremonyId(), "worker-a", 30,
                authorities(authorizers), authorities(incoming));

        assertThat(result.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);
        assertThat(result.snapshot()).satisfies(snapshot -> {
            assertThat(snapshot.state()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
            assertThat(snapshot.outcome()).isNull();
        });
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(service.signerCallSnapshot()).satisfies(snapshot -> {
            assertThat(snapshot.timedOutCalls()).isEqualTo(2L);
            assertThat(snapshot.activeCalls()).isZero();
            assertThat(snapshot.lingeringCalls()).isZero();
        });
    }

    @Test
    void recoveryResolvesAuthoritiesOnlyAfterApprovalAndCommitsTheApprovedCohort() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        AtomicInteger resolutions = new AtomicInteger();
        ExternalSequenceAnchorBootstrapRootAuthorityResolver resolver = proposal -> {
            resolutions.incrementAndGet();
            return new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                    authorities(authorizers), authorities(incoming));
        };

        assertThat(service.recover("recovery-worker", 30, resolver).status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .NO_ACTIVE_CEREMONY);
        var request = request("ceremony-automatic-recovery");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        assertThat(service.recover("recovery-worker", 30, resolver).status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .AWAITING_APPROVAL);
        assertThat(resolutions).hasValue(0);

        approve(request.ceremonyId());
        var recovered = service.recover("recovery-worker", 30, resolver);

        assertThat(recovered.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus.EXECUTED);
        assertThat(recovered.execution().status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.PRODUCED);
        assertThat(recovered.snapshot().attemptCount()).isEqualTo(1L);
        assertThat(resolutions).hasValue(1);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isEqualTo(1));
    }

    @Test
    void resolverFailureIsBoundedReleasesTheFenceAndEntersDurableBackoff() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-resolver-failure");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        var failed = service.recover("recovery-worker", 30, proposal -> {
            throw new IllegalStateException("credential and provider details");
        });

        assertThat(failed.status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus.EXECUTED);
        assertThat(failed.execution().status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED);
        assertThat(failed.execution().failureReason()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNER_BINDING_INVALID);
        assertThat(failed.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
        assertThat(objectMapper.valueToTree(failed).toString())
                .doesNotContain("credential", "provider");
        assertThat(service.recover("recovery-worker-2", 30, proposal ->
                new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                        authorities(authorizers), authorities(incoming))).status())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyService.RecoveryStatus
                        .RETRY_DELAYED);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
    }

    @Test
    void closedServiceRejectsRecoveryBeforeItCanConsumeADurableAttempt() {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-close-fence");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        service.close();

        assertThat(service.runtimeSnapshot()).satisfies(snapshot -> {
            assertThat(snapshot.schemaVersion()).isEqualTo(
                    ExternalSequenceAnchorBootstrapRootCeremonyService.RuntimeSnapshot
                            .SCHEMA_VERSION);
            assertThat(snapshot.closed()).isTrue();
            assertThat(snapshot.signerCalls().closed()).isTrue();
        });
        assertThatThrownBy(() -> service.recover("worker-after-close", 30, proposal ->
                new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                        authorities(authorizers), authorities(incoming))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThatThrownBy(() -> service.execute(request.ceremonyId(),
                "worker-after-close", 30, authorities(authorizers), authorities(incoming)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(journal.snapshot(request.ceremonyId())).isPresent().get()
                .satisfies(snapshot -> {
                    assertThat(snapshot.state()).isEqualTo(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
                    assertThat(snapshot.attemptCount()).isZero();
                });
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
    }

    @Test
    void resolverTimeoutReleasesTheFenceWithoutCallingAnySigner() {
        service.close();
        service = new ExternalSequenceAnchorBootstrapRootCeremonyService(
                producer, journal,
                new ExternalSequenceAnchorBootstrapRootSignerCallSupervisor.Policy(
                        Duration.ofMillis(100), Duration.ofSeconds(1),
                        Duration.ofSeconds(1), 4));
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-resolver-timeout");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());

        long startedAt = System.nanoTime();
        var failed = service.recover("recovery-worker", 30, proposal -> {
            try {
                Thread.sleep(10_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("resolver interrupted", interrupted);
            }
            return new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                    authorities(authorizers), authorities(incoming));
        });
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        assertThat(failed.execution().status()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus.FAILED);
        assertThat(failed.execution().failureReason()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNER_BINDING_INVALID);
        assertThat(service.signerCallSnapshot().timedOutCalls()).isEqualTo(1L);
        assertThat(authorizers).allSatisfy(signer -> assertThat(signer.callCount).isZero());
        assertThat(incoming).allSatisfy(signer -> assertThat(signer.callCount).isZero());
    }

    @Test
    void backgroundRecoverySchedulerRunsOneLaneAndClosesWithPayloadFreeCounters()
            throws Exception {
        List<IdempotentSigner> authorizers = signers(genesisKeys, "genesis", Set.of());
        List<IdempotentSigner> incoming = signers(incomingKeys, "incoming", Set.of());
        var request = request("ceremony-background-recovery");
        service.propose(request, "maker-a", 300, authorities(authorizers),
                authorities(incoming));
        approve(request.ceremonyId());
        var policy = new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler
                .SchedulePolicy(Duration.ZERO, Duration.ofMillis(100),
                Duration.ofSeconds(1));
        var scheduler = new ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler(
                service, "background-worker", 30, proposal ->
                new ExternalSequenceAnchorBootstrapRootAuthorityResolver.AuthoritySet(
                        authorities(authorizers), authorities(incoming)), policy);
        try {
            Instant deadline = Instant.now().plusSeconds(5);
            while (journal.snapshot(request.ceremonyId()).orElseThrow().state()
                    != ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.PRODUCED
                    && Instant.now().isBefore(deadline)) {
                Thread.sleep(25L);
            }
            assertThat(journal.snapshot(request.ceremonyId())).isPresent().get()
                    .extracting(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                            .CeremonySnapshot::state)
                    .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.PRODUCED);
            assertThat(scheduler.snapshot()).satisfies(snapshot -> {
                assertThat(snapshot.schemaVersion()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootCeremonyRecoveryScheduler.Snapshot
                                .SCHEMA_VERSION);
                assertThat(snapshot.pollCount()).isGreaterThanOrEqualTo(1L);
                assertThat(snapshot.executedCount()).isEqualTo(1L);
                assertThat(snapshot.pollFailureCount()).isZero();
                assertThat(snapshot.lastPollFailed()).isFalse();
                assertThat(snapshot.lastExecutionStatus()).isEqualTo(
                        ExternalSequenceAnchorBootstrapRootCeremonyService.ExecutionStatus
                                .PRODUCED);
            });
        } finally {
            scheduler.close();
        }
        assertThat(scheduler.snapshot().closed()).isTrue();
        assertThatThrownBy(scheduler::runOnce)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
        assertThat(objectMapper.valueToTree(scheduler.snapshot()).toString())
                .doesNotContain(request.ceremonyId(), "root-1", "key-1");
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
                materials(incomingKeys, "incoming"), POLICY, now, now,
                now.plusSeconds(3600));
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
                        now.minusSeconds(3600), now.plus(Duration.ofDays(40)), true, false))
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
        private final long delayMillis;
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final Map<String, SignatureRequest> requests = new LinkedHashMap<>();
        private final Map<String, SignatureResponse> responses = new LinkedHashMap<>();
        private int callCount;
        private int generatedCount;

        private IdempotentSigner(
                String authorityId,
                String keyId,
                KeyPair keyPair,
                boolean unavailable) {
            this(authorityId, keyId, keyPair, unavailable, 0L, null, null);
        }

        private IdempotentSigner(
                String authorityId,
                String keyId,
                KeyPair keyPair,
                boolean unavailable,
                long delayMillis,
                CountDownLatch entered) {
            this(authorityId, keyId, keyPair, unavailable, delayMillis, entered, null);
        }

        private IdempotentSigner(
                String authorityId,
                String keyId,
                KeyPair keyPair,
                boolean unavailable,
                long delayMillis,
                CountDownLatch entered,
                CountDownLatch release) {
            this.authorityId = authorityId;
            this.keyId = keyId;
            this.keyPair = keyPair;
            this.unavailable = unavailable;
            this.delayMillis = delayMillis;
            this.entered = entered;
            this.release = release;
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
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("signer release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("signer interrupted", interrupted);
                }
            }
            if (delayMillis > 0L) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("signer interrupted", interrupted);
                }
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

    private enum HeartbeatFault {
        RESPONSE_LOSS,
        MALFORMED_SUCCESSOR
    }

    private static final class FaultInjectingHeartbeatJournal
            implements ExternalSequenceAnchorBootstrapRootCeremonyJournal {
        private final ExternalSequenceAnchorBootstrapRootCeremonyJournal delegate;
        private final HeartbeatFault fault;
        private final AtomicInteger heartbeatCalls = new AtomicInteger();
        private final CountDownLatch heartbeatRecovered = new CountDownLatch(1);
        private volatile ExecutionClaim initialClaim;

        private FaultInjectingHeartbeatJournal(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal delegate,
                HeartbeatFault fault) {
            this.delegate = delegate;
            this.fault = fault;
        }

        @Override
        public ProposalResult propose(CeremonyProposal proposal) {
            return delegate.propose(proposal);
        }

        @Override
        public ApprovalResult approve(ApprovalCommand command) {
            return delegate.approve(command);
        }

        @Override
        public Acquisition acquire(AcquisitionCommand command) {
            Acquisition acquired = delegate.acquire(command);
            if (acquired.disposition()
                    == ExternalSequenceAnchorBootstrapRootCeremonyJournal
                    .AcquisitionDisposition.ACQUIRED
                    && initialClaim == null) {
                initialClaim = acquired.claim();
            }
            return acquired;
        }

        @Override
        public RecoveryAcquisition acquireRecovery(RecoveryAcquisitionCommand command) {
            return delegate.acquireRecovery(command);
        }

        @Override
        public HeartbeatResult heartbeat(HeartbeatCommand command) {
            HeartbeatResult committed = delegate.heartbeat(command);
            int call = heartbeatCalls.getAndIncrement();
            if (call == 0) {
                if (fault == HeartbeatFault.RESPONSE_LOSS) {
                    throw new IllegalStateException("simulated committed response loss");
                }
                ExecutionClaim valid = committed.claim();
                var malformed = new ExecutionClaim(ExecutionClaim.SCHEMA_VERSION,
                        valid.ceremonyId(), "forged-worker", valid.claimVersion(),
                        valid.claimUntil(), valid.proposal());
                return new HeartbeatResult(HeartbeatDisposition.RENEWED,
                        malformed, committed.snapshot());
            }
            if (fault == HeartbeatFault.RESPONSE_LOSS) {
                heartbeatRecovered.countDown();
            }
            return committed;
        }

        @Override
        public CompletionResult complete(
                ExecutionClaim claim,
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome) {
            return delegate.complete(claim, outcome);
        }

        @Override
        public FailureResult release(
                ExecutionClaim claim,
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason reason) {
            return delegate.release(claim, reason);
        }

        @Override
        public Optional<CeremonySnapshot> snapshot(String ceremonyId) {
            return delegate.snapshot(ceremonyId);
        }

        @Override
        public boolean durable() {
            return delegate.durable();
        }
    }
}
