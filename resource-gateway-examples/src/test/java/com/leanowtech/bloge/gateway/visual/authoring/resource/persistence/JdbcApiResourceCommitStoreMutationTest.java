package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceAuthoringException;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceCommand;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ApiResourceSpec;
import com.leanowtech.bloge.gateway.visual.authoring.resource.ExpectedRevision;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionCommand;
import com.leanowtech.bloge.gateway.visual.authoring.connection.ApiConnectionDecisions;
import com.leanowtech.bloge.gateway.visual.authoring.connection.PreparedSecretBinding;
import com.leanowtech.bloge.gateway.visual.authoring.connection.SecretReference;
import com.leanowtech.bloge.gateway.visual.authoring.connection.persistence.JdbcApiConnectionCommitStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.PreparedExternalSecret;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.SecretOperationContext;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.ConnectionRevisionCoordinate;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.JdbcPendingSecretStore;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretBatch;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretLease;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.PendingSecretOperation;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.SecretAbortCandidate;
import com.leanowtech.bloge.gateway.visual.authoring.connection.secret.persistence.SecretSourceMode;
import com.leanowtech.bloge.gateway.visual.model.SchemaEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JDBC mutation contract at the public commit-store seam.
 *
 * <p>Both migrations are installed for every test. V002 is part of this
 * store's contract because staged revisions and projections are keyed by
 * exact command provenance and may share a logical revision.</p>
 */
class JdbcApiResourceCommitStoreMutationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AuthoringScope SCOPE = new AuthoringScope("tenant", "project", "dev");
    private static final CommandKey KEY = key("one");
    private static final String FP1 = "sha256:" + "1".repeat(64);
    private static final String FP2 = "sha256:" + "2".repeat(64);
    private static final String FP3 = "sha256:" + "3".repeat(64);

    private JdbcTemplate jdbc;
    private String url;

    @BeforeEach
    void setUp() {
        url = "jdbc:h2:mem:mutation-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        DataSource dataSource = new DriverManagerDataSource(url, "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        applyMigration("db/postgresql/V20260830_001__api_resource_authoring.sql", dataSource);
        applyMigration("db/postgresql/V20260830_002__api_resource_concurrent_staging.sql", dataSource);
        applyMigration("db/postgresql/V20260830_003__api_connection_secret_staging.sql", dataSource);
        applyMigration("db/postgresql/V20260830_004__connection_metadata_authority.sql", dataSource);
        applyMigration("db/postgresql/V20260830_005__pending_secret_store_protocol.sql", dataSource);
        applyMigration("db/postgresql/V20260830_006__pending_secret_store_hardening.sql", dataSource);
        applyMigration("db/postgresql/V20260831_007__pending_secret_store_protocol_closure.sql", dataSource);
        applyMigration("db/postgresql/V20260831_008__pending_secret_store_child_cas_closure.sql", dataSource);
        applyMigration("db/postgresql/V20260831_009__authoring_command_attempt_authority.sql", dataSource);
        applyMigration("db/postgresql/V20260831_010__attempt_provenance_closure.sql", dataSource);
        applyMigration("db/postgresql/V20260831_011__api_resource_connection_snapshot.sql", dataSource);
        jdbc.update("INSERT INTO rg_api_connection_identities"
                + " (tenant_id, project_id, environment_id, connection_id) VALUES ('tenant', 'project', 'dev', 'connection')");
        jdbc.update("INSERT INTO rg_api_connection_identities"
                + " (tenant_id, project_id, environment_id, connection_id) VALUES ('tenant', 'project', 'dev', 'other-connection')");
    }

    private static void applyMigration(String path, DataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource(path)).execute(dataSource);
    }

    @Test
    void stageIsInvisibleThenCommitPublishesDurableHeadAndRevision() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));

        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        assertThat(store.findRevision(SCOPE, "profile", 1)).isEmpty();
        CommandReceipt receipt = receipt(staged);

        assertThat(store.commit(lease, receipt)).isEqualTo(receipt);
        assertThat(store.findHead(SCOPE, "profile")).isPresent();
        assertThat(store.findRevision(SCOPE, "profile", 1))
                .contains(store.findHead(SCOPE, "profile").orElseThrow());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'", Integer.class)).isZero();
    }

    @Test
    void oldStrongEtagResolvesAfterHeadAdvancesButStagedTagDoesNot() {
        JdbcApiResourceCommitStore store = store();
        CommandLease create = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource stagedCreate = store.stage(create, "connection", command("one"));

        assertThat(store.findRevisionByStrongEtag(SCOPE, "profile", stagedCreate.strongEtag())).isEmpty();
        store.commit(create, receipt(stagedCreate));
        StoredApiResource first = store.findRevision(SCOPE, "profile", 1).orElseThrow();
        CommandLease update = acquire(store, key("two"), ExpectedRevision.match(1), FP2);
        StagedApiResource stagedUpdate = store.stage(update, "connection", command("two"));
        store.commit(update, receipt(stagedUpdate));

        assertThat(store.findRevisionByStrongEtag(SCOPE, "profile", first.receipt().strongEtag()))
                .contains(first);
        assertThat(store.findRevisionByStrongEtag(SCOPE, "profile", stagedUpdate.strongEtag()))
                .contains(store.findRevision(SCOPE, "profile", 2).orElseThrow());
        assertThat(store.findRevisionByStrongEtag(SCOPE, "other", first.receipt().strongEtag())).isEmpty();
    }

    @Test
    void stageIsIdempotentAndProjectionSetTamperingFailsClosed() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource first = store.stage(lease, "connection", command("one"));

        assertThat(store.stage(lease, "connection", command("one")).strongEtag())
                .isEqualTo(first.strongEtag());
        jdbc.update("UPDATE rg_api_resource_projection_revisions SET set_fingerprint=?", FP2);

        assertThatThrownBy(() -> store.stage(lease, "connection", command("one")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void stagedSpecAndRelationshipTamperingFailsClosed() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        store.stage(lease, "connection", command("one"));
        jdbc.update("UPDATE rg_api_resource_revisions SET connection_id=?", "other-connection");

        assertThatThrownBy(() -> store.stage(lease, "connection", command("one")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void connectionSnapshotTamperingFailsClosed() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        jdbc.update("UPDATE rg_api_resource_revisions SET connection_metadata_fingerprint=?", FP2);

        assertThatThrownBy(() -> store.commit(lease, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
    }

    @Test
    void commitRejectsTamperedSpecFingerprintAndKeepsHeadUnchanged() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_fingerprint=?", FP2);

        assertThatThrownBy(() -> store.commit(lease, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_resource_revisions", String.class)).isEqualTo("STAGED");
    }

    @Test
    void commitRejectsTamperedSpecBodyAndKeepsHeadUnchanged() throws Exception {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        ApiResourceSpec original = staged.resource();
        ApiResourceSpec tampered = new ApiResourceSpec(original.schemaVersion(), original.resourceId(), original.revision(),
                original.fingerprint(), "tampered", original.description(), original.connectionId(), original.operation(),
                original.contract(), original.response(), original.effect(), original.examples(), original.status());
        jdbc.update("UPDATE rg_api_resource_revisions SET spec_json=?", JSON.writeValueAsString(tampered));

        assertThatThrownBy(() -> store.commit(lease, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
    }

    @Test
    void commitRejectsTamperedConnectionAndKeepsHeadUnchanged() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        jdbc.update("UPDATE rg_api_resource_revisions SET connection_id=?", "other-connection");

        assertThatThrownBy(() -> store.commit(lease, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_resource_revisions", String.class))
                .isEqualTo("STAGED");
    }

    @Test
    void commitRejectsTamperedProjectionBodyAndKeepsHeadUnchanged() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        jdbc.update("UPDATE rg_api_resource_projection_revisions SET descriptor_json=?", "{\"tampered\":true}");

        assertThatThrownBy(() -> store.commit(lease, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
    }

    @Test
    void compilerFailureIsTypedAndLeavesNoStage() {
        JdbcApiResourceCommitStore store = store((scope, resource) -> {
            throw new IllegalStateException("invalid projection");
        });
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);

        assertThatThrownBy(() -> store.stage(lease, "connection", command("one")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.PROJECTION_INVALID);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions", Integer.class)).isZero();
    }

    @Test
    void authoringValidationRemainsDistinctFromCas() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);

        assertThatThrownBy(() -> store.stage(lease, "connection", command("")))
                .isInstanceOf(ApiResourceAuthoringException.class)
                .extracting("code").isEqualTo(ApiResourceAuthoringException.Code.VALIDATION);
    }

    @Test
    void receiptMismatchIsRejectedBeforeCommit() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        JsonNode body = JSON.createObjectNode().put("result", "profile");

        assertThatThrownBy(() -> store.commit(lease, new CommandReceipt(
                "test.receipt.v1", body, AuthoringFingerprints.of(body), "\"wrong-etag\"")))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.RECEIPT_INVALID);
        assertThat(store.findHead(SCOPE, "profile")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT state FROM rg_api_resource_revisions", String.class))
                .isEqualTo("STAGED");
        assertThat(staged.strongEtag()).isNotEqualTo("\"wrong-etag\"");
    }

    @Test
    void failedAttemptCleansStageAndCannotBeCommittedOrReplayed() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        store.stage(lease, "connection", command("one"));

        store.fail(lease, CommandFailureCode.INTERNAL);

        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal", String.class))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions", Integer.class)).isZero();
        assertThatThrownBy(() -> store.fail(lease, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
        assertThat(store.claim(KEY, FP2, ExpectedRevision.create())).isInstanceOf(ClaimResult.Conflict.class);
    }

    @Test
    void matchingCommittedFailIsIntegrityError() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(lease, "connection", command("one"));
        store.commit(lease, receipt(staged));

        assertThatThrownBy(() -> store.fail(lease, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
    }

    @Test
    void failRefusesToDeleteResourceStageUntilPendingSecretCompensationCompletes() {
        Clock clock = Clock.systemUTC();
        JdbcApiResourceCommitStore resource = store();
        JdbcApiConnectionCommitStore connection = new JdbcApiConnectionCommitStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                new ApiConnectionDecisions(), clock);
        JdbcPendingSecretStore pending = new JdbcPendingSecretStore(jdbc.getDataSource(), clock);
        CommandLease lease = acquire(resource, KEY, ExpectedRevision.create(), FP1);
        resource.stage(lease, "connection", command("one"));
        connection.stage(lease, "connection", ExpectedRevision.create(), secretCommand(),
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://team/token")));
        PendingSecretBatch batch = pendingBatch(lease);
        pending.stage(batch);

        assertThatThrownBy(() -> resource.fail(lease, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("PREPARING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE command_id=?",
                Integer.class, lease.commandId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases"
                        + " WHERE command_id=?", Integer.class, lease.commandId())).isEqualTo(1);

        pending.markAbortRequired(batch.lease());
        pending.completeAbort(pending.claimRecoveryDue(1).getFirst());
        resource.fail(lease, CommandFailureCode.INTERNAL);

        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE command_id=?",
                Integer.class, lease.commandId())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions WHERE command_id=?",
                Integer.class, lease.commandId())).isZero();
    }

    @Test
    void failedCleanupRequiresTheCompleteImmutableAttemptAuthority() {
        JdbcApiResourceCommitStore store = store();
        CommandLease lease = acquire(store, KEY, ExpectedRevision.create(), FP1);
        store.stage(lease, "connection", command("one"));

        jdbc.update("UPDATE rg_authoring_command_attempts SET status='FAILED'"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                lease.commandId(), lease.attemptNo(), lease.attemptToken());
        jdbc.update("UPDATE rg_authoring_command_journal SET status='FAILED', failure_code='INTERNAL'"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                lease.commandId(), lease.attemptNo(), lease.attemptToken());

        CommandLease alteredLease = new CommandLease(lease.commandId(), lease.attemptNo(), lease.attemptToken(),
                lease.key(), lease.requestFingerprint(), lease.leaseUntil().plusSeconds(1),
                lease.expectedRevision());
        assertThatThrownBy(() -> store.fail(alteredLease, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_FENCED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE command_id=?",
                Integer.class, lease.commandId())).isOne();

        store.fail(lease, CommandFailureCode.INTERNAL);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_revisions WHERE command_id=?",
                Integer.class, lease.commandId())).isZero();
    }

    @Test
    void resourceFailAndPendingRecoveryUseOneCrossStoreJournalFirstOrder() throws Exception {
        JdbcApiResourceCommitStore resource = store();
        JdbcApiConnectionCommitStore connection = new JdbcApiConnectionCommitStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                new ApiConnectionDecisions(), Clock.systemUTC());
        JdbcPendingSecretStore pending = new JdbcPendingSecretStore(jdbc.getDataSource(), Clock.systemUTC());
        CommandLease lease = acquire(resource, KEY, ExpectedRevision.create(), FP1);
        resource.stage(lease, "connection", command("one"));
        connection.stage(lease, "connection", ExpectedRevision.create(), secretCommand(),
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://team/token")));
        PendingSecretBatch batch = pendingBatch(lease);
        pending.stage(batch);
        pending.markAbortRequired(batch.lease());

        DataSource recoveryDataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcPendingSecretStore recovery = new JdbcPendingSecretStore(recoveryDataSource, Clock.systemUTC());
        CountDownLatch resourceJournalLocked = new CountDownLatch(1);
        CountDownLatch pendingStarted = new CountDownLatch(1);
        JdbcApiResourceCommitStore contendingResource = new JdbcApiResourceCommitStore(
                new JdbcTemplate(recoveryDataSource),
                new TransactionTemplate(new DataSourceTransactionManager(recoveryDataSource)), JSON,
                Duration.ofSeconds(1), new ApiResourceDecisions(), JdbcApiResourceCommitStoreMutationTest::compile,
                ignored -> {
                    resourceJournalLocked.countDown();
                    try {
                        if (!pendingStarted.await(10, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("pending worker did not start");
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("pending worker interrupted", ex);
                    }
                });
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApiResourceCommitStoreException.Code> failed = executor.submit(() -> {
                try {
                    contendingResource.fail(lease, CommandFailureCode.INTERNAL);
                    return null;
                } catch (ApiResourceCommitStoreException ex) {
                    return ex.code();
                }
            });
            assertThat(resourceJournalLocked.await(10, TimeUnit.SECONDS)).isTrue();
            Future<SecretAbortCandidate> recovered = executor.submit(() -> {
                pendingStarted.countDown();
                return recovery.claimRecoveryDue(1).getFirst();
            });
            assertThat(failed.get(10, TimeUnit.SECONDS))
                    .isEqualTo(ApiResourceCommitStoreException.Code.INTEGRITY);
            SecretAbortCandidate candidate = recovered.get(10, TimeUnit.SECONDS);
            recovery.completeAbort(candidate);
        } finally {
            executor.shutdownNow();
        }

        // Recovery owns the pending attempt's terminal transition.  The
        // resource stage remains until the resource store performs its exact
        // FAILED cleanup below.
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("FAILED");
        resource.fail(lease, CommandFailureCode.INTERNAL);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal WHERE command_id=?",
                String.class, lease.commandId())).isEqualTo("FAILED");
    }

    @Test
    void committedCommandReplaysReceiptAfterStoreReopen() {
        JdbcApiResourceCommitStore first = store();
        CommandLease lease = acquire(first, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = first.stage(lease, "connection", command("one"));
        CommandReceipt receipt = first.commit(lease, receipt(staged));
        JdbcApiResourceCommitStore reopened = store();

        ClaimResult.Replay replay = (ClaimResult.Replay) reopened.claim(KEY, FP1, ExpectedRevision.create());
        assertThat(replay.receipt()).isEqualTo(receipt);
    }

    @Test
    void staleFailIsNoOpButMatchingExpiredFailIsTyped() {
        JdbcApiResourceCommitStore store = store();
        CommandLease old = acquire(store, KEY, ExpectedRevision.create(), FP1);
        store.stage(old, "connection", command("one"));
        expire(old);
        CommandLease current = acquire(store, KEY, ExpectedRevision.create(), FP1);

        store.fail(old, CommandFailureCode.INTERNAL);
        assertThat(jdbc.queryForObject("SELECT status FROM rg_authoring_command_journal", String.class))
                .isEqualTo("PREPARING");
        expire(current);
        assertThatThrownBy(() -> store.fail(current, CommandFailureCode.INTERNAL))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_EXPIRED);
    }

    @Test
    void expiredLeaseCannotCommitAndTakeoverGetsFreshAttempt() {
        JdbcApiResourceCommitStore store = store();
        CommandLease old = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource staged = store.stage(old, "connection", command("one"));
        expire(old);

        assertThatThrownBy(() -> store.commit(old, receipt(staged)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.LEASE_EXPIRED);
        ClaimResult.Acquired takeover = (ClaimResult.Acquired) store.claim(
                KEY, FP1, ExpectedRevision.create());
        assertThat(takeover.lease().attemptNo()).isEqualTo(2);
        assertThat(takeover.lease().attemptToken()).isNotEqualTo(old.attemptToken());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'", Integer.class)).isZero();
    }

    @Test
    void updatePublishesHistoryAndRestartReadsIt() {
        JdbcApiResourceCommitStore store = store();
        CommandLease create = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource first = store.stage(create, "connection", command("one"));
        store.commit(create, receipt(first));
        CommandLease update = acquire(store, key("two"), ExpectedRevision.match(1), FP2);
        StagedApiResource second = store.stage(update, "connection", command("two"));
        store.commit(update, receipt(second));

        assertThat(store.findHead(SCOPE, "profile").orElseThrow().resource().revision()).isEqualTo(2);
        assertThat(store.findRevision(SCOPE, "profile", 1).orElseThrow().resource().revision()).isEqualTo(1);
        JdbcApiResourceCommitStore restarted = store();
        assertThat(restarted.findRevision(SCOPE, "profile", 1)).isPresent();
        assertThat(restarted.findRevision(SCOPE, "profile", 2)).isPresent();
    }

    @Test
    void staleUpdateFailsCasAndLeavesItsStageForRetry() {
        JdbcApiResourceCommitStore store = store();
        CommandLease create = acquire(store, KEY, ExpectedRevision.create(), FP1);
        StagedApiResource first = store.stage(create, "connection", command("one"));
        store.commit(create, receipt(first));
        CommandLease update = acquire(store, key("two"), ExpectedRevision.match(1), FP2);
        StagedApiResource updateStage = store.stage(update, "connection", command("two"));
        CommandLease other = acquire(store, key("three"), ExpectedRevision.match(1), FP3);
        StagedApiResource otherStage = store.stage(other, "connection", command("three"));
        store.commit(other, receipt(otherStage));

        assertThatThrownBy(() -> store.commit(update, receipt(updateStage)))
                .isInstanceOf(ApiResourceCommitStoreException.class)
                .extracting("code").isEqualTo(ApiResourceCommitStoreException.Code.CAS_MISMATCH);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void takeoverDeletesAProviderlessSupersededNestedConnectionStage() {
        JdbcApiResourceCommitStore resource = store();
        JdbcApiConnectionCommitStore connection = new JdbcApiConnectionCommitStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                new ApiConnectionDecisions(), Clock.systemUTC());
        CommandLease old = acquire(resource, KEY, ExpectedRevision.create(), FP1);
        resource.stage(old, "connection", command("one"));
        connection.stage(old, "connection", ExpectedRevision.create(), connectionCommand());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=? AND state='STAGED'", Integer.class,
                old.commandId(), old.attemptNo(), old.attemptToken())).isOne();

        expire(old);
        ClaimResult.Acquired replacement = (ClaimResult.Acquired) resource.claim(KEY, FP1, ExpectedRevision.create());

        assertThat(replacement.resumed()).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", Integer.class,
                old.commandId(), old.attemptNo(), old.attemptToken())).isZero();
    }

    @Test
    void takeoverRetainsNestedConnectionStageUntilPendingCompensation() {
        JdbcApiResourceCommitStore resource = store();
        JdbcApiConnectionCommitStore connection = new JdbcApiConnectionCommitStore(
                jdbc, new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                new ApiConnectionDecisions(), Clock.systemUTC());
        JdbcPendingSecretStore pending = new JdbcPendingSecretStore(jdbc.getDataSource(), Clock.systemUTC());
        CommandLease old = acquire(resource, KEY, ExpectedRevision.create(), FP1);
        resource.stage(old, "connection", command("one"));
        connection.stage(old, "connection", ExpectedRevision.create(), secretCommand(),
                new PreparedSecretBinding("token", new SecretReference(SCOPE, "vault://team/token")));
        PendingSecretBatch batch = pendingBatch(old);
        pending.stage(batch);
        pending.markAbortRequired(batch.lease());
        expire(old);

        resource.claim(KEY, FP1, ExpectedRevision.create());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", Integer.class,
                old.commandId(), old.attemptNo(), old.attemptToken())).isOne();
        pending.completeAbort(pending.claimRecoveryDue(1).getFirst());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_revisions"
                + " WHERE command_id=? AND attempt_no=? AND attempt_token=?", Integer.class,
                old.commandId(), old.attemptNo(), old.attemptToken())).isZero();
    }

    @Test
    void twoIndependentConnectionsHaveOneCommitWinnerAndRollbackLoser() throws Exception {
        DataSource leftDataSource = new DriverManagerDataSource(url, "sa", "");
        DataSource rightDataSource = new DriverManagerDataSource(url, "sa", "");
        JdbcApiResourceCommitStore left = store(new JdbcTemplate(leftDataSource), leftDataSource);
        JdbcApiResourceCommitStore right = store(new JdbcTemplate(rightDataSource), rightDataSource);
        CommandLease leftLease = acquire(left, key("left"), ExpectedRevision.create(), FP1);
        CommandLease rightLease = acquire(right, key("right"), ExpectedRevision.create(), FP2);
        StagedApiResource leftStage = left.stage(leftLease, "connection", command("one"));
        StagedApiResource rightStage = right.stage(rightLease, "connection", command("two"));
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> leftCommit = executor.submit(() ->
                    commitAtBarrier(left, leftLease, leftStage, barrier));
            Future<Boolean> rightCommit = executor.submit(() ->
                    commitAtBarrier(right, rightLease, rightStage, barrier));
            assertThat((leftCommit.get() ? 1 : 0) + (rightCommit.get() ? 1 : 0)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_resource_heads", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='COMMITTED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM rg_api_resource_revisions WHERE state='STAGED'", Integer.class)).isEqualTo(1);
    }

    private static boolean commitAtBarrier(JdbcApiResourceCommitStore store, CommandLease lease,
                                            StagedApiResource staged, CyclicBarrier barrier) throws Exception {
        barrier.await();
        try {
            store.commit(lease, receipt(staged));
            return true;
        } catch (ApiResourceCommitStoreException ex) {
            assertThat(ex.code()).as(ex.getMessage()).isEqualTo(ApiResourceCommitStoreException.Code.CAS_MISMATCH);
            return false;
        }
    }

    private void expire(CommandLease lease) {
        jdbc.update("UPDATE rg_authoring_command_journal SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1' SECOND WHERE command_id=?",
                lease.commandId());
    }

    private JdbcApiResourceCommitStore store() {
        return store(jdbc, jdbc.getDataSource());
    }

    private JdbcApiResourceCommitStore store(ApiResourceProjectionCompiler compiler) {
        return new JdbcApiResourceCommitStore(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())), JSON,
                Duration.ofSeconds(1), new ApiResourceDecisions(), compiler);
    }

    private JdbcApiResourceCommitStore store(JdbcTemplate template, DataSource dataSource) {
        return new JdbcApiResourceCommitStore(template,
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)), JSON,
                Duration.ofSeconds(1), new ApiResourceDecisions(),
                JdbcApiResourceCommitStoreMutationTest::compile);
    }

    private static CommandLease acquire(JdbcApiResourceCommitStore store, CommandKey key,
                                        ExpectedRevision expected, String fingerprint) {
        return ((ClaimResult.Acquired) store.claim(key, fingerprint, expected)).lease();
    }

    private static CommandKey key(String idempotencyKey) {
        return new CommandKey(SCOPE, "actor", AuthoringEndpoint.API_RESOURCE_SAVE, "profile", idempotencyKey);
    }

    private static CommandReceipt receipt(StagedApiResource staged) {
        JsonNode body = JSON.createObjectNode().put("result", staged.resource().resourceId());
        return new CommandReceipt("test.receipt.v1", body, AuthoringFingerprints.of(body), staged.strongEtag());
    }

    private static ReadyApiResourceProjections compile(AuthoringScope scope, ApiResourceSpec resource) {
        return new ReadyApiResourceProjections(document(ProjectionDocument.Kind.DESCRIPTOR, resource),
                document(ProjectionDocument.Kind.DESIGN_CONTRACT, resource),
                document(ProjectionDocument.Kind.OPERATOR, resource),
                new ApiResourceConnectionSnapshot(resource.connectionId(), 1,
                        "sha256:" + "c".repeat(64)));
    }

    private static ProjectionDocument document(ProjectionDocument.Kind kind, ApiResourceSpec resource) {
        JsonNode body = JSON.createObjectNode().put("kind", kind.name());
        return new ProjectionDocument(kind, resource.ref(), body, AuthoringFingerprints.of(body),
                ProjectionDocument.State.READY);
    }

    private static ApiResourceCommand command(String name) {
        Map<String, Object> schema = SchemaEnvelope
                .object(Map.of("id", Map.of("type", "string")), List.of("id")).schema();
        SchemaEnvelope envelope = new SchemaEnvelope(SchemaEnvelope.JSON_SCHEMA, "2020-12", schema);
        JsonNode value = JSON.createObjectNode().put("id", "one");
        return new ApiResourceCommand(name, null,
                new ApiResourceCommand.Operation("GET", "/profile", List.of()),
                new ApiResourceCommand.Contract(envelope, envelope),
                new ApiResourceCommand.Response(new ApiResourceCommand.HttpStatus(List.of(200)), null),
                ApiResourceCommand.Effect.READ_ONLY,
                List.of(new ApiResourceCommand.Example("one", value, value)));
    }

    private static ApiConnectionCommand connectionCommand() {
        return new ApiConnectionCommand("Connection", "https://customer.example.com",
                ApiConnectionCommand.Auth.none(), new ApiConnectionCommand.Defaults(5_000, Map.of()));
    }

    private static ApiConnectionCommand secretCommand() {
        return new ApiConnectionCommand("Connection", "https://customer.example.com",
                ApiConnectionCommand.Auth.bearer(ApiConnectionCommand.SecretWrite.value("one-time-secret")),
                new ApiConnectionCommand.Defaults(5_000, Map.of()));
    }

    private static PendingSecretBatch pendingBatch(CommandLease lease) {
        ConnectionRevisionCoordinate coordinate = new ConnectionRevisionCoordinate(SCOPE, "connection", 1);
        PendingSecretLease pendingLease = new PendingSecretLease(lease, coordinate, ExpectedRevision.create());
        SecretOperationContext context = new SecretOperationContext(SCOPE, "actor", "connection-save",
                "connection", 1, lease.commandId(), lease.attemptNo(), lease.attemptToken(), "token");
        PreparedExternalSecret prepared = new PreparedExternalSecret("provider:test", "provider-lease",
                "opaque-locator", lease.leaseUntil(), context);
        return new PendingSecretBatch(pendingLease, List.of(new PendingSecretOperation.Prepared("token",
                SecretSourceMode.VALUE, prepared)));
    }
}
