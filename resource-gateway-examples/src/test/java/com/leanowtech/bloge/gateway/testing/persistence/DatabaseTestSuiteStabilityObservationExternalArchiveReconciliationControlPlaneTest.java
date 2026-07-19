package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryRequest;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlaneTest {
    private static final String AUTHORITY = "archive-a";
    private static final String FAILURE_DOMAIN = "region-a";
    private static final String TRUST_DOMAIN = "archive.example";
    private static final String ARCHIVE_SET = "archive-set-a";

    private ObjectMapper objectMapper;
    private TestRuntimeDatabase database;
    private Instant snapshotAt;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        database = new TestRuntimeDatabase(new TestRuntimeDatabase.Settings(
                "jdbc:h2:mem:external-inventory-control-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1",
                "sa", "", 6));
        snapshotAt = Instant.now().minusSeconds(5).truncatedTo(ChronoUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void stagesTwoPagesAndCompletesThePinnedRootAcrossReplicas() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = items(3);
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items);
        var firstReplica = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);

        var first = firstReplica.stageNextPage(AUTHORITY);

        assertThat(first.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.STAGED);
        assertThat(first.pageSequence()).isZero();
        assertThat(first.pageItemCount()).isEqualTo(2);
        assertThat(first.accumulatedObjectCount()).isEqualTo(2);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                """, Integer.class)).isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_items
                """, Integer.class)).isEqualTo(2);
        assertThat(database.jdbc().queryForObject("""
                SELECT accumulated_root
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, first.cycleId())).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                        objectMapper, items.subList(0, 2)));
        assertThat(database.jdbc().queryForMap("""
                SELECT trust_domain, archive_set_id, failure_domain
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, first.cycleId()))
                .containsEntry("TRUST_DOMAIN", TRUST_DOMAIN)
                .containsEntry("ARCHIVE_SET_ID", ARCHIVE_SET)
                .containsEntry("FAILURE_DOMAIN", FAILURE_DOMAIN);
        assertThat(database.jdbc().queryForObject("""
                SELECT lease_owner
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEmpty();

        var secondReplica = controlPlane("replica-b", Duration.ofSeconds(30), 2, authority);
        var terminal = secondReplica.stageNextPage(AUTHORITY);

        assertThat(terminal.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.COMPLETED);
        assertThat(terminal.cycleId()).isEqualTo(first.cycleId());
        assertThat(terminal.pageSequence()).isEqualTo(1);
        assertThat(terminal.pageItemCount()).isEqualTo(1);
        assertThat(terminal.accumulatedObjectCount()).isEqualTo(3);
        assertThat(database.jdbc().queryForObject("""
                SELECT cycle_status
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, first.cycleId())).isEqualTo("COMPLETED");
        assertThat(database.jdbc().queryForObject("""
                SELECT accumulated_root
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, first.cycleId())).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                        objectMapper, items));
        assertThat(database.jdbc().queryForObject("""
                SELECT last_completed_cycle_id
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEqualTo(first.cycleId());
        assertThat(authority.remoteCalls()).isEqualTo(2);
    }

    @Test
    void operationalSnapshotTracksInitialActiveAndCompletedCycleWithoutIdentities() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(2));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 1, authority);

        var initial = controlPlane.operationalSnapshot(AUTHORITY);
        assertThat(initial.activeCycle()).isFalse();
        assertThat(initial.completedCycle()).isFalse();
        assertThat(initial.lastCompletedAt()).isNull();

        controlPlane.stageNextPage(AUTHORITY);
        var active = controlPlane.operationalSnapshot(AUTHORITY);
        assertThat(active.activeCycle()).isTrue();
        assertThat(active.completedCycle()).isFalse();
        assertThat(active.nextPageSequence()).isEqualTo(1);
        assertThat(active.accumulatedObjectCount()).isEqualTo(1);
        assertThat(active.activeCycleStartedAt()).isNotNull();
        assertThat(active.activeCycleUpdatedAt()).isNotNull();

        controlPlane.stageNextPage(AUTHORITY);
        var completed = controlPlane.operationalSnapshot(AUTHORITY);
        assertThat(completed.activeCycle()).isFalse();
        assertThat(completed.completedCycle()).isTrue();
        assertThat(completed.nextPageSequence()).isZero();
        assertThat(completed.lastCompletedAt()).isNotNull();
        assertThat(completed.getClass().getRecordComponents())
                .extracting(component -> component.getName().toLowerCase())
                .noneMatch(name -> name.contains("id") || name.contains("token")
                        || name.contains("owner"));
    }

    @Test
    void structurallyValidAuthorityTamperingFailsClosedBeforeRemoteIo() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(1));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 1, authority);
        controlPlane.operationalSnapshot(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_authorities
                SET revision = revision + 1
                WHERE authority_id = ?
                """, AUTHORITY);

        assertThatThrownBy(() -> controlPlane.operationalSnapshot(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("authority record fingerprint");
        assertThat(authority.remoteCalls()).isZero();
    }

    @Test
    void structurallyValidCycleTamperingFailsClosedBeforeRemoteIo() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(2));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 1, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_cycles
                SET updated_at = DATEADD('MILLISECOND', 1, updated_at)
                WHERE cycle_id = ?
                """, first.cycleId());

        assertThatThrownBy(() -> controlPlane.operationalSnapshot(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle record fingerprint");
        assertThat(authority.remoteCalls()).isEqualTo(1);
    }

    @Test
    void startupEstablishesANonNullFingerprintBaselineForLegacyStateRows() {
        String cycleId = "00000000-0000-0000-0000-000000000001";
        database.jdbc().execute("""
                CREATE TABLE rg_test_suite_stability_observation_external_inventory_authorities (
                    authority_id VARCHAR(255) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    active_cycle_id VARCHAR(36) NOT NULL,
                    last_completed_cycle_id VARCHAR(36) NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        database.jdbc().execute("""
                CREATE TABLE rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id VARCHAR(36) PRIMARY KEY,
                    authority_id VARCHAR(255) NOT NULL,
                    cycle_status VARCHAR(32) NOT NULL,
                    trust_domain VARCHAR(255) NOT NULL,
                    archive_set_id VARCHAR(255) NOT NULL,
                    failure_domain VARCHAR(255) NOT NULL,
                    snapshot_id VARCHAR(255) NOT NULL,
                    snapshot_at TIMESTAMP WITH TIME ZONE,
                    snapshot_object_count BIGINT NOT NULL,
                    snapshot_root VARCHAR(71) NOT NULL,
                    next_after_object_id VARCHAR(255) NOT NULL,
                    next_page_sequence BIGINT NOT NULL,
                    accumulated_object_count BIGINT NOT NULL,
                    accumulated_root VARCHAR(71) NOT NULL,
                    last_object_id VARCHAR(255) NOT NULL,
                    revision BIGINT NOT NULL,
                    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    completed_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_authorities (
                    authority_id, lease_owner, lease_token, lease_epoch, lease_until, revision,
                    active_cycle_id, last_completed_cycle_id, last_success_at, updated_at
                ) VALUES (?, '', '', 0, CURRENT_TIMESTAMP, 0, ?, '', NULL, CURRENT_TIMESTAMP)
                """, AUTHORITY, cycleId);
        database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                    failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                    snapshot_root, next_after_object_id, next_page_sequence,
                    accumulated_object_count, accumulated_root, last_object_id, revision,
                    started_at, completed_at, updated_at
                ) VALUES (?, ?, 'ACTIVE', '', '', '', '', NULL, -1, '', '', 0, 0, ?, '', 0,
                          CURRENT_TIMESTAMP, NULL, CURRENT_TIMESTAMP)
                """, cycleId, AUTHORITY,
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT);

        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 1,
                new FixtureInventoryAuthority(items(1)));

        assertThat(controlPlane.operationalSnapshot(AUTHORITY).activeCycle()).isTrue();
        assertThat(database.jdbc().queryForObject("""
                SELECT record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).startsWith("sha256:");
        assertThat(database.jdbc().queryForObject("""
                SELECT record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, cycleId)).startsWith("sha256:");
        assertThatThrownBy(() -> database.jdbc().update("""
                INSERT INTO rg_test_suite_stability_observation_external_inventory_cycles (
                    cycle_id, authority_id, cycle_status, trust_domain, archive_set_id,
                    failure_domain, snapshot_id, snapshot_at, snapshot_object_count,
                    snapshot_root, next_after_object_id, next_page_sequence,
                    accumulated_object_count, accumulated_root, last_object_id, revision,
                    started_at, completed_at, updated_at, record_fingerprint
                ) VALUES ('00000000-0000-0000-0000-000000000002', ?, 'ACTIVE', '', '', '', '',
                          NULL, -1, '', '', 0, 0, ?, '', 0, CURRENT_TIMESTAMP, NULL,
                          CURRENT_TIMESTAMP, NULL)
                """, AUTHORITY,
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void liveLeaseReturnsBusyWithoutRemoteIoAndExpiredFenceIsTakenOver() throws Exception {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(1));
        authority.blockFirstCall();
        var firstReplica = controlPlane("replica-a", Duration.ofSeconds(1), 10, authority);
        var secondReplica = controlPlane("replica-b", Duration.ofSeconds(1), 10, authority);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var stale = pool.submit(() -> firstReplica.stageNextPage(AUTHORITY));
            assertThat(authority.awaitFirstCall()).isTrue();

            var busy = secondReplica.stageNextPage(AUTHORITY);
            assertThat(busy.status()).isEqualTo(
                    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                            .PageStatus.BUSY);
            assertThat(authority.remoteCalls()).isEqualTo(1);

            Thread.sleep(1_100);
            var takeover = secondReplica.stageNextPage(AUTHORITY);
            assertThat(takeover.status()).isEqualTo(
                    DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                            .PageStatus.COMPLETED);
            assertThat(authority.remoteCalls()).isEqualTo(2);

            authority.releaseFirstCall();
            assertThatThrownBy(stale::get)
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(
                            DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                                    .LeaseLostException.class);
            assertThat(database.jdbc().queryForObject("""
                    SELECT COUNT(*)
                    FROM rg_test_suite_stability_observation_external_inventory_pages
                    """, Integer.class)).isEqualTo(1);
            assertThat(database.jdbc().queryForObject("""
                    SELECT lease_owner
                    FROM rg_test_suite_stability_observation_external_inventory_authorities
                    WHERE authority_id = ?
                    """, String.class, AUTHORITY)).isEmpty();
        } finally {
            authority.releaseFirstCall();
            pool.shutdownNow();
        }
    }

    @Test
    void invalidPageReleasesOnlyTheLeaseAndPreservesTheExactCursor() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(3));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        authority.verification(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification.INVALID);

        assertThatThrownBy(() -> controlPlane.stageNextPage(AUTHORITY))
                .isInstanceOf(
                        TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                                .InventoryException.class)
                .extracting(failure -> ((TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                        .InventoryException) failure).reason())
                .isEqualTo(TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                        .InventoryException.Reason.INVALID_PAGE);
        assertThat(database.jdbc().queryForObject("""
                SELECT next_page_sequence
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, Long.class, first.cycleId())).isEqualTo(1L);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                """, Integer.class)).isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT lease_owner
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEmpty();

        authority.verification(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification.VERIFIED);
        assertThat(controlPlane.stageNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.COMPLETED);
    }

    @Test
    void snapshotExpiryClosesTheOldCycleBeforeANewPageZeroCycleStarts() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(3));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        authority.expireContinuations(true);

        var expired = controlPlane.stageNextPage(AUTHORITY);

        assertThat(expired.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.SNAPSHOT_EXPIRED);
        assertThat(expired.cycleId()).isEqualTo(first.cycleId());
        assertThat(database.jdbc().queryForObject("""
                SELECT cycle_status
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, first.cycleId())).isEqualTo("SNAPSHOT_EXPIRED");
        assertThat(database.jdbc().queryForObject("""
                SELECT active_cycle_id
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEmpty();

        authority.expireContinuations(false);
        var restarted = controlPlane.stageNextPage(AUTHORITY);
        assertThat(restarted.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.STAGED);
        assertThat(restarted.cycleId()).isNotEqualTo(first.cycleId());
        assertThat(authority.cursors()).extracting(
                TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor::pageSequence)
                .containsExactly(0L, 1L, 0L);
    }

    @Test
    void terminalRootMismatchRollsBackThePageItemsCursorAndCompletion() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(2));
        authority.wrongSignedRoot(true);
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 10, authority);

        assertThatThrownBy(() -> controlPlane.stageNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed count, root, and page sequence");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_items
                """, Integer.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT next_page_sequence
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                """, Long.class)).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT lease_owner
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEmpty();

        authority.wrongSignedRoot(false);
        assertThat(controlPlane.stageNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.COMPLETED);
    }

    @Test
    void terminalReplayDetectsTamperedStagedItemAndRollsBackOnlyTheNewPage() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = items(3);
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items);
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_items
                SET object_commitment = ?
                WHERE cycle_id = ? AND object_id = ?
                """, fingerprint('f'), first.cycleId(), items.getFirst().objectId());

        assertThatThrownBy(() -> controlPlane.stageNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staged item material");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                WHERE cycle_id = ?
                """, Integer.class, first.cycleId())).isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ?
                """, Integer.class, first.cycleId())).isEqualTo(2);
        assertThat(database.jdbc().queryForObject("""
                SELECT next_page_sequence
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, Long.class, first.cycleId())).isEqualTo(1L);

        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_items
                SET object_commitment = ?
                WHERE cycle_id = ? AND object_id = ?
                """, items.getFirst().objectCommitment(), first.cycleId(),
                items.getFirst().objectId());
        assertThat(controlPlane.stageNextPage(AUTHORITY).status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.COMPLETED);
    }

    @Test
    void committedPageSurvivesAnOuterCallerTransactionRollback() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(3));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        TransactionTemplate caller = new TransactionTemplate(database.transactionManager());

        var attempt = caller.execute(status -> {
            var staged = controlPlane.stageNextPage(AUTHORITY);
            status.setRollbackOnly();
            return staged;
        });

        assertThat(attempt).isNotNull();
        assertThat(attempt.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.STAGED);
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                """, Integer.class)).isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT next_page_sequence
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                """, Long.class)).isEqualTo(1L);
    }

    @Test
    void emptySignedSnapshotCompletesWithTheDomainSeparatedEmptyRoot() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(List.of());
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 10, authority);

        var attempt = controlPlane.stageNextPage(AUTHORITY);

        assertThat(attempt.status()).isEqualTo(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .PageStatus.COMPLETED);
        assertThat(attempt.pageItemCount()).isZero();
        assertThat(attempt.accumulatedObjectCount()).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT accumulated_root
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, String.class, attempt.cycleId())).isEqualTo(
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT);
    }

    @Test
    void corruptDurableCursorFailsBeforeLeaseOrRemoteIo() {
        List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items = items(3);
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items);
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        database.jdbc().update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_cycles
                SET next_after_object_id = ?
                WHERE cycle_id = ?
                """, items.getFirst().objectId(), first.cycleId());

        assertThatThrownBy(() -> controlPlane.stageNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cycle state is corrupt");
        assertThat(authority.remoteCalls()).isEqualTo(1);
        assertThat(database.jdbc().queryForObject("""
                SELECT lease_owner
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                """, String.class, AUTHORITY)).isEmpty();
    }

    @Test
    void terminalReplayRejectsAMissingHistoricalPageEnvelope() {
        FixtureInventoryAuthority authority = new FixtureInventoryAuthority(items(3));
        var controlPlane = controlPlane("replica-a", Duration.ofSeconds(30), 2, authority);
        var first = controlPlane.stageNextPage(AUTHORITY);
        database.jdbc().update("""
                DELETE FROM rg_test_suite_stability_observation_external_inventory_pages
                WHERE cycle_id = ? AND page_sequence = 0
                """, first.cycleId());

        assertThatThrownBy(() -> controlPlane.stageNextPage(AUTHORITY))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("signed count, root, and page sequence");
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_pages
                WHERE cycle_id = ?
                """, Integer.class, first.cycleId())).isZero();
        assertThat(database.jdbc().queryForObject("""
                SELECT COUNT(*)
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ?
                """, Integer.class, first.cycleId())).isEqualTo(2);
        assertThat(database.jdbc().queryForObject("""
                SELECT next_page_sequence
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                """, Long.class, first.cycleId())).isEqualTo(1L);
    }

    @Test
    void settingsAndPublicBoundaryRejectUnsafeShapeAndExposeNoDestructiveOperation() {
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .Settings("replica-a", Duration.ofMillis(1_500), 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole seconds");
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .Settings("replica-a", Duration.ofSeconds(1), 501))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 through 500");
        assertThatThrownBy(() -> new
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                .PageAttempt(
                        DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                                .PageStatus.STAGED,
                        AUTHORITY, "00000000-0000-0000-0000-000000000001", 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page attempt");
        assertThatThrownBy(() -> controlPlane(
                "replica-a", Duration.ofSeconds(30), 10,
                new FixtureInventoryAuthority(items(1))).stageNextPage("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not configured");

        assertThat(Arrays.stream(
                DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                        .class.getMethods())
                .map(method -> method.getName().toLowerCase())
                .noneMatch(name -> name.contains("delete") || name.contains("purge")
                        || name.contains("overwrite") || name.contains("shorten")
                        || name.contains("legalhold")))
                .isTrue();
    }

    private DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
            controlPlane(
            String ownerId,
            Duration leaseDuration,
            int maximumItems,
            FixtureInventoryAuthority authority) {
        var controlPlane =
                new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
                        database.jdbc(), database.transactionManager(), objectMapper, authority,
                        new DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane
                                .Settings(ownerId, leaseDuration, maximumItems));
        controlPlane.init();
        return controlPlane;
    }

    private List<TestSuiteStabilityObservationExternalArchiveInventoryItem> items(int count) {
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> item((char) ('1' + index)))
                .toList();
    }

    private TestSuiteStabilityObservationExternalArchiveInventoryItem item(char identity) {
        var material = new TestSuiteStabilityObservationExternalArchiveInventoryItem.Material(
                TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                "stability-observation-worm-" + String.valueOf(identity).repeat(64),
                fingerprint('a'),
                "stability-observation-retirement-" + String.valueOf(identity).repeat(64),
                fingerprint('b'),
                "stability-observation-archive-" + String.valueOf(identity).repeat(64),
                fingerprint('c'), fingerprint('d'), snapshotAt.plus(Duration.ofDays(365)),
                snapshotAt.minusSeconds(60));
        return new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                material.objectId(), material.objectCommitment(), material.retirementId(),
                material.retirementFingerprint(), material.segmentId(),
                material.segmentFingerprint(), material.retentionPolicyFingerprint(),
                material.retainUntil(), material.storedAt());
    }

    private static String fingerprint(char value) {
        return "sha256:" + String.valueOf(value).repeat(64);
    }

    private final class FixtureInventoryAuthority
            implements TestSuiteStabilityObservationExternalArchiveInventoryAuthority {
        private final List<TestSuiteStabilityObservationExternalArchiveInventoryItem> inventory;
        private final AtomicInteger calls = new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<Cursor> cursors =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile Verification verification = Verification.VERIFIED;
        private volatile boolean expireContinuations;
        private volatile boolean wrongSignedRoot;
        private volatile boolean blockFirst;
        private volatile CountDownLatch firstEntered = new CountDownLatch(0);
        private volatile CountDownLatch releaseFirst = new CountDownLatch(0);

        private FixtureInventoryAuthority(
                List<TestSuiteStabilityObservationExternalArchiveInventoryItem> inventory) {
            this.inventory = List.copyOf(inventory);
        }

        @Override
        public List<String> inventoryAuthorities() {
            return List.of(AUTHORITY);
        }

        @Override
        public TestSuiteStabilityObservationExternalArchiveInventoryPage inventoryPage(
                String authorityId,
                Cursor cursor,
                int maximumItems) {
            int call = calls.incrementAndGet();
            cursors.add(cursor);
            if (blockFirst && call == 1) {
                firstEntered.countDown();
                try {
                    if (!releaseFirst.await(10, TimeUnit.SECONDS)) {
                        throw new InventoryException(InventoryException.Reason.UNAVAILABLE);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new InventoryException(InventoryException.Reason.UNAVAILABLE);
                }
            }
            if (expireContinuations && cursor.pageSequence() > 0) {
                throw new InventoryException(InventoryException.Reason.SNAPSHOT_EXPIRED);
            }
            String root = wrongSignedRoot
                    ? fingerprint('f')
                    : TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.root(
                    objectMapper, inventory);
            String snapshotId =
                    TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.snapshotId(
                            objectMapper, TRUST_DOMAIN, ARCHIVE_SET, AUTHORITY, FAILURE_DOMAIN,
                            snapshotAt, inventory.size(), root);
            if (cursor.pageSequence() > 0 && !cursor.snapshotId().equals(snapshotId)) {
                throw new InventoryException(InventoryException.Reason.SNAPSHOT_EXPIRED);
            }
            int from = cursor.pageSequence() == 0
                    ? 0 : indexAfter(cursor.afterObjectId());
            int through = Math.min(inventory.size(), from + maximumItems);
            List<TestSuiteStabilityObservationExternalArchiveInventoryItem> pageItems =
                    inventory.subList(from, through);
            boolean complete = through == inventory.size();
            String next = complete ? "" : pageItems.getLast().objectId();
            Instant requestedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            byte[] challengeBytes = new byte[32];
            Arrays.fill(challengeBytes, (byte) call);
            var request = TestSuiteStabilityObservationExternalArchiveInventoryRequest.create(
                    objectMapper, TRUST_DOMAIN, ARCHIVE_SET, authorityId, cursor.snapshotId(),
                    cursor.afterObjectId(), cursor.pageSequence(), maximumItems,
                    Base64.getUrlEncoder().withoutPadding().encodeToString(challengeBytes),
                    requestedAt, requestedAt.plusSeconds(30));
            Instant issuedAt = requestedAt;
            Instant expiresAt = requestedAt.plusSeconds(20);
            var material = new TestSuiteStabilityObservationExternalArchiveInventoryPage.Material(
                    TestSuiteStabilityObservationExternalArchiveInventoryPage.SCHEMA_VERSION,
                    request.requestFingerprint(), authorityId, FAILURE_DOMAIN, "inventory-key-a",
                    snapshotId, snapshotAt, inventory.size(), root, pageItems, next, complete,
                    issuedAt, expiresAt, "Ed25519");
            return new TestSuiteStabilityObservationExternalArchiveInventoryPage(
                    material.schemaVersion(), ProtocolFingerprint.of(objectMapper, material),
                    request, material.authorityId(), material.failureDomain(), material.keyId(),
                    material.snapshotId(), material.snapshotAt(), material.snapshotObjectCount(),
                    material.snapshotRoot(), material.items(), material.nextAfterObjectId(),
                    material.complete(), material.issuedAt(), material.expiresAt(),
                    material.algorithm(), Base64.getEncoder().encodeToString(new byte[64]));
        }

        @Override
        public Verification verifyInventoryPage(
                TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
            if (verification == Verification.VERIFIED
                    && !page.fingerprintVerified(objectMapper)) {
                return Verification.INVALID;
            }
            return verification;
        }

        private int indexAfter(String objectId) {
            for (int index = 0; index < inventory.size(); index++) {
                if (inventory.get(index).objectId().equals(objectId)) {
                    return index + 1;
                }
            }
            throw new InventoryException(InventoryException.Reason.INVALID_PAGE);
        }

        private void verification(Verification value) {
            verification = value;
        }

        private void expireContinuations(boolean value) {
            expireContinuations = value;
        }

        private void wrongSignedRoot(boolean value) {
            wrongSignedRoot = value;
        }

        private void blockFirstCall() {
            blockFirst = true;
            firstEntered = new CountDownLatch(1);
            releaseFirst = new CountDownLatch(1);
        }

        private boolean awaitFirstCall() throws InterruptedException {
            return firstEntered.await(5, TimeUnit.SECONDS);
        }

        private void releaseFirstCall() {
            releaseFirst.countDown();
        }

        private int remoteCalls() {
            return calls.get();
        }

        private List<Cursor> cursors() {
            return List.copyOf(cursors);
        }
    }
}
