package com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence;

import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringEndpoint;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.AuthoringScope;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandKey;
import com.leanowtech.bloge.gateway.visual.authoring.resource.persistence.CommandLease;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * H2/PostgreSQL-mode evidence for the durable pending-secret protocol.
 *
 * <p>This is a database-backed parity harness rather than a subclass of the
 * pure {@link PendingSecretStoreContractTest}: the JDBC schema intentionally
 * keeps one authoritative journal row per {@code command_id}, uses the database
 * clock for lease decisions, and requires the coordinator's ambient transaction
 * for the final binding commit. The tests below port every store-level contract
 * case that is meaningful under those durable boundaries; the pure value-object
 * cases remain in the shared contract. Additional JDBC cases exercise immutable
 * attempt history, two-connection binding contention, rollback release, and
 * concurrent recovery claims.</p>
 */
class JdbcPendingSecretStoreTest {
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final Instant NOW = Instant.now();
    private DataSource dataSource;
    private String databaseUrl;
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        databaseUrl = "jdbc:h2:mem:pending-jdbc-" + System.nanoTime()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        dataSource = new DriverManagerDataSource(databaseUrl, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        migrate("db/postgresql/V20260830_001__api_resource_authoring.sql");
        migrate("db/postgresql/V20260830_002__api_resource_concurrent_staging.sql");
        migrate("db/postgresql/V20260830_003__api_connection_secret_staging.sql");
        migrate("db/postgresql/V20260830_004__connection_metadata_authority.sql");
        migrate("db/postgresql/V20260830_005__pending_secret_store_protocol.sql");
        migrate("db/postgresql/V20260830_006__pending_secret_store_hardening.sql");
        migrate("db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql");
        migrate("db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql");
        migrate("db/postgresql/V20260831_009__authoring_command_attempt_authority.sql");
        migrate("db/postgresql/V20260831_010__attempt_provenance_closure.sql");
    }

    @AfterEach
    void tearDown() { if (jdbc != null) jdbc.execute("DROP ALL OBJECTS"); }


    @Test
    void commitRequiresAmbientTransactionAndRollbackKeepsAllRows() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("tx", 1, "tx-token", NOW.plusSeconds(60)), "token", "password");
        seed(batch);
        store.stage(batch);
        List<ActivatedSecretSlot> activated = List.of(activation("token", "tx-token", "active-token"),
                activation("password", "tx-token", "active-password"));
        assertThatThrownBy(() -> store.commitBindings(batch, activated)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.TRANSACTION_REQUIRED);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            store.commitBindings(batch, activated);
            throw new IllegalStateException("rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Long.class)).isZero();
        transactions.executeWithoutResult(status -> store.commitBindings(batch, activated));
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isPresent();
        FinalizedSecretSlots replay = transactions.execute(status -> store.commitBindings(batch, activated));
        assertThat(replay).isNotNull();
    }

    @Test
    void keepExistingReadsExactPriorRevisionAndDoesNotPersistProviderSentinelsAsBinding() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch old = batchAtRevision(leaseAtRevision("old", 1, "old-token", NOW.plusSeconds(60), 2), 2, "token");
        seed(old); store.stage(old);
        transactions.executeWithoutResult(status -> store.commitBindings(old,
                List.of(activation("token", "old-token", "old-active"))));
        CommandLease nextLease = leaseAtRevision("next", 1, "next-token", NOW.plusSeconds(60), 3);
        PendingSecretBatch next = new PendingSecretBatch(new PendingSecretLease(nextLease,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 3), ExpectedRevision.match(2)),
                List.of(new PendingSecretOperation.Retained("token",
                        new ConnectionRevisionCoordinate(SCOPE, "connection", 2))));
        seed(next); store.stage(next);
        transactions.executeWithoutResult(status -> store.commitBindings(next, List.of()));
        assertThat(store.findActive(next.lease().coordinate(), "token")).contains(
                new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding(
                        "provider:one", "old-active", "next"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", Long.class, "next")).isZero();
    }

    @Test
    void nestedResourceLeaseUsesOuterJournalCasAndChildCreateCasAcrossRecovery() {
        JdbcPendingSecretStore store = store();
        CommandKey outerKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "resource", "idempotency-nested-jdbc");
        CommandLease outer = new CommandLease("nested-jdbc", 1, "nested-jdbc-token", outerKey,
                "sha256:" + "a".repeat(64), NOW.plusSeconds(60), ExpectedRevision.match(7));
        PendingSecretBatch batch = nestedBatch(outer, "connection", 1, ExpectedRevision.create());
        seed(batch);
        store.stage(batch);

        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(store.prepareFinalization(batch,
                List.of(activation("token", "nested-jdbc-token", "nested-jdbc-active"))))
                .isNotNull();
        transactions.executeWithoutResult(status -> store.commitBindings(batch,
                List.of(activation("token", "nested-jdbc-token", "nested-jdbc-active"))));
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isPresent();

        CommandKey recoveryKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "resource-recovery", "idempotency-nested-recovery-jdbc");
        PendingSecretBatch recoveryBatch = nestedBatch(new CommandLease("nested-recovery", 1,
                "nested-recovery-token", recoveryKey, "sha256:" + "b".repeat(64), NOW.plusSeconds(60),
                ExpectedRevision.match(8)), "connection-recovery", 1, ExpectedRevision.create());
        seed(recoveryBatch);
        store.stage(recoveryBatch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id=?", "nested-recovery");
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        assertThat(candidate.batch()).isEqualTo(recoveryBatch);
        store.completeAbort(candidate);
        assertThat(store.findExact(recoveryBatch.lease())).isEmpty();
    }

    @Test
    void takeoverKeepsOldAttemptRecoverableAndHydratesOuterCasFromImmutableAuthority() {
        JdbcPendingSecretStore store = store();
        CommandKey outerKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE,
                "resource-takeover", "idempotency-resource-takeover");
        String fingerprint = "sha256:" + "a".repeat(64);
        CommandLease old = new CommandLease("resource-takeover", 1, "old-attempt", outerKey,
                fingerprint, NOW.plusSeconds(60), ExpectedRevision.match(7));
        PendingSecretBatch oldBatch = nestedBatch(old, "connection-takeover", 1, ExpectedRevision.create());
        seed(oldBatch);
        store.stage(oldBatch);
        jdbc.update("UPDATE rg_api_connection_revisions SET state='STAGED' WHERE command_id=?"
                + " AND attempt_no=1 AND attempt_token=?", old.commandId(), old.attemptToken());

        CommandLease replacement = new CommandLease("resource-takeover", 2, "new-attempt", outerKey,
                fingerprint, NOW.plusSeconds(120), ExpectedRevision.match(8));
        PendingSecretBatch replacementBatch = nestedBatch(replacement, "connection-takeover", 1,
                ExpectedRevision.create());
        insertAttemptAndPointJournal(replacement);
        insertCommittedConnectionRevision(replacement, replacementBatch.lease().coordinate());

        // The old pending row is still reconstructable after the journal's
        // current pointer moves to the replacement attempt.
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until="
                + "CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE command_id=? AND attempt_no=1",
                old.commandId());
        assertThat(store.findExact(oldBatch.lease())).contains(oldBatch);
        store.stage(replacementBatch);

        SecretAbortCandidate oldCandidate = store.claimRecoveryDue(1).getFirst();
        assertThat(oldCandidate.batch()).isEqualTo(oldBatch);
        store.completeAbort(oldCandidate);
        assertThat(store.findExact(oldBatch.lease())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT outcome FROM rg_api_connection_pending_secret_outcomes"
                + " WHERE command_id=? AND attempt_no=1", String.class, old.commandId())).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_attempts"
                + " WHERE command_id=? AND attempt_no=1", String.class, old.commandId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_attempts"
                + " WHERE command_id=? AND attempt_no=2", String.class, replacement.commandId())).isEqualTo("PREPARING");

        assertThat(store.findExact(replacementBatch.lease())).contains(replacementBatch);
        transactions.executeWithoutResult(status -> store.commitBindings(replacementBatch,
                List.of(activation("token", "new-attempt", "new-active"))));
        assertThat(store.findActive(replacementBatch.lease().coordinate(), "token")).isPresent();
        assertThat(jdbc.queryForObject("SELECT outcome FROM rg_api_connection_pending_secret_outcomes"
                + " WHERE command_id=? AND attempt_no=2", String.class, replacement.commandId())).isEqualTo("COMMITTED");
    }

    @Test
    void recoveryClaimsWholeBatchAndTerminalAbortReplayIsExact() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("recover", 1, "recover-token", NOW.plusSeconds(60)), "token", "password");
        seed(batch); store.stage(batch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=? WHERE command_id=?",
                java.sql.Timestamp.from(NOW.minusSeconds(60)), "recover");
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        assertThat(candidate.batch().operations()).hasSize(2);
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findExact(batch.lease())).isEmpty();
        assertThat(store.claimRecoveryDue(1)).isEmpty();
    }

    @Test
    void recoveryLimitSelectsCompleteBatchesInStableOrderBeforeLoadingSlots() {
        JdbcPendingSecretStore store = store();
        for (String id : List.of("batch-b", "batch-a", "batch-c")) {
            PendingSecretBatch batch = batch(lease(id, 1, id + "-token", NOW.plusSeconds(60)), "password", "token");
            seed(batch);
            store.stage(batch);
            jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=? WHERE command_id=?",
                    java.sql.Timestamp.from(NOW.minusSeconds(60)), id);
        }

        List<SecretAbortCandidate> candidates = store.claimRecoveryDue(2);

        assertThat(candidates).extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                .containsExactly("batch-a", "batch-b");
        assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.batch().operations()).hasSize(2));
    }

    @Test
    void recoveryLimitExcludesStillClaimedBatchesBeforeSelectingLaterFreeBatch() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch claimedBatch = batch(lease("claimed-first", 1, "claimed-first-token", NOW.plusSeconds(60)),
                "token");
        PendingSecretBatch laterBatch = batch(lease("later-free", 1, "later-free-token", NOW.plusSeconds(60)),
                "token");
        seed(claimedBatch);
        seed(laterBatch);
        store.stage(claimedBatch);
        store.stage(laterBatch);
        java.sql.Timestamp expired = java.sql.Timestamp.from(NOW.minusSeconds(60));
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=? WHERE command_id IN (?, ?)",
                expired, "claimed-first", "later-free");

        SecretAbortCandidate first = store.claimRecoveryDue(1).getFirst();
        assertThat(first.batch().lease().commandLease().commandId()).isEqualTo("claimed-first");

        assertThat(store.claimRecoveryDue(1)).singleElement()
                .extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                .isEqualTo("later-free");
    }

    @Test
    void competingCommandCannotOverwriteAnAlreadyCommittedBinding() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch first = batch(lease("binding-winner", 1, "binding-winner-token", NOW.plusSeconds(60)), "token");
        PendingSecretBatch competing = batch(lease("binding-competing", 1, "binding-competing-token", NOW.plusSeconds(60)), "token");
        seed(first);
        seed(competing);
        store.stage(first);
        store.stage(competing);
        transactions.executeWithoutResult(status -> store.commitBindings(first,
                List.of(activation("token", "binding-winner-token", "binding-winner-active"))));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.commitBindings(competing,
                List.of(activation("token", "binding-competing-token", "binding-competing-active")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
        assertThat(store.findActive(first.lease().coordinate(), "token")).contains(
                new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActiveSecretBinding(
                        "provider:one", "binding-winner-active", "binding-winner"));
    }

    @Test
    void concurrentJdbcConnectionsHaveOneBindingWinnerWithoutOverwriting() throws Exception {
        DataSource firstDataSource = newConnection();
        DataSource secondDataSource = newConnection();
        JdbcPendingSecretStore firstStore = storeOn(firstDataSource);
        JdbcPendingSecretStore secondStore = storeOn(secondDataSource);
        PendingSecretBatch first = batch(lease("binding-concurrent-a", 1,
                "binding-concurrent-a-token", NOW.plusSeconds(60)), "token");
        PendingSecretBatch second = batch(lease("binding-concurrent-b", 1,
                "binding-concurrent-b-token", NOW.plusSeconds(60)), "token");
        seed(first);
        seed(second);
        firstStore.stage(first);
        secondStore.stage(second);

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = workers.submit(() -> concurrentCommit(firstDataSource, firstStore, first, start));
            Future<String> secondResult = workers.submit(() -> concurrentCommit(secondDataSource, secondStore, second, start));
            List<String> results = List.of(firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder("COMMITTED", "LEASE_FENCED");
            String owner = jdbc.queryForObject("SELECT command_id FROM rg_api_connection_secret_bindings"
                    + " WHERE tenant_id=? AND project_id=? AND environment_id=? AND connection_id=?"
                    + " AND revision=? AND slot=?", String.class, SCOPE.tenantId(), SCOPE.projectId(),
                    SCOPE.environmentId(), "connection", 3L, "token");
            assertThat(owner).isIn(first.lease().commandLease().commandId(), second.lease().commandLease().commandId());
            String locator = jdbc.queryForObject("SELECT active_locator FROM rg_api_connection_secret_bindings"
                    + " WHERE command_id=? AND slot='token'", String.class, owner);
            assertThat(locator).isEqualTo(owner + "-active");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings"
                    + " WHERE connection_id='connection' AND revision=3 AND slot='token'", Long.class)).isOne();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void rolledBackBindingWinnerReleasesParentForCompetingConnection() throws Exception {
        DataSource firstDataSource = newConnection();
        DataSource secondDataSource = newConnection();
        JdbcPendingSecretStore firstStore = storeOn(firstDataSource);
        JdbcPendingSecretStore secondStore = storeOn(secondDataSource);
        PendingSecretBatch first = batch(lease("binding-rollback-a", 1,
                "binding-rollback-a-token", NOW.plusSeconds(60)), "token");
        PendingSecretBatch second = batch(lease("binding-rollback-b", 1,
                "binding-rollback-b-token", NOW.plusSeconds(60)), "token");
        seed(first);
        seed(second);
        firstStore.stage(first);
        secondStore.stage(second);

        CountDownLatch firstWrote = new CountDownLatch(1);
        CountDownLatch secondAttempting = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> rolledBack = workers.submit(() -> transactions(firstDataSource).executeWithoutResult(status -> {
                firstStore.commitBindings(first,
                        List.of(activation("token", "binding-rollback-a-token", "binding-rollback-a-active")));
                firstWrote.countDown();
                await(secondAttempting);
                throw new IllegalStateException("intentional binding rollback");
            }));
            assertThat(firstWrote.await(10, TimeUnit.SECONDS)).isTrue();
            Future<?> competing = workers.submit(() -> transactions(secondDataSource).executeWithoutResult(status -> {
                secondAttempting.countDown();
                secondStore.commitBindings(second,
                        List.of(activation("token", "binding-rollback-b-token", "binding-rollback-b-active")));
            }));

            assertThatThrownBy(() -> rolledBack.get(10, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            competing.get(10, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT command_id FROM rg_api_connection_secret_bindings"
                    + " WHERE connection_id='connection' AND revision=3 AND slot='token'", String.class))
                    .isEqualTo(second.lease().commandLease().commandId());
            assertThat(firstStore.findExact(first.lease())).contains(first);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_outcomes"
                    + " WHERE command_id=?", Long.class, first.lease().commandLease().commandId())).isZero();
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentRecoveryWorkersClaimDistinctCompleteBatches() throws Exception {
        JdbcPendingSecretStore firstStore = storeOn(newConnection());
        JdbcPendingSecretStore secondStore = storeOn(newConnection());
        PendingSecretBatch first = batch(lease("recovery-concurrent-a", 1,
                "recovery-concurrent-a-token", NOW.plusSeconds(60)), "token", "password");
        PendingSecretBatch second = batch(lease("recovery-concurrent-b", 1,
                "recovery-concurrent-b-token", NOW.plusSeconds(60)), "token", "password");
        seed(first);
        seed(second);
        firstStore.stage(first);
        secondStore.stage(second);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id IN (?, ?)", first.lease().commandLease().commandId(),
                second.lease().commandLease().commandId());

        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SecretAbortCandidate> firstResult = workers.submit(() -> concurrentRecovery(firstStore, start));
            Future<SecretAbortCandidate> secondResult = workers.submit(() -> concurrentRecovery(secondStore, start));
            List<SecretAbortCandidate> candidates = List.of(firstResult.get(10, TimeUnit.SECONDS),
                    secondResult.get(10, TimeUnit.SECONDS));
            assertThat(candidates).extracting(candidate -> candidate.batch().lease().commandLease().commandId())
                    .containsExactlyInAnyOrder("recovery-concurrent-a", "recovery-concurrent-b");
            assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.batch().operations()).hasSize(2));
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void recoveryRetrySkipsAClaimedFirstBatchAfterBothWorkersSelectIt() throws Exception {
        CyclicBarrier selectedFirst = new CyclicBarrier(2);
        AtomicBoolean firstObserved = new AtomicBoolean();
        AtomicBoolean secondObserved = new AtomicBoolean();
        List<String> firstSelections = new CopyOnWriteArrayList<>();
        List<String> secondSelections = new CopyOnWriteArrayList<>();
        JdbcPendingSecretStore firstStore = storeOn(newConnection(), commandId -> {
            firstSelections.add(commandId);
            if (firstObserved.compareAndSet(false, true)) await(selectedFirst);
        });
        JdbcPendingSecretStore secondStore = storeOn(newConnection(), commandId -> {
            secondSelections.add(commandId);
            if (secondObserved.compareAndSet(false, true)) await(selectedFirst);
        });
        PendingSecretBatch first = batch(lease("recovery-observer-a", 1,
                "recovery-observer-a-token", NOW.plusSeconds(60)), "token");
        PendingSecretBatch second = batch(lease("recovery-observer-b", 1,
                "recovery-observer-b-token", NOW.plusSeconds(60)), "token");
        seed(first);
        seed(second);
        firstStore.stage(first);
        secondStore.stage(second);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id IN (?, ?)", first.lease().commandLease().commandId(),
                second.lease().commandLease().commandId());

        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<SecretAbortCandidate> firstResult = workers.submit(() -> firstStore.claimRecoveryDue(1).getFirst());
            Future<SecretAbortCandidate> secondResult = workers.submit(() -> secondStore.claimRecoveryDue(1).getFirst());
            List<String> claimed = List.of(
                    firstResult.get(10, TimeUnit.SECONDS).batch().lease().commandLease().commandId(),
                    secondResult.get(10, TimeUnit.SECONDS).batch().lease().commandLease().commandId());
            assertThat(claimed).containsExactlyInAnyOrder("recovery-observer-a", "recovery-observer-b");
            assertThat(firstSelections).first().isEqualTo("recovery-observer-a");
            assertThat(secondSelections).first().isEqualTo("recovery-observer-a");
            assertThat(firstSelections).doesNotHaveDuplicates();
            assertThat(secondSelections).doesNotHaveDuplicates();
            List<String> observations = new ArrayList<>(firstSelections);
            observations.addAll(secondSelections);
            assertThat(observations).containsExactlyInAnyOrder("recovery-observer-a",
                    "recovery-observer-a", "recovery-observer-b");
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void recoveryCandidateObserverFailureRollsBackTheClaim() {
        PendingSecretBatch batch = batch(lease("recovery-observer-failure", 1,
                "recovery-observer-failure-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store().stage(batch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases"
                + " SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE command_id=?",
                batch.lease().commandLease().commandId());

        JdbcPendingSecretStore failing = storeOn(newConnection(), ignored -> {
            throw new IllegalStateException("observer failure");
        });
        assertThatThrownBy(() -> failing.claimRecoveryDue(1))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
        assertThat(jdbc.queryForObject("SELECT recovery_claim_token"
                        + " FROM rg_api_connection_pending_secret_leases WHERE command_id=?",
                String.class, batch.lease().commandLease().commandId())).isNull();

        SecretAbortCandidate retry = store().claimRecoveryDue(1).getFirst();
        assertThat(retry.batch()).isEqualTo(batch);
    }

    @Test
    void recoveryClaimIsExclusiveAndReclaimFencesStaleWorker() {
        JdbcPendingSecretStore firstStore = store();
        JdbcPendingSecretStore secondStore = store();
        PendingSecretBatch batch = batch(lease("reclaim", 1, "reclaim-token", NOW.plusSeconds(60)), "token", "password");
        seed(batch); firstStore.stage(batch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id=?", "reclaim");

        SecretAbortCandidate first = firstStore.claimRecoveryDue(1).getFirst();
        assertThat(secondStore.claimRecoveryDue(1)).isEmpty();
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET recovery_claim_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id=?", "reclaim");
        SecretAbortCandidate reclaimed = secondStore.claimRecoveryDue(1).getFirst();
        assertThat(reclaimed.recoveryClaimToken()).isNotEqualTo(first.recoveryClaimToken());
        assertThatThrownBy(() -> firstStore.completeAbort(first)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        secondStore.completeAbort(reclaimed);
        secondStore.completeAbort(reclaimed);
    }

    @Test
    void stageUsesDatabaseTimeForAlreadyExpiredProviderReceipt() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("db-expired", 1, "db-expired-token", Instant.now().minusSeconds(30)), "token");
        seed(batch);
        assertThatThrownBy(() -> store.stage(batch)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_EXPIRED);
    }

    @Test
    void restoreKeepsProviderPurposeAndRejectsRequestFingerprintDrift() {
        JdbcPendingSecretStore store = store();
        CommandLease lease = lease("context", 1, "context-token", NOW.plusSeconds(60));
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "resource-save-child",
                "connection", 3, lease.commandId(), lease.attemptNo(), lease.attemptToken(), "token");
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", lease.attemptToken(), "opaque-context",
                                lease.leaseUntil(), context))));
        seed(batch); store.stage(batch);
        assertThat(store.findExact(batch.lease())).contains(batch);

        CommandLease drifted = new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(), lease.key(),
                "sha256:" + "c".repeat(64), lease.leaseUntil(), lease.expectedRevision());
        assertThat(store.findExact(new PendingSecretLease(drifted, batch.lease().coordinate(), batch.lease().connectionExpected())))
                .isEmpty();
    }

    @Test
    void terminalJournalStatusFencesEveryPendingMutation() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch failed = batch(lease("terminal-failed", 1, "terminal-failed-token", NOW.plusSeconds(60)), "token");
        seed(failed);
        store.stage(failed);
        jdbc.update("UPDATE rg_authoring_command_journal SET status='FAILED', failure_code='INTERNAL'"
                + " WHERE command_id=?", failed.lease().commandLease().commandId());
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id=?", failed.lease().commandLease().commandId());

        assertThatThrownBy(() -> store.stage(failed)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        assertThatThrownBy(() -> store.markAbortRequired(failed.lease())).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.commitBindings(failed,
                List.of(activation("token", "terminal-failed-token", "terminal-failed-active")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        assertThat(store.claimRecoveryDue(1)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", Long.class, "terminal-failed")).isEqualTo(1L);

        PendingSecretBatch committed = batch(lease("terminal-committed", 1, "terminal-committed-token", NOW.plusSeconds(60)), "token");
        seed(committed);
        jdbc.update("UPDATE rg_authoring_command_journal SET status='COMMITTED', receipt_schema='test-v1',"
                + " receipt_json='{}', receipt_fingerprint=?, receipt_etag='\"terminal-etag\"', failure_code=NULL"
                + " WHERE command_id=?", "sha256:" + "d".repeat(64), "terminal-committed");
        assertThatThrownBy(() -> store.stage(committed)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
    }

    @Test
    void rowDeadlineIsEarlierOfCommandAndProviderExpiryWhileReceiptRoundTrips() {
        JdbcPendingSecretStore store = store();
        CommandLease lease = lease("deadline", 1, "deadline-token", NOW.plusSeconds(5));
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 3, lease.commandId(), lease.attemptNo(), lease.attemptToken(), "token");
        Instant providerExpiry = NOW.plusSeconds(60);
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 3), lease.expectedRevision()),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", lease.attemptToken(), "opaque-deadline",
                                providerExpiry, context))));
        seed(batch); store.stage(batch);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT lease_until FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", java.sql.Timestamp.class, "deadline").toInstant())
                .isBefore(providerExpiry);
    }

    @Test
    void stagedOpaqueValuesNeverBecomePlaintextOrJsonPayload() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("opaque", 1, "opaque-token", NOW.plusSeconds(60)), "token");
        seed(batch); store.stage(batch);
        assertThat(jdbc.queryForObject("SELECT opaque_handle FROM rg_api_connection_pending_secret_leases"
                + " WHERE command_id=?", String.class, "opaque")).isEqualTo("opaque-locator-token");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_outcomes", Long.class)).isZero();
    }

    @Test
    void stageAndFindExactKeepActiveBindingsInvisible() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("visibility-jdbc", 1, "visibility-jdbc-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);

        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).isEmpty();
    }

    @Test
    void exactReentryIsIdempotentButDriftIsIntegrity() {
        JdbcPendingSecretStore store = store();
        CommandLease lease = lease("reentry-jdbc", 1, "reentry-jdbc-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batch(lease, "password", "token");
        seed(batch);
        store.stage(batch);
        store.stage(batch(lease, "token", "password"));

        assertThatThrownBy(() -> store.stage(batchWithLocator(lease, "token", "different-locator")))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test
    void partialActivationAndWrongProviderLeavePendingAndActiveRowsUntouched() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("partial-jdbc", 1, "partial-jdbc-token", NOW.plusSeconds(60)),
                "token", "password");
        seed(batch);
        store.stage(batch);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.commitBindings(batch,
                List.of(activation("token", "partial-jdbc-token", "active-token")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.commitBindings(batch,
                List.of(new ActivatedSecretSlot("token",
                        new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret(
                                "other-provider", "partial-jdbc-token", "active-token")),
                        activation("password", "partial-jdbc-token", "active-password")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Long.class)).isZero();
    }

    @Test
    void extraActivationOutputIsRejectedWithoutWrites() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("extra-jdbc", 1, "extra-jdbc-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> store.commitBindings(batch,
                List.of(activation("token", "extra-jdbc-token", "active-token"),
                        activation("password", "extra-jdbc-token", "unexpected")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.ACTIVATION_MISMATCH);
        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Long.class)).isZero();
    }

    @Test
    void prepareIsReadOnlyAndCommitReturnsThePreparedProof() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("prepare-jdbc", 1, "prepare-jdbc-token", NOW.plusSeconds(60)),
                "token", "password");
        List<ActivatedSecretSlot> outputs = List.of(activation("token", "prepare-jdbc-token", "active-token"),
                activation("password", "prepare-jdbc-token", "active-password"));
        seed(batch);
        store.stage(batch);

        FinalizedSecretSlots prepared = store.prepareFinalization(batch, outputs);

        assertThat(store.findExact(batch.lease())).contains(batch);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings", Long.class)).isZero();
        FinalizedSecretSlots committed = transactions.execute(status -> store.commitBindings(batch, outputs));
        assertThat(committed).isEqualTo(prepared);
    }

    @Test
    void commitReplayIsExactAndDriftDoesNotOverwriteBinding() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("replay-jdbc", 1, "replay-jdbc-token", NOW.plusSeconds(60)), "token");
        List<ActivatedSecretSlot> outputs = List.of(activation("token", "replay-jdbc-token", "active"));
        seed(batch);
        store.stage(batch);
        FinalizedSecretSlots first = transactions.execute(status -> store.commitBindings(batch, outputs));

        FinalizedSecretSlots replay = transactions.execute(status -> store.commitBindings(batch, outputs));
        assertThat(replay).isEqualTo(first);
        assertThatThrownBy(() -> transactions.execute(status -> store.commitBindings(batch,
                List.of(activation("token", "replay-jdbc-token", "different")))))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
        assertThat(store.findActive(batch.lease().coordinate(), "token")).hasValueSatisfying(binding ->
                assertThat(binding.activeLocator()).isEqualTo("active"));
    }

    @Test
    void createAndWrongCasCannotUseKeepExisting() {
        JdbcPendingSecretStore store = store();
        CommandLease create = leaseAtRevision("create-jdbc", 1, "create-jdbc-token", NOW.plusSeconds(60), 1);
        PendingSecretBatch createKeep = new PendingSecretBatch(new PendingSecretLease(create,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 1), create.expectedRevision()),
                List.of(new PendingSecretOperation.Retained("token",
                        new ConnectionRevisionCoordinate(SCOPE, "connection", 1))));
        assertThatThrownBy(() -> store.stage(createKeep)).isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);

        CommandKey wrongKey = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "connection", "idempotency-wrong-cas-jdbc");
        CommandLease wrongCas = new CommandLease("wrong-cas-jdbc", 1, "wrong-cas-jdbc-token", wrongKey,
                "sha256:" + "e".repeat(64), NOW.plusSeconds(60), ExpectedRevision.match(1));
        assertThatThrownBy(() -> store.stage(batchAtRevision(wrongCas, 3, "token")))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test
    void staleLeaseCannotReadOrMutateExactAttempt() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("stale-jdbc", 1, "winner-jdbc", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);
        CommandLease stale = lease("stale-jdbc", 2, "stale-jdbc", NOW.plusSeconds(60));
        PendingSecretLease staleLease = new PendingSecretLease(stale, batch.lease().coordinate(), stale.expectedRevision());
        assertThat(store.findExact(staleLease)).isEmpty();
        assertThatThrownBy(() -> store.markAbortRequired(staleLease))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.LEASE_FENCED);
    }

    @Test
    void explicitAbortMarksAndCompletesAnExactLease() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("abort-jdbc", 1, "abort-jdbc-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);
        store.markAbortRequired(batch.lease());
        store.markAbortRequired(batch.lease());

        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        store.completeAbort(candidate);
        store.completeAbort(candidate);
        assertThat(store.findExact(batch.lease())).isEmpty();
    }

    @Test
    void abortCandidateMustBeClaimedBeforeCompletionAndInvalidLimitIsIntegrity() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("candidate-jdbc", 1, "candidate-jdbc-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);
        assertThatThrownBy(() -> store.completeAbort(new SecretAbortCandidate(batch)))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.RECOVERY_STATE);
        assertThatThrownBy(() -> store.claimRecoveryDue(0))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    @Test
    void providerExpiryIsTheEffectiveRecoveryDeadline() {
        JdbcPendingSecretStore store = store();
        CommandLease lease = lease("provider-deadline-jdbc", 1, "provider-deadline-jdbc-token", NOW.plusSeconds(60));
        PendingSecretBatch batch = batchWithProviderExpiry(lease, NOW.plusSeconds(5));
        seed(batch);
        store.stage(batch);
        jdbc.update("UPDATE rg_api_connection_pending_secret_leases SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND"
                + " WHERE command_id=?", lease.commandId());
        SecretAbortCandidate candidate = store.claimRecoveryDue(1).getFirst();
        assertThat(candidate.batch()).isEqualTo(batch);
        store.completeAbort(candidate);
        assertThat(store.findExact(batch.lease())).isEmpty();
    }

    @Test
    void committedBindingRemainsVisibleOnlyAtItsExactCoordinate() {
        JdbcPendingSecretStore store = store();
        PendingSecretBatch batch = batch(lease("coordinate-jdbc", 1, "coordinate-jdbc-token", NOW.plusSeconds(60)), "token");
        seed(batch);
        store.stage(batch);
        transactions.executeWithoutResult(status -> store.commitBindings(batch,
                List.of(activation("token", "coordinate-jdbc-token", "active"))));

        assertThat(store.findActive(batch.lease().coordinate(), "token")).isPresent();
        assertThat(store.findActive(new ConnectionRevisionCoordinate(SCOPE, "other", 3), "token")).isEmpty();
        assertThat(store.findActive(new ConnectionRevisionCoordinate(SCOPE, "connection", 4), "token")).isEmpty();
    }

    @Test
    void standaloneConnectionTargetMustMatchChildCoordinate() {
        JdbcPendingSecretStore store = store();
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "resource", "idempotency-standalone-mismatch-jdbc");
        CommandLease lease = new CommandLease("standalone-mismatch-jdbc", 1, "standalone-mismatch-jdbc-token",
                key, "sha256:" + "f".repeat(64), NOW.plusSeconds(60), ExpectedRevision.match(2));
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 3, lease.commandId(), lease.attemptNo(), lease.attemptToken(), "token");
        PendingSecretBatch batch = new PendingSecretBatch(new PendingSecretLease(lease,
                new ConnectionRevisionCoordinate(SCOPE, "connection", 3), ExpectedRevision.match(2)),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", lease.attemptToken(), "standalone-opaque",
                                lease.leaseUntil(), context))));
        assertThatThrownBy(() -> store.stage(batch))
                .isInstanceOf(PendingSecretStoreException.class)
                .extracting("code").isEqualTo(PendingSecretStoreException.Code.INTEGRITY);
    }

    private JdbcPendingSecretStore store() { return storeOn(dataSource); }

    private JdbcPendingSecretStore storeOn(DataSource source) {
        return new JdbcPendingSecretStore(source, fixedClock());
    }

    private JdbcPendingSecretStore storeOn(DataSource source, java.util.function.Consumer<String> observer) {
        return new JdbcPendingSecretStore(source, fixedClock(), observer);
    }

    private DataSource newConnection() {
        return new DriverManagerDataSource(databaseUrl, "sa", "");
    }

    private TransactionTemplate transactions(DataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    private String concurrentCommit(DataSource source, JdbcPendingSecretStore store, PendingSecretBatch batch,
                                    CyclicBarrier start) {
        try {
            transactions(source).executeWithoutResult(status -> {
                await(start);
                store.commitBindings(batch, List.of(activation("token",
                        batch.lease().commandLease().attemptToken(),
                        batch.lease().commandLease().commandId() + "-active")));
            });
            return "COMMITTED";
        } catch (PendingSecretStoreException failure) {
            return failure.code().name();
        }
    }

    private SecretAbortCandidate concurrentRecovery(JdbcPendingSecretStore store, CyclicBarrier start) {
        await(start);
        return store.claimRecoveryDue(1).getFirst();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("latch timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    private Clock fixedClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private void seed(PendingSecretBatch batch) {
        CommandLease lease = batch.lease().commandLease();
        CommandKey key = lease.key();
        jdbc.update("INSERT INTO rg_authoring_command_journal (tenant_id, project_id, environment_id, actor_id, endpoint,"
                + " target_id, idempotency_key, command_id, request_fingerprint, status, attempt_no, attempt_token,"
                + " lease_until, expected_mode, expected_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PREPARING', ?, ?, ?, ?, ?)",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.commandId(), lease.requestFingerprint(), lease.attemptNo(), lease.attemptToken(),
                java.sql.Timestamp.from(lease.leaseUntil()), lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? (long) match.revision() : null);
        jdbc.update("INSERT INTO rg_authoring_command_attempts (tenant_id, project_id, environment_id, actor_id,"
                + " endpoint, target_id, idempotency_key, command_id, request_fingerprint, status, attempt_no,"
                + " attempt_token, lease_until, expected_mode, expected_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'PREPARING', ?, ?, ?, ?, ?)", SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), key.actorId(),
                key.endpoint().name(), key.targetId(), key.idempotencyKey(), lease.commandId(), lease.requestFingerprint(),
                lease.attemptNo(), lease.attemptToken(), java.sql.Timestamp.from(lease.leaseUntil()),
                lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? (long) match.revision() : null);
        jdbc.update("MERGE INTO rg_api_connection_identities (tenant_id, project_id, environment_id, connection_id) KEY (tenant_id, project_id, environment_id, connection_id) VALUES (?, ?, ?, ?)",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), batch.lease().coordinate().connectionId());
        jdbc.update("INSERT INTO rg_api_connection_revisions (tenant_id, project_id, environment_id, connection_id, revision, command_id,"
                + " state, attempt_no, attempt_token, display_name, secret_slot, view_json, metadata_fingerprint, base_url,"
                + " defaults_headers_json, timeout_ms, auth_kind, basic_username, api_key_header, strong_etag) VALUES (?, ?, ?, ?, ?, ?,"
                + " 'COMMITTED', ?, ?, 'fixture', 'token', '{}', ?, 'https://example.com', '{}', 1000, 'BEARER', NULL, NULL, '" + "\"etag-" + batch.lease().commandLease().commandId() + "\"' )",
                SCOPE.tenantId(), SCOPE.projectId(), SCOPE.environmentId(), batch.lease().coordinate().connectionId(),
                batch.lease().coordinate().revision(), lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                "sha256:" + "b".repeat(64));
    }

    private void insertAttemptAndPointJournal(CommandLease lease) {
        CommandKey key = lease.key();
        jdbc.update("INSERT INTO rg_authoring_command_attempts (tenant_id, project_id, environment_id, actor_id,"
                + " endpoint, target_id, idempotency_key, command_id, request_fingerprint, status, attempt_no,"
                + " attempt_token, lease_until, expected_mode, expected_revision) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?,"
                + " 'PREPARING', ?, ?, ?, ?, ?)", key.scope().tenantId(), key.scope().projectId(),
                key.scope().environmentId(), key.actorId(), key.endpoint().name(), key.targetId(),
                key.idempotencyKey(), lease.commandId(), lease.requestFingerprint(), lease.attemptNo(),
                lease.attemptToken(), java.sql.Timestamp.from(lease.leaseUntil()),
                lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? (long) match.revision() : null);
        jdbc.update("UPDATE rg_authoring_command_journal SET request_fingerprint=?, status='PREPARING',"
                + " attempt_no=?, attempt_token=?, lease_until=?, expected_mode=?, expected_revision=?,"
                + " receipt_schema=NULL, receipt_json=NULL, receipt_fingerprint=NULL, receipt_etag=NULL,"
                + " failure_code=NULL WHERE command_id=?", lease.requestFingerprint(), lease.attemptNo(),
                lease.attemptToken(), java.sql.Timestamp.from(lease.leaseUntil()),
                lease.expectedRevision() instanceof ExpectedRevision.Create ? "CREATE" : "MATCH",
                lease.expectedRevision() instanceof ExpectedRevision.Match match ? (long) match.revision() : null,
                lease.commandId());
    }

    private void insertCommittedConnectionRevision(CommandLease lease,
                                                    ConnectionRevisionCoordinate coordinate) {
        jdbc.update("INSERT INTO rg_api_connection_revisions (tenant_id, project_id, environment_id, connection_id,"
                + " revision, command_id, state, attempt_no, attempt_token, display_name, secret_slot, view_json,"
                + " metadata_fingerprint, base_url, defaults_headers_json, timeout_ms, auth_kind, basic_username,"
                + " api_key_header, strong_etag) VALUES (?, ?, ?, ?, ?, ?, 'COMMITTED', ?, ?, 'fixture', 'token', '{}',"
                + " ?, 'https://example.com', '{}', 1000, 'BEARER', NULL, NULL, ?)",
                coordinate.scope().tenantId(), coordinate.scope().projectId(), coordinate.scope().environmentId(),
                coordinate.connectionId(), coordinate.revision(), lease.commandId(), lease.attemptNo(),
                lease.attemptToken(), "sha256:" + "c".repeat(64), "\"etag-" + lease.commandId() + "\"");
    }

    private void migrate(String path) { new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource); }

    private PendingSecretBatch batch(CommandLease lease, String... slots) {
        return batchAtRevision(lease, 3, slots);
    }

    private PendingSecretBatch batchAtRevision(CommandLease lease, long revision, String... slots) {
        List<PendingSecretOperation> operations = java.util.Arrays.stream(slots).map(slot ->
                (PendingSecretOperation) new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE,
                        new PreparedExternalSecret("provider:one", lease.attemptToken(), "opaque-locator-" + slot,
                                lease.leaseUntil(), new SecretOperationContext(SCOPE, "actor", "connection-save",
                                lease.key().targetId(), revision,
                                lease.commandId(), lease.attemptNo(), lease.attemptToken(), slot)))).toList();
        String connectionId = lease.key().endpoint() == AuthoringEndpoint.API_RESOURCE_SAVE ? "connection" : lease.key().targetId();
        return new PendingSecretBatch(new PendingSecretLease(lease,
                new ConnectionRevisionCoordinate(SCOPE, connectionId, revision), lease.expectedRevision()), operations);
    }

    private PendingSecretBatch batchWithLocator(CommandLease lease, String slot, String locator) {
        PendingSecretBatch original = batch(lease, slot);
        PreparedExternalSecret prepared = ((PendingSecretOperation.Prepared) original.operation(slot)).prepared();
        PreparedExternalSecret drifted = new PreparedExternalSecret(prepared.providerId(), prepared.leaseId(), locator,
                prepared.leaseUntil(), prepared.context());
        return new PendingSecretBatch(original.lease(),
                List.of(new PendingSecretOperation.Prepared(slot, SecretSourceMode.VALUE, drifted)));
    }

    private PendingSecretBatch batchWithProviderExpiry(CommandLease lease, Instant providerExpiry) {
        PendingSecretBatch original = batch(lease, "token");
        PreparedExternalSecret prepared = ((PendingSecretOperation.Prepared) original.operation("token")).prepared();
        PreparedExternalSecret shortened = new PreparedExternalSecret(prepared.providerId(), prepared.leaseId(),
                prepared.opaqueLocator(), providerExpiry, prepared.context());
        return new PendingSecretBatch(original.lease(),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE, shortened)));
    }

    private PendingSecretBatch nestedBatch(CommandLease outer, String connectionId, long revision,
                                           ExpectedRevision childExpected) {
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "resource-save-child",
                connectionId, revision, outer.commandId(), outer.attemptNo(), outer.attemptToken(), "token");
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:one", outer.attemptToken(),
                "opaque-locator-token", outer.leaseUntil(), context);
        return new PendingSecretBatch(new PendingSecretLease(outer,
                new ConnectionRevisionCoordinate(SCOPE, connectionId, revision), childExpected),
                List.of(new PendingSecretOperation.Prepared("token", SecretSourceMode.VALUE, prepared)));
    }

    private CommandLease lease(String id, int attempt, String token, Instant until) {
        return leaseAtRevision(id, attempt, token, until, 3);
    }

    private CommandLease leaseAtRevision(String id, int attempt, String token, Instant until, long revision) {
        CommandKey key = new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_CONNECTION_SAVE,
                "connection", "idempotency-" + id);
        ExpectedRevision expected = revision == 1 ? ExpectedRevision.create() : ExpectedRevision.match(revision - 1);
        return new CommandLease(id, attempt, token, key, "sha256:" + "a".repeat(64), until, expected);
    }

    private ActivatedSecretSlot activation(String slot, String leaseId, String locator) {
        return new ActivatedSecretSlot(slot, new com.leanowtech.bloge.gateway.visual.authoring.connection.secret.ActivatedExternalSecret(
                "provider:one", leaseId, locator));
    }
}
