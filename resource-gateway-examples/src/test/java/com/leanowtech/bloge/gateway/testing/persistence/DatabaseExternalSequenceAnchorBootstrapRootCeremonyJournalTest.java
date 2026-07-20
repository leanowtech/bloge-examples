package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootBundle;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyJournal;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootCeremonyProducer;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootGenesis;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootSigningAuthority;
import com.leanowtech.bloge.gateway.testing.api.ExternalSequenceAnchorBootstrapRootTransition;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityServingInventory;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournalTest {

    private static final String SCOPE = "stability-fleet";
    private static final String ROOT_SET = "external-notary-bootstrap-roots";
    private static final String TRUST_DOMAIN = "bootstrap-root.example";
    private static final Instant NOW = Instant.parse("2026-07-21T01:00:00Z");

    private TestRuntimeDatabase database;
    private ObjectMapper objectMapper;
    private KeyPair keyPair;
    private ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial rootKey;
    private ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor descriptor;
    private ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy recoveryPolicy;
    private DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal journal;

    @BeforeEach
    void setUp() throws Exception {
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-bootstrap-root-ceremony-" + UUID.randomUUID()
                        + ";DB_CLOSE_DELAY=-1", "sa", "", 8));
        objectMapper = new ObjectMapper().findAndRegisterModules();
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
        rootKey = new ExternalSequenceAnchorBootstrapRootGenesis.RootKeyMaterial(
                "root-1", "key-1", publicKey, NOW.minusSeconds(3600),
                NOW.plusSeconds(86_400), true, false);
        descriptor = new ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor(
                ExternalSequenceAnchorBootstrapRootSigningAuthority.Descriptor.SCHEMA_VERSION,
                rootKey.authorityId(), rootKey.keyId(), "Ed25519", publicKey);
        recoveryPolicy = new ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy.SCHEMA_VERSION,
                1L, 1L, 2L);
        journal = repository();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    void proposalIsExactlyIdempotentAndOnlyOneRootSetWorkflowMayBeActive() {
        var first = proposal("ceremony-a", "maker-a", 'a');

        assertThat(journal.propose(first).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition.CREATED);
        assertThat(repository().propose(first).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition
                        .IDEMPOTENT_REPLAY);
        assertThat(journal.propose(proposal("ceremony-a", "maker-b", 'a')).disposition())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .ProposalDisposition.IDEMPOTENCY_CONFLICT);

        var blocked = journal.propose(proposal("ceremony-b", "maker-b", 'b'));
        assertThat(blocked.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition
                        .ACTIVE_CEREMONY_EXISTS);
        assertThat(blocked.snapshot().ceremonyId()).isEqualTo("ceremony-a");
    }

    @Test
    void proposalAndApprovalDeadlinesExpireIntoDistinctTerminalStates()
            throws Exception {
        journal.propose(proposal("ceremony-proposal-timeout", "maker-a", 'a', 1));
        Thread.sleep(1_100L);
        assertThat(journal.snapshot("ceremony-proposal-timeout").orElseThrow().state())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal.State
                        .PROPOSAL_EXPIRED);

        var next = proposal("ceremony-approval-timeout", "maker-a", 'b');
        assertThat(journal.propose(next).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition.CREATED);
        journal.approve(approval(next.ceremonyId(), "approve-timeout", "checker-a", 1));
        Thread.sleep(1_100L);
        var expired = journal.acquire(acquisition(next.ceremonyId(), "worker-a", 30));
        assertThat(expired.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionDisposition
                        .EXPIRED);
        assertThat(expired.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVAL_EXPIRED);
        assertThat(journal.propose(proposal(
                "ceremony-after-timeouts", "maker-b", 'c')).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition.CREATED);
    }

    @Test
    void checkerMustDifferFromMakerAndApprovalReplayIsExact() {
        journal.propose(proposal("ceremony-approval", "maker-a", 'a'));
        var self = approval("ceremony-approval", "approve-1", "maker-a", 60);
        assertThat(journal.approve(self).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition
                        .SELF_APPROVAL);

        var command = approval("ceremony-approval", "approve-1", "checker-a", 60);
        var approved = journal.approve(command);
        assertThat(approved.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition.APPROVED);
        assertThat(approved.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
        assertThat(repository().approve(command).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalDisposition
                        .IDEMPOTENT_REPLAY);
        assertThat(journal.approve(approval(
                "ceremony-approval", "approve-1", "checker-b", 60)).disposition())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .ApprovalDisposition.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void liveLeaseIsExclusiveAndExpiredLeaseUsesAMonotonicTakeoverFence()
            throws Exception {
        CeremonyProposalFixture fixture = approved("ceremony-takeover", 'a');
        var first = journal.acquire(acquisition(fixture.id(), "worker-a", 1));
        assertThat(first.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionDisposition
                        .ACQUIRED);
        assertThat(repository().acquire(acquisition(
                fixture.id(), "worker-b", 30)).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionDisposition.BUSY);

        Thread.sleep(1_100L);
        var second = repository().acquire(acquisition(fixture.id(), "worker-b", 30));
        assertThat(second.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionDisposition
                        .ACQUIRED);
        assertThat(second.claim().claimVersion()).isEqualTo(2L);
        assertThat(second.snapshot().attemptCount()).isEqualTo(2L);

        assertThat(journal.complete(first.claim(), outcome(fixture.proposal())).disposition())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .CompletionDisposition.FENCE_REJECTED);
        assertThat(journal.complete(second.claim(), outcome(fixture.proposal())).disposition())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .CompletionDisposition.PRODUCED);
    }

    @Test
    void heartbeatIssuesSuccessorFenceAndOnlyExactlyReplaysItsLatestCommand() {
        CeremonyProposalFixture fixture = approved("ceremony-heartbeat", 'a');
        var acquisition = journal.acquire(acquisition(fixture.id(), "worker-a", 3));
        var command = heartbeat("heartbeat-1", acquisition.claim(), 30);

        var renewed = journal.heartbeat(command);

        assertThat(renewed.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition.RENEWED);
        assertThat(renewed.claim().claimVersion()).isEqualTo(2L);
        assertThat(renewed.claim().claimUntil()).isAfter(acquisition.claim().claimUntil());
        assertThat(renewed.snapshot().attemptCount()).isEqualTo(1L);
        assertThat(renewed.snapshot().heartbeatCount()).isEqualTo(1L);
        assertThat(renewed.snapshot().heartbeatRequestId()).isEqualTo("heartbeat-1");
        assertThat(renewed.snapshot().heartbeatAt()).isNotNull();

        var replay = repository().heartbeat(command);
        assertThat(replay.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition
                        .IDEMPOTENT_REPLAY);
        assertThat(replay.claim()).isEqualTo(renewed.claim());
        assertThat(journal.heartbeat(heartbeat(
                "heartbeat-1", acquisition.claim(), 31)).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition
                        .IDEMPOTENCY_CONFLICT);
        assertThat(journal.complete(acquisition.claim(), outcome(fixture.proposal()))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition
                        .FENCE_REJECTED);
        assertThat(journal.complete(renewed.claim(), outcome(fixture.proposal()))
                .disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition
                        .PRODUCED);
    }

    @Test
    void retryAttemptClearsHeartbeatReplaySlotAndStartsItsOwnBoundedCount() {
        CeremonyProposalFixture fixture = approved("ceremony-heartbeat-retry", 'a');
        var first = journal.acquire(acquisition(fixture.id(), "worker-a", 3));
        var firstCommand = heartbeat("heartbeat-first", first.claim(), 30);
        var renewed = journal.heartbeat(firstCommand);
        journal.release(renewed.claim(),
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        var retry = journal.acquire(acquisition(fixture.id(), "worker-b", 30));

        assertThat(retry.snapshot().attemptCount()).isEqualTo(2L);
        assertThat(retry.snapshot().heartbeatCount()).isZero();
        assertThat(retry.snapshot().heartbeatRequestId()).isEmpty();
        assertThat(retry.snapshot().heartbeatFingerprint()).isEmpty();
        assertThat(retry.snapshot().heartbeatAt()).isNull();
        assertThat(journal.heartbeat(firstCommand).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition
                        .FENCE_REJECTED);
    }

    @Test
    void heartbeatCannotExtendAnElapsedApprovalDeadline() throws Exception {
        var proposal = proposal("ceremony-heartbeat-expired", "maker-a", 'a');
        journal.propose(proposal);
        journal.approve(approval(proposal.ceremonyId(), "approve-heartbeat-expired",
                "checker-a", 1));
        var claim = journal.acquire(acquisition(proposal.ceremonyId(), "worker-a", 1)).claim();

        Thread.sleep(1_100L);
        var expired = journal.heartbeat(heartbeat("heartbeat-expired", claim, 30));

        assertThat(expired.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatDisposition.EXPIRED);
        assertThat(expired.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVAL_EXPIRED);
        assertThat(expired.claim()).isNull();
    }

    @Test
    void boundedFailureReopensApprovalAndStaleWorkerCannotReleaseTheRetry() {
        CeremonyProposalFixture fixture = approved("ceremony-retry", 'b');
        var first = journal.acquire(acquisition(fixture.id(), "worker-a", 30));
        var released = journal.release(first.claim(),
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        assertThat(released.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition.RELEASED);
        assertThat(released.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVED);
        assertThat(released.snapshot().lastFailure()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        var retry = journal.acquire(acquisition(fixture.id(), "worker-b", 30));
        assertThat(retry.claim().claimVersion()).isEqualTo(2L);
        assertThat(journal.release(first.claim(),
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNER_BINDING_INVALID).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition
                        .FENCE_REJECTED);
    }

    @Test
    void exactClaimPersistsApprovalExpiryWithoutPostFenceFailureAttribution()
            throws Exception {
        var proposal = proposal("ceremony-expired-release", "maker-a", 'b');
        journal.propose(proposal);
        journal.approve(approval(proposal.ceremonyId(), "approve-expired-release",
                "checker-a", 1));
        var claim = journal.acquire(acquisition(
                proposal.ceremonyId(), "worker-a", 1)).claim();

        Thread.sleep(1_100L);
        var expired = journal.release(claim,
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        assertThat(expired.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.FailureDisposition.EXPIRED);
        assertThat(expired.snapshot().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.APPROVAL_EXPIRED);
        assertThat(expired.snapshot().lastFailure()).isNull();
        assertThat(expired.snapshot().lastFailedAt()).isNull();
    }

    @Test
    void terminalOutcomeReplaysExactlyAndRejectsChangedOutcome() {
        CeremonyProposalFixture fixture = approved("ceremony-terminal", 'c');
        var claim = journal.acquire(acquisition(fixture.id(), "worker-a", 30)).claim();
        var outcome = outcome(fixture.proposal());

        assertThat(journal.complete(claim, outcome).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition
                        .PRODUCED);
        assertThat(repository().complete(claim, outcome).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition
                        .IDEMPOTENT_REPLAY);
        assertThat(journal.complete(claim,
                changedAttempts(outcome)).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CompletionDisposition
                        .OUTCOME_CONFLICT);
        assertThat(journal.snapshot(fixture.id()).orElseThrow().state()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.State.PRODUCED);
    }

    @Test
    void nextProposalMustExactlyExtendTheLatestProducedJournalHead() {
        CeremonyProposalFixture fixture = approved("ceremony-head-one", 'c');
        var firstOutcome = outcome(fixture.proposal());
        var claim = journal.acquire(acquisition(fixture.id(), "worker-a", 30)).claim();
        journal.complete(claim, firstOutcome);

        var stale = journal.propose(proposal("ceremony-stale-head", "maker-b", 'd'));
        assertThat(stale.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition
                        .CHAIN_CONFLICT);
        assertThat(stale.snapshot().ceremonyId()).isEqualTo(fixture.id());

        var successor = successorProposal("ceremony-head-two", "maker-b", 'e',
                firstOutcome.bundle());
        assertThat(journal.propose(successor).disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition.CREATED);
        assertThat(journal.snapshot(successor.ceremonyId()).orElseThrow()
                .proposal().currentBundle()).isEqualTo(firstOutcome.bundle());
    }

    @Test
    void restartPreservesProjectionAndOfflineCorruptionFailsClosed() {
        CeremonyProposalFixture fixture = approved("ceremony-corrupt", 'd');
        var restarted = repository();
        assertThat(restarted.snapshot(fixture.id())).isPresent().get()
                .extracting(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .CeremonySnapshot::checkerId)
                .isEqualTo("checker-a");

        database.jdbc().update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_ceremonies
                SET attempt_count = attempt_count + 1
                WHERE scope_id = ? AND root_set_id = ? AND ceremony_id = ?
                """, SCOPE, ROOT_SET, fixture.id());
        assertThatThrownBy(() -> restarted.snapshot(fixture.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }

    @Test
    void initializationMigratesOnlyAnExactlyValidLegacyRowFingerprint() {
        CeremonyProposalFixture fixture = approved("ceremony-legacy-fingerprint", 'd');
        var snapshot = journal.snapshot(fixture.id()).orElseThrow();
        String currentFingerprint = snapshot.recordFingerprint();
        String legacyFingerprint = ProtocolFingerprint.of(objectMapper,
                new LegacyIntegrityMaterial(
                        "bloge.externalSequenceAnchorBootstrapRootCeremonyJournalRecord.v1",
                        SCOPE, ROOT_SET, fixture.id(), snapshot.state(), snapshot.proposal(),
                        snapshot.proposalFingerprint(), snapshot.submittedAt(),
                        snapshot.proposalUntil(), snapshot.approvalRequestId(),
                        snapshot.approvalFingerprint(), snapshot.checkerId(),
                        snapshot.approvedAt(), snapshot.approvalUntil(), snapshot.claimOwner(),
                        snapshot.claimVersion(), snapshot.claimUntil(), snapshot.attemptCount(),
                        snapshot.lastFailure(), snapshot.lastFailedAt(), snapshot.outcome(),
                        snapshot.outcomeFingerprint(), snapshot.completedAt(),
                        snapshot.updatedAt()));
        database.jdbc().update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_ceremonies
                SET record_fingerprint = ?
                WHERE scope_id = ? AND root_set_id = ? AND ceremony_id = ?
                """, legacyFingerprint, SCOPE, ROOT_SET, fixture.id());
        for (String column : List.of("heartbeat_request_id", "heartbeat_fingerprint",
                "heartbeat_at", "heartbeat_count")) {
            database.jdbc().execute("""
                    ALTER TABLE rg_external_sequence_anchor_bootstrap_root_ceremonies
                    DROP COLUMN %s
                    """.formatted(column));
        }

        var migrated = repository().snapshot(fixture.id()).orElseThrow();

        assertThat(migrated.recordFingerprint()).isEqualTo(currentFingerprint);
        assertThat(migrated.heartbeatCount()).isZero();
        assertThat(migrated.heartbeatRequestId()).isEmpty();
    }

    @Test
    void competingFirstProposalsLinearizeToOneWinner() throws Exception {
        var left = repository();
        var right = repository();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition>
                    leftResult = workers.submit(() -> proposeAfter(start, left,
                    proposal("ceremony-left", "maker-a", 'e')));
            Future<ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition>
                    rightResult = workers.submit(() -> proposeAfter(start, right,
                    proposal("ceremony-right", "maker-b", 'f')));
            start.countDown();

            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .containsExactlyInAnyOrder(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                    .ProposalDisposition.CREATED,
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                    .ProposalDisposition.ACTIVE_CEREMONY_EXISTS);
        }
    }

    @Test
    void recoveryAcquisitionClassifiesAbsentApprovalAndLiveLeaseWithoutResolvingWork() {
        assertThat(journal.acquireRecovery(recovery("recovery-a", 30)).disposition())
                .isEqualTo(ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.NO_ACTIVE_CEREMONY);

        var proposal = proposal("ceremony-recovery-states", "maker-a", 'a');
        journal.propose(proposal);
        var awaiting = journal.acquireRecovery(recovery("recovery-a", 30));
        assertThat(awaiting.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.AWAITING_APPROVAL);
        assertThat(awaiting.snapshot().ceremonyId()).isEqualTo(proposal.ceremonyId());

        journal.approve(approval(proposal.ceremonyId(), "approve-recovery-states",
                "checker-a", 60));
        var acquired = journal.acquireRecovery(recovery("recovery-a", 30));
        var busy = repository().acquireRecovery(recovery("recovery-b", 30));
        assertThat(acquired.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.ACQUIRED);
        assertThat(busy.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.BUSY);
        assertThat(busy.eligibleAt()).isEqualTo(acquired.claim().claimUntil());
    }

    @Test
    void failedRecoveryUsesDatabaseBackoffAndStopsAtDurableAutomaticAttemptBudget()
            throws Exception {
        CeremonyProposalFixture fixture = approved("ceremony-recovery-budget", 'b');
        var first = journal.acquireRecovery(recovery("recovery-a", 30));
        journal.release(first.claim(),
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        var delayed = repository().acquireRecovery(recovery("recovery-b", 30));
        assertThat(delayed.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.RETRY_DELAYED);
        assertThat(delayed.eligibleAt()).isAfter(delayed.snapshot().lastFailedAt());

        Thread.sleep(1_100L);
        var second = repository().acquireRecovery(recovery("recovery-b", 30));
        assertThat(second.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.ACQUIRED);
        assertThat(second.snapshot().attemptCount()).isEqualTo(2L);
        journal.release(second.claim(),
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason
                        .SIGNING_QUORUM_UNAVAILABLE);

        var exhausted = repository().acquireRecovery(recovery("recovery-c", 30));
        assertThat(exhausted.disposition()).isEqualTo(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionDisposition.ATTEMPT_LIMIT_REACHED);
        assertThat(exhausted.snapshot().ceremonyId()).isEqualTo(fixture.id());
        assertThat(exhausted.snapshot().attemptCount()).isEqualTo(2L);
    }

    @Test
    void competingRecoveryReplicasAtomicallyIssueOnlyOneFence() throws Exception {
        approved("ceremony-recovery-race", 'c');
        var left = repository();
        var right = repository();
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Future<ExternalSequenceAnchorBootstrapRootCeremonyJournal
                    .RecoveryAcquisitionDisposition> leftResult = workers.submit(() -> {
                start.await();
                return left.acquireRecovery(recovery("recovery-left", 30)).disposition();
            });
            Future<ExternalSequenceAnchorBootstrapRootCeremonyJournal
                    .RecoveryAcquisitionDisposition> rightResult = workers.submit(() -> {
                start.await();
                return right.acquireRecovery(recovery("recovery-right", 30)).disposition();
            });
            start.countDown();

            assertThat(List.of(leftResult.get(), rightResult.get()))
                    .containsExactlyInAnyOrder(
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                    .RecoveryAcquisitionDisposition.ACQUIRED,
                            ExternalSequenceAnchorBootstrapRootCeremonyJournal
                                    .RecoveryAcquisitionDisposition.BUSY);
        }
        assertThat(journal.snapshot("ceremony-recovery-race").orElseThrow().attemptCount())
                .isEqualTo(1L);
    }

    @Test
    void recoveryPolicyDriftAndOfflineBindingTamperFailClosed() {
        var conflictingPolicy = new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .RecoveryPolicy(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.RecoveryPolicy.SCHEMA_VERSION,
                2L, 2L, 2L);
        var conflicting = new DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
                database.jdbc(), objectMapper, SCOPE, ROOT_SET,
                database.transactionManager(), conflictingPolicy);
        assertThatThrownBy(conflicting::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("policy conflicts");

        database.jdbc().update("""
                UPDATE rg_external_sequence_anchor_bootstrap_root_ceremony_locks
                SET recovery_policy_fingerprint = ?
                WHERE scope_id = ? AND root_set_id = ?
                """, fingerprint('f'), SCOPE, ROOT_SET);
        assertThatThrownBy(() -> journal.acquireRecovery(recovery("recovery-a", 30)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing or corrupt");
    }

    private CeremonyProposalFixture approved(String ceremonyId, char marker) {
        var proposal = proposal(ceremonyId, "maker-a", marker);
        journal.propose(proposal);
        journal.approve(approval(ceremonyId, "approve-" + ceremonyId,
                "checker-a", 60));
        return new CeremonyProposalFixture(ceremonyId, proposal);
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal(
            String ceremonyId, String makerId, char marker) {
        return proposal(ceremonyId, makerId, marker, 300);
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal(
            String ceremonyId, String makerId, char marker, long proposalDurationSeconds) {
        String predecessor = fingerprint('0');
        String materialFingerprint = fingerprint(marker);
        var request = new ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                ceremonyId, predecessor, List.of(rootKey), fingerprint('9'), NOW, NOW,
                NOW.plusSeconds(3600));
        var preflight = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyPreflight(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight
                        .SCHEMA_VERSION,
                ceremonyId, 1L, materialFingerprint, NOW.plusSeconds(300),
                List.of(descriptor),
                List.of(descriptor));
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal
                        .SCHEMA_VERSION,
                request, null, preflight, makerId, proposalDurationSeconds);
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal
            successorProposal(
            String ceremonyId,
            String makerId,
            char marker,
            ExternalSequenceAnchorBootstrapRootBundle currentBundle) {
        long sequence = currentBundle.transitions().getLast().material().sequence() + 1L;
        var request = new ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.RotationRequest
                        .SCHEMA_VERSION,
                ceremonyId, currentBundle.headMaterialFingerprint(), List.of(rootKey),
                fingerprint('9'), NOW, NOW, NOW.plusSeconds(3600));
        var preflight = new ExternalSequenceAnchorBootstrapRootCeremonyProducer
                .CeremonyPreflight(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyPreflight
                        .SCHEMA_VERSION,
                ceremonyId, sequence, fingerprint(marker), NOW.plusSeconds(300),
                List.of(descriptor),
                List.of(descriptor));
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal
                        .SCHEMA_VERSION,
                request, currentBundle, preflight, makerId, 300);
    }

    private ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome(
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal) {
        String materialFingerprint = proposal.preflight().materialFingerprint();
        var material = new ExternalSequenceAnchorBootstrapRootTransition.Material(
                ExternalSequenceAnchorBootstrapRootTransition.Material.SCHEMA_VERSION,
                ROOT_SET, 1L, proposal.request().expectedPreviousMaterialFingerprint(),
                SCOPE, TRUST_DOMAIN, 1, 0, List.of(rootKey), fingerprint('9'), NOW, NOW,
                NOW.plusSeconds(3600));
        var signature = new TestSuiteStabilityServingInventory.AuthoritySignature(
                descriptor.authorityId(), descriptor.keyId(), "Ed25519", NOW,
                Base64.getEncoder().encodeToString(new byte[64]));
        var transition = new ExternalSequenceAnchorBootstrapRootTransition(
                ExternalSequenceAnchorBootstrapRootTransition.SCHEMA_VERSION,
                material, materialFingerprint, List.of(signature), List.of(signature));
        var bundle = new ExternalSequenceAnchorBootstrapRootBundle(
                ExternalSequenceAnchorBootstrapRootBundle.SCHEMA_VERSION,
                proposal.request().expectedPreviousMaterialFingerprint(),
                List.of(transition), materialFingerprint);
        return new ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome(
                ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
                        .SCHEMA_VERSION,
                proposal.ceremonyId(), bundle, List.of(
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role
                                .AUTHORIZING_ROOT,
                        descriptor.authorityId(), descriptor.keyId(),
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED),
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        ExternalSequenceAnchorBootstrapRootSigningAuthority.Role.INCOMING_ROOT,
                        descriptor.authorityId(), descriptor.keyId(),
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus.SIGNED)));
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome
            changedAttempts(
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome value) {
        var changed = value.signingAttempts().stream().map(attempt ->
                new ExternalSequenceAnchorBootstrapRootCeremonyProducer.SigningAttempt(
                        attempt.role(), attempt.authorityId(), attempt.keyId(),
                        ExternalSequenceAnchorBootstrapRootCeremonyProducer.AttemptStatus
                                .UNAVAILABLE)).toList();
        return new ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome(
                value.schemaVersion(), value.ceremonyId(), value.bundle(), changed);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand approval(
            String ceremonyId, String requestId, String checkerId, long duration) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.ApprovalCommand
                        .SCHEMA_VERSION,
                ceremonyId, requestId, checkerId, duration);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand
            acquisition(String ceremonyId, String workerId, long duration) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.AcquisitionCommand
                        .SCHEMA_VERSION,
                ceremonyId, workerId, duration);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal
            .RecoveryAcquisitionCommand recovery(String workerId, long duration) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal
                .RecoveryAcquisitionCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal
                        .RecoveryAcquisitionCommand.SCHEMA_VERSION,
                workerId, duration);
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand heartbeat(
            String requestId,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.ExecutionClaim claim,
            long duration) {
        return new ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand(
                ExternalSequenceAnchorBootstrapRootCeremonyJournal.HeartbeatCommand
                        .SCHEMA_VERSION,
                requestId, claim, duration);
    }

    private DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal repository() {
        var result = new DatabaseExternalSequenceAnchorBootstrapRootCeremonyJournal(
                database.jdbc(), objectMapper, SCOPE, ROOT_SET,
                database.transactionManager(), recoveryPolicy);
        result.init();
        return result;
    }

    private static ExternalSequenceAnchorBootstrapRootCeremonyJournal.ProposalDisposition
            proposeAfter(
            CountDownLatch start,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal target,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal)
            throws InterruptedException {
        start.await();
        return target.propose(proposal).disposition();
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private record CeremonyProposalFixture(
            String id,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal) {
    }

    private record LegacyIntegrityMaterial(
            String schemaVersion,
            String scopeId,
            String rootSetId,
            String ceremonyId,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.State state,
            ExternalSequenceAnchorBootstrapRootCeremonyJournal.CeremonyProposal proposal,
            String proposalFingerprint,
            Instant submittedAt,
            Instant proposalUntil,
            String approvalRequestId,
            String approvalFingerprint,
            String checkerId,
            Instant approvedAt,
            Instant approvalUntil,
            String claimOwner,
            long claimVersion,
            Instant claimUntil,
            long attemptCount,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.FailureReason lastFailure,
            Instant lastFailedAt,
            ExternalSequenceAnchorBootstrapRootCeremonyProducer.CeremonyOutcome outcome,
            String outcomeFingerprint,
            Instant completedAt,
            Instant updatedAt) {
    }
}
