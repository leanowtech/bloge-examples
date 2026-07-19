package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryItem;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryPage;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityObservationExternalArchiveInventoryRequest;
import com.leanowtech.bloge.gateway.testing.evidence.TestSuiteStabilityObservationExternalArchiveInventoryIntegrity;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Durable page-staging control plane for signed external observation-archive inventories.
 *
 * <p>One short database-clock lease fences one authority page. Remote HTTPS I/O happens outside a
 * database transaction while the lease is live. The returned page is verified again, then its page
 * envelope, normalized items, accumulated ordered root, exact continuation cursor, and lease
 * release are committed in one local transaction. A crash before commit leaves the previous cursor;
 * a crash after commit exposes the successor cursor to another replica.</p>
 *
 * <p>Authority and cycle rows carry versioned whole-record fingerprints. Every lock read verifies
 * the fingerprint before state can drive remote I/O or readiness, and every mutation uses the
 * previous fingerprint plus revision as its compare-and-set fence. Startup performs an explicit
 * one-time trust migration for legacy test/staging rows that predate these columns.</p>
 *
 * <p>The control plane has no external delete, purge, overwrite, legal-hold, or retention mutation
 * authority. Staged values contain signed payload-free inventory metadata only. Classification,
 * finding workflow, and remediation are deliberately separate later phases.</p>
 */
public final class
        DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane {
    private static final Duration MINIMUM_LEASE = Duration.ofSeconds(1);
    private static final Duration MAXIMUM_LEASE = Duration.ofHours(1);
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern SNAPSHOT_ID = Pattern.compile(
            "stability-observation-external-inventory-[a-f0-9]{64}");
    private static final Pattern OBJECT_ID =
            Pattern.compile("stability-observation-worm-[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority;
    private final TransactionTemplate transactions;
    private final Settings settings;

    /**
     * Creates a control plane using a transaction manager derived from the JDBC datasource.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param authority read-only signed inventory authority
     * @param settings replica-owned lease and page settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
            Settings settings) {
        this(jdbc, localTransactionManager(jdbc), objectMapper, authority, settings);
    }

    /**
     * Creates a control plane over the same datasource as stability observation retirement state.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param transactionManager transaction manager bound to the JDBC datasource
     * @param objectMapper canonical protocol mapper
     * @param authority read-only signed inventory authority
     * @param settings replica-owned lease and page settings
     */
    public DatabaseTestSuiteStabilityObservationExternalArchiveReconciliationControlPlane(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority authority,
            Settings settings) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.authority = Objects.requireNonNull(authority, "authority");
        this.settings = Objects.requireNonNull(settings, "settings");
        transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** Creates authority leases, cycles, page envelopes, and normalized item staging tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_inventory_authorities (
                    authority_id VARCHAR(255) PRIMARY KEY,
                    lease_owner VARCHAR(255) NOT NULL,
                    lease_token VARCHAR(255) NOT NULL,
                    lease_epoch BIGINT NOT NULL,
                    lease_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    revision BIGINT NOT NULL,
                    active_cycle_id VARCHAR(36) NOT NULL,
                    last_completed_cycle_id VARCHAR(36) NOT NULL,
                    last_success_at TIMESTAMP WITH TIME ZONE,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_inventory_cycles (
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
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_inventory_cycle_authority
                ON rg_test_suite_stability_observation_external_inventory_cycles (
                    authority_id, cycle_status, started_at, cycle_id
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_inventory_pages (
                    cycle_id VARCHAR(36) NOT NULL,
                    page_sequence BIGINT NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    trust_domain VARCHAR(255) NOT NULL,
                    archive_set_id VARCHAR(255) NOT NULL,
                    failure_domain VARCHAR(255) NOT NULL,
                    request_fingerprint VARCHAR(71) NOT NULL,
                    page_fingerprint VARCHAR(71) NOT NULL,
                    snapshot_id VARCHAR(255) NOT NULL,
                    snapshot_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    snapshot_object_count BIGINT NOT NULL,
                    snapshot_root VARCHAR(71) NOT NULL,
                    after_object_id VARCHAR(255) NOT NULL,
                    next_after_object_id VARCHAR(255) NOT NULL,
                    item_count INTEGER NOT NULL,
                    complete BOOLEAN NOT NULL,
                    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    page_json CLOB NOT NULL,
                    PRIMARY KEY (cycle_id, page_sequence),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_inventory_request
                        UNIQUE (request_fingerprint),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_inventory_page
                        UNIQUE (page_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS
                    rg_test_suite_stability_observation_external_inventory_items (
                    cycle_id VARCHAR(36) NOT NULL,
                    object_id VARCHAR(255) NOT NULL,
                    page_sequence BIGINT NOT NULL,
                    item_fingerprint VARCHAR(71) NOT NULL,
                    object_commitment VARCHAR(71) NOT NULL,
                    retirement_id VARCHAR(255) NOT NULL,
                    retirement_fingerprint VARCHAR(71) NOT NULL,
                    segment_id VARCHAR(255) NOT NULL,
                    segment_fingerprint VARCHAR(71) NOT NULL,
                    retention_policy_fingerprint VARCHAR(71) NOT NULL,
                    retain_until TIMESTAMP WITH TIME ZONE NOT NULL,
                    stored_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    committed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    PRIMARY KEY (cycle_id, object_id),
                    CONSTRAINT uq_rg_test_suite_stability_observation_external_inventory_item
                        UNIQUE (cycle_id, item_fingerprint)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS
                    idx_rg_test_suite_stability_observation_external_inventory_item_page
                ON rg_test_suite_stability_observation_external_inventory_items (
                    cycle_id, page_sequence, object_id
                )
                """);
        migrateStateFingerprints();
    }

    /**
     * Claims, reads, verifies, and atomically stages one authority page.
     *
     * <p>A busy result performs no remote call. Snapshot expiry explicitly terminates the old cycle
     * and clears its cursor; an unavailable or invalid page releases only the lease and preserves the
     * exact active cycle for a later retry.</p>
     *
     * @param authorityId exact configured inventory authority
     * @return payload-free page-stage result
     * @throws TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
     *         when the remote page is unavailable or invalid
     * @throws LeaseLostException when this replica loses its database fence before commit
     */
    public PageAttempt stageNextPage(String authorityId) {
        String exactAuthority = configuredAuthority(authorityId);
        Optional<PageLease> acquired = acquireLease(exactAuthority);
        if (acquired.isEmpty()) {
            return PageAttempt.busy(exactAuthority);
        }
        PageLease lease = acquired.orElseThrow();
        try {
            TestSuiteStabilityObservationExternalArchiveInventoryPage page =
                    authority.inventoryPage(exactAuthority, lease.cursor(),
                            settings.maximumItems());
            requireVerified(page);
            return commitPage(lease, page);
        } catch (TestSuiteStabilityObservationExternalArchiveInventoryAuthority.InventoryException
                failure) {
            if (failure.reason()
                    == TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .InventoryException.Reason.SNAPSHOT_EXPIRED) {
                return expireCycle(lease);
            }
            releaseAfterFailure(lease, failure);
            throw failure;
        } catch (RuntimeException failure) {
            releaseAfterFailure(lease, failure);
            throw failure;
        }
    }

    /**
     * Returns the verified low-cardinality progress needed to sequence downstream comparison.
     *
     * <p>The snapshot deliberately omits cycle, snapshot, object, lease-owner, and lease-token
     * identities. Callers use it only to decide whether an active inventory cycle must advance,
     * whether the first cycle must start, or whether a completed cycle may flow downstream.</p>
     *
     * @param authorityId exact configured inventory authority
     * @return database-clock authority and active-cycle progress
     */
    public OperationalSnapshot operationalSnapshot(String authorityId) {
        String exactAuthority = configuredAuthority(authorityId);
        OperationalSnapshot result = transactions.execute(status -> {
            initializeAuthority(exactAuthority);
            StoredAuthority state = lockAuthority(exactAuthority);
            Instant now = databaseNow();
            if (state.activeCycleId().isEmpty()) {
                return new OperationalSnapshot(now, state.leaseLiveAt(now), false,
                        state.lastSuccessAt() != null, 0, 0, null, null,
                        state.lastSuccessAt());
            }
            StoredCycle cycle = lockCycle(state.activeCycleId());
            cycle.requireActiveFor(exactAuthority);
            return new OperationalSnapshot(now, state.leaseLiveAt(now), true,
                    state.lastSuccessAt() != null, cycle.nextPageSequence(),
                    cycle.accumulatedObjectCount(), cycle.startedAt(), cycle.updatedAt(),
                    state.lastSuccessAt());
        });
        if (result == null) {
            throw new IllegalStateException(
                    "External inventory operational snapshot returned no result");
        }
        return result;
    }

    private Optional<PageLease> acquireLease(String authorityId) {
        Optional<PageLease> result = transactions.execute(status -> {
            initializeAuthority(authorityId);
            StoredAuthority state = lockAuthority(authorityId);
            Instant now = databaseNow();
            if (state.leaseLiveAt(now)) {
                return Optional.empty();
            }
            String cycleId = state.activeCycleId();
            StoredCycle cycle;
            if (cycleId.isEmpty()) {
                cycle = insertCycle(authorityId, now);
                cycleId = cycle.cycleId();
            } else {
                cycle = lockCycle(cycleId);
                cycle.requireActiveFor(authorityId);
            }
            long successorEpoch = increment(state.leaseEpoch(), "inventory lease epoch");
            long successorRevision = increment(state.revision(), "inventory authority revision");
            String token = UUID.randomUUID().toString();
            Instant leaseUntil = now.plus(settings.leaseDuration());
            StoredAuthority successor = fingerprinted(new StoredAuthority(
                    state.authorityId(), settings.ownerId(), token, successorEpoch, leaseUntil,
                    successorRevision, cycleId, state.lastCompletedCycleId(),
                    state.lastSuccessAt(), now, ""));
            updateAuthority(state, successor);
            return Optional.of(new PageLease(authorityId, cycleId, settings.ownerId(), token,
                    successorEpoch, successorRevision, leaseUntil, cycle.cursor()));
        });
        if (result == null) {
            throw new IllegalStateException("External inventory lease transaction returned no result");
        }
        return result;
    }

    private PageAttempt commitPage(
            PageLease lease,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        PageAttempt result = transactions.execute(status -> {
            StoredAuthority state = lockAuthority(lease.authorityId());
            Instant now = databaseNow();
            requireLease(state, lease, now);
            StoredCycle cycle = lockCycle(lease.cycleId());
            cycle.requireActiveFor(lease.authorityId());
            requirePageMatches(cycle, lease, page, now);

            long accumulatedCount;
            try {
                accumulatedCount = Math.addExact(
                        cycle.accumulatedObjectCount(), page.items().size());
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        "External inventory accumulated object count overflow", overflow);
            }
            if (accumulatedCount > page.snapshotObjectCount()
                    || (!page.complete()
                    && accumulatedCount >= page.snapshotObjectCount())) {
                throw new IllegalStateException(
                        "External inventory page contradicts its snapshot object count");
            }
            String accumulatedRoot = cycle.accumulatedRoot();
            for (TestSuiteStabilityObservationExternalArchiveInventoryItem item : page.items()) {
                accumulatedRoot =
                        TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.append(
                                objectMapper, accumulatedRoot, item);
            }
            long successorPageSequence = increment(
                    page.request().pageSequence(), "inventory page sequence");

            insertPage(lease.cycleId(), page, now);
            insertItems(lease.cycleId(), page, now);
            if (page.complete()) {
                StagedReplay replay = replayStagedCycle(lease.cycleId());
                PageSpan pageSpan = stagedPageSpan(lease.cycleId());
                if (accumulatedCount != page.snapshotObjectCount()
                        || !accumulatedRoot.equals(page.snapshotRoot())
                        || replay.objectCount() != accumulatedCount
                        || !replay.root().equals(accumulatedRoot)
                        || !replay.lastObjectId().equals(page.items().isEmpty()
                        ? cycle.lastObjectId() : page.items().getLast().objectId())
                        || pageSpan.pageCount() != successorPageSequence
                        || pageSpan.minimumSequence() != 0
                        || pageSpan.maximumSequence() != page.request().pageSequence()) {
                    throw new IllegalStateException(
                            "External inventory terminal page does not reproduce staged signed "
                                    + "count, root, and page sequence");
                }
            }

            long cycleRevision = increment(cycle.revision(), "inventory cycle revision");
            long authorityRevision = increment(state.revision(), "inventory authority revision");
            String lastObjectId = page.items().isEmpty()
                    ? cycle.lastObjectId() : page.items().getLast().objectId();
            String statusValue = page.complete() ? "COMPLETED" : "ACTIVE";
            String nextAfterObjectId = page.complete() ? "" : page.nextAfterObjectId();
            StoredCycle cycleSuccessor = fingerprinted(new StoredCycle(
                    cycle.cycleId(), cycle.authorityId(), statusValue,
                    page.request().trustDomain(), page.request().archiveSetId(),
                    page.failureDomain(), page.snapshotId(), page.snapshotAt(),
                    page.snapshotObjectCount(), page.snapshotRoot(), nextAfterObjectId,
                    successorPageSequence, accumulatedCount, accumulatedRoot, lastObjectId,
                    cycleRevision, cycle.startedAt(), page.complete() ? now : null, now, ""));
            updateCycle(cycle, cycleSuccessor);
            StoredAuthority authoritySuccessor = fingerprinted(new StoredAuthority(
                    state.authorityId(), "", "", state.leaseEpoch(), now,
                    authorityRevision, page.complete() ? "" : lease.cycleId(),
                    page.complete() ? lease.cycleId() : state.lastCompletedCycleId(),
                    page.complete() ? now : state.lastSuccessAt(), now, ""));
            updateAuthority(state, authoritySuccessor);
            return new PageAttempt(
                    page.complete() ? PageStatus.COMPLETED : PageStatus.STAGED,
                    lease.authorityId(), lease.cycleId(), page.request().pageSequence(),
                    page.items().size(), accumulatedCount);
        });
        if (result == null) {
            throw new IllegalStateException("External inventory page transaction returned no result");
        }
        return result;
    }

    private PageAttempt expireCycle(PageLease lease) {
        PageAttempt result = transactions.execute(status -> {
            StoredAuthority state = lockAuthority(lease.authorityId());
            Instant now = databaseNow();
            requireLease(state, lease, now);
            StoredCycle cycle = lockCycle(lease.cycleId());
            cycle.requireActiveFor(lease.authorityId());
            long cycleRevision = increment(cycle.revision(), "inventory cycle revision");
            long authorityRevision = increment(state.revision(), "inventory authority revision");
            StoredCycle cycleSuccessor = fingerprinted(new StoredCycle(
                    cycle.cycleId(), cycle.authorityId(), "SNAPSHOT_EXPIRED",
                    cycle.trustDomain(), cycle.archiveSetId(), cycle.failureDomain(),
                    cycle.snapshotId(), cycle.snapshotAt(), cycle.snapshotObjectCount(),
                    cycle.snapshotRoot(), cycle.nextAfterObjectId(), cycle.nextPageSequence(),
                    cycle.accumulatedObjectCount(), cycle.accumulatedRoot(),
                    cycle.lastObjectId(), cycleRevision, cycle.startedAt(), now, now, ""));
            updateCycle(cycle, cycleSuccessor);
            StoredAuthority authoritySuccessor = fingerprinted(new StoredAuthority(
                    state.authorityId(), "", "", state.leaseEpoch(), now,
                    authorityRevision, "", state.lastCompletedCycleId(), state.lastSuccessAt(),
                    now, ""));
            updateAuthority(state, authoritySuccessor);
            return new PageAttempt(PageStatus.SNAPSHOT_EXPIRED, lease.authorityId(),
                    lease.cycleId(), cycle.nextPageSequence(), 0,
                    cycle.accumulatedObjectCount());
        });
        if (result == null) {
            throw new IllegalStateException(
                    "External inventory snapshot-expiry transaction returned no result");
        }
        return result;
    }

    private void releaseAfterFailure(PageLease lease, RuntimeException failure) {
        try {
            releaseLease(lease);
        } catch (RuntimeException releaseFailure) {
            failure.addSuppressed(releaseFailure);
        }
    }

    private void releaseLease(PageLease lease) {
        transactions.executeWithoutResult(status -> {
            StoredAuthority state = lockAuthority(lease.authorityId());
            if (!state.matches(lease)) {
                return;
            }
            Instant now = databaseNow();
            long successorRevision = increment(state.revision(), "inventory authority revision");
            StoredAuthority successor = fingerprinted(new StoredAuthority(
                    state.authorityId(), "", "", state.leaseEpoch(), now,
                    successorRevision, state.activeCycleId(), state.lastCompletedCycleId(),
                    state.lastSuccessAt(), now, ""));
            updateAuthority(state, successor);
        });
    }

    private void requireVerified(
            TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        if (page == null) {
            throw new TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .InventoryException(
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                            .InventoryException.Reason.INVALID_PAGE);
        }
        TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification verification =
                authority.verifyInventoryPage(page);
        if (verification == null
                || verification
                == TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                .INVALID) {
            throw new TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .InventoryException(
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                            .InventoryException.Reason.INVALID_PAGE);
        }
        if (verification
                == TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Verification
                .UNAVAILABLE) {
            throw new TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                    .InventoryException(
                    TestSuiteStabilityObservationExternalArchiveInventoryAuthority
                            .InventoryException.Reason.UNAVAILABLE);
        }
    }

    private void requirePageMatches(
            StoredCycle cycle,
            PageLease lease,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page,
            Instant databaseNow) {
        TestSuiteStabilityObservationExternalArchiveInventoryRequest request = page.request();
        if (!page.fingerprintVerified(objectMapper)
                || !lease.authorityId().equals(page.authorityId())
                || !lease.authorityId().equals(request.authorityId())
                || request.maximumItems() != settings.maximumItems()
                || request.pageSequence() != cycle.nextPageSequence()
                || !request.snapshotId().equals(cycle.snapshotId())
                || !request.afterObjectId().equals(cycle.nextAfterObjectId())
                || !databaseNow.isBefore(request.expiresAt())
                || !databaseNow.isBefore(page.expiresAt())) {
            throw new IllegalStateException(
                    "External inventory page does not match the durable cursor or admission time");
        }
        if (cycle.nextPageSequence() == 0) {
            if (!cycle.snapshotId().isEmpty() || cycle.snapshotAt() != null
                    || cycle.snapshotObjectCount() != -1 || !cycle.snapshotRoot().isEmpty()
                    || cycle.accumulatedObjectCount() != 0
                    || !cycle.accumulatedRoot().equals(
                    TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT)) {
                throw new IllegalStateException(
                        "External inventory initial cycle state is corrupt");
            }
        } else if (!page.snapshotId().equals(cycle.snapshotId())
                || !page.request().trustDomain().equals(cycle.trustDomain())
                || !page.request().archiveSetId().equals(cycle.archiveSetId())
                || !page.failureDomain().equals(cycle.failureDomain())
                || !page.snapshotAt().equals(cycle.snapshotAt())
                || page.snapshotObjectCount() != cycle.snapshotObjectCount()
                || !page.snapshotRoot().equals(cycle.snapshotRoot())) {
            throw new IllegalStateException(
                    "External inventory page changed the pinned snapshot");
        }
    }

    private void requireLease(StoredAuthority state, PageLease lease, Instant now) {
        if (!state.matches(lease) || !now.isBefore(state.leaseUntil())) {
            throw new LeaseLostException();
        }
    }

    private void insertPage(
            String cycleId,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page,
            Instant committedAt) {
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_inventory_pages (
                        cycle_id, page_sequence, authority_id, trust_domain,
                        archive_set_id, failure_domain, request_fingerprint,
                        page_fingerprint, snapshot_id, snapshot_at, snapshot_object_count,
                        snapshot_root, after_object_id, next_after_object_id, item_count,
                        complete, issued_at, expires_at, committed_at, page_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, cycleId, page.request().pageSequence(), page.authorityId(),
                    page.request().trustDomain(), page.request().archiveSetId(),
                    page.failureDomain(), page.request().requestFingerprint(),
                    page.pageFingerprint(), page.snapshotId(),
                    Timestamp.from(page.snapshotAt()), page.snapshotObjectCount(),
                    page.snapshotRoot(), page.request().afterObjectId(), page.nextAfterObjectId(),
                    page.items().size(), page.complete(), Timestamp.from(page.issuedAt()),
                    Timestamp.from(page.expiresAt()), Timestamp.from(committedAt), writePage(page));
            if (inserted != 1) {
                throw new IllegalStateException("External inventory page insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException(
                    "External inventory page identity was already staged", duplicate);
        }
    }

    private void insertItems(
            String cycleId,
            TestSuiteStabilityObservationExternalArchiveInventoryPage page,
            Instant committedAt) {
        for (TestSuiteStabilityObservationExternalArchiveInventoryItem item : page.items()) {
            try {
                int inserted = jdbc.update("""
                        INSERT INTO
                            rg_test_suite_stability_observation_external_inventory_items (
                            cycle_id, object_id, page_sequence, item_fingerprint,
                            object_commitment, retirement_id, retirement_fingerprint,
                            segment_id, segment_fingerprint, retention_policy_fingerprint,
                            retain_until, stored_at, committed_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, cycleId, item.objectId(), page.request().pageSequence(),
                        item.itemFingerprint(), item.objectCommitment(), item.retirementId(),
                        item.retirementFingerprint(), item.segmentId(), item.segmentFingerprint(),
                        item.retentionPolicyFingerprint(), Timestamp.from(item.retainUntil()),
                        Timestamp.from(item.storedAt()), Timestamp.from(committedAt));
                if (inserted != 1) {
                    throw new IllegalStateException(
                            "External inventory item insert was incomplete");
                }
            } catch (DuplicateKeyException duplicate) {
                throw new IllegalStateException(
                        "External inventory item identity was already staged", duplicate);
            }
        }
    }

    private StagedReplay replayStagedCycle(String cycleId) {
        long[] count = {0};
        String[] root = {
                TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT};
        String[] previousObjectId = {""};
        jdbc.query("""
                SELECT object_id, item_fingerprint, object_commitment,
                       retirement_id, retirement_fingerprint, segment_id,
                       segment_fingerprint, retention_policy_fingerprint,
                       retain_until, stored_at
                FROM rg_test_suite_stability_observation_external_inventory_items
                WHERE cycle_id = ?
                ORDER BY object_id
                """, (RowCallbackHandler) result -> {
            TestSuiteStabilityObservationExternalArchiveInventoryItem item =
                    new TestSuiteStabilityObservationExternalArchiveInventoryItem(
                            TestSuiteStabilityObservationExternalArchiveInventoryItem.SCHEMA_VERSION,
                            result.getString("item_fingerprint"), result.getString("object_id"),
                            result.getString("object_commitment"),
                            result.getString("retirement_id"),
                            result.getString("retirement_fingerprint"),
                            result.getString("segment_id"),
                            result.getString("segment_fingerprint"),
                            result.getString("retention_policy_fingerprint"),
                            result.getTimestamp("retain_until").toInstant(),
                            result.getTimestamp("stored_at").toInstant());
            if (!item.fingerprintVerified(objectMapper)
                    || previousObjectId[0].compareTo(item.objectId()) >= 0) {
                throw new IllegalStateException(
                        "External inventory staged item material or order is corrupt");
            }
            count[0] = increment(count[0], "inventory staged object count");
            root[0] = TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.append(
                    objectMapper, root[0], item);
            previousObjectId[0] = item.objectId();
        }, cycleId);
        return new StagedReplay(count[0], root[0], previousObjectId[0]);
    }

    private PageSpan stagedPageSpan(String cycleId) {
        List<PageSpan> rows = jdbc.query("""
                SELECT COUNT(*) AS page_count, MIN(page_sequence) AS minimum_sequence,
                       MAX(page_sequence) AS maximum_sequence
                FROM rg_test_suite_stability_observation_external_inventory_pages
                WHERE cycle_id = ?
                """, (result, row) -> new PageSpan(
                result.getLong("page_count"), result.getLong("minimum_sequence"),
                result.getLong("maximum_sequence")), cycleId);
        if (rows.size() != 1 || rows.getFirst().pageCount() < 1) {
            throw new IllegalStateException("External inventory staged page sequence is missing");
        }
        return rows.getFirst();
    }

    /**
     * Establishes a fingerprint trust baseline for rows written before record fingerprints existed.
     *
     * <p>This migration is intentionally local to the test/staging-only reconciliation surface. It
     * trusts each legacy row exactly once, writes the derived fingerprint under its revision fence,
     * then makes both columns non-null. Rows that already carry a fingerprint must verify and are
     * never silently re-baselined.</p>
     */
    private void migrateStateFingerprints() {
        jdbc.execute("""
                ALTER TABLE
                    rg_test_suite_stability_observation_external_inventory_authorities
                ADD COLUMN IF NOT EXISTS record_fingerprint VARCHAR(71)
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_observation_external_inventory_cycles
                ADD COLUMN IF NOT EXISTS record_fingerprint VARCHAR(71)
                """);
        transactions.executeWithoutResult(status -> {
            List<StoredAuthority> authorities = jdbc.query("""
                    SELECT authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                           revision, active_cycle_id, last_completed_cycle_id,
                           last_success_at, updated_at, record_fingerprint
                    FROM rg_test_suite_stability_observation_external_inventory_authorities
                    ORDER BY authority_id
                    FOR UPDATE
                    """, this::unverifiedAuthority);
            for (StoredAuthority authorityState : authorities) {
                if (normalized(authorityState.recordFingerprint()).isEmpty()) {
                    StoredAuthority migrated = fingerprinted(authorityState);
                    int updated = jdbc.update("""
                            UPDATE
                                rg_test_suite_stability_observation_external_inventory_authorities
                            SET record_fingerprint = ?
                            WHERE authority_id = ? AND revision = ?
                              AND (record_fingerprint IS NULL OR record_fingerprint = '')
                            """, migrated.recordFingerprint(), migrated.authorityId(),
                            migrated.revision());
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "External inventory authority fingerprint migration lost its fence");
                    }
                } else {
                    verify(authorityState);
                }
            }
            List<StoredCycle> cycles = jdbc.query("""
                    SELECT cycle_id, authority_id, cycle_status, trust_domain,
                           archive_set_id, failure_domain, snapshot_id, snapshot_at,
                           snapshot_object_count, snapshot_root, next_after_object_id,
                           next_page_sequence, accumulated_object_count, accumulated_root,
                           last_object_id, revision, started_at, completed_at, updated_at,
                           record_fingerprint
                    FROM rg_test_suite_stability_observation_external_inventory_cycles
                    ORDER BY cycle_id
                    FOR UPDATE
                    """, this::unverifiedCycle);
            for (StoredCycle cycle : cycles) {
                if (normalized(cycle.recordFingerprint()).isEmpty()) {
                    StoredCycle migrated = fingerprinted(cycle);
                    int updated = jdbc.update("""
                            UPDATE rg_test_suite_stability_observation_external_inventory_cycles
                            SET record_fingerprint = ?
                            WHERE cycle_id = ? AND revision = ?
                              AND (record_fingerprint IS NULL OR record_fingerprint = '')
                            """, migrated.recordFingerprint(), migrated.cycleId(),
                            migrated.revision());
                    if (updated != 1) {
                        throw new IllegalStateException(
                                "External inventory cycle fingerprint migration lost its fence");
                    }
                } else {
                    verify(cycle);
                }
            }
        });
        jdbc.execute("""
                ALTER TABLE
                    rg_test_suite_stability_observation_external_inventory_authorities
                ALTER COLUMN record_fingerprint SET NOT NULL
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_observation_external_inventory_cycles
                ALTER COLUMN record_fingerprint SET NOT NULL
                """);
    }

    private void updateAuthority(StoredAuthority expected, StoredAuthority successor) {
        verify(expected);
        verify(successor);
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_authorities
                SET lease_owner = ?, lease_token = ?, lease_epoch = ?, lease_until = ?,
                    revision = ?, active_cycle_id = ?, last_completed_cycle_id = ?,
                    last_success_at = ?, updated_at = ?, record_fingerprint = ?
                WHERE authority_id = ? AND revision = ? AND record_fingerprint = ?
                """, successor.updateArguments(expected));
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private void updateCycle(StoredCycle expected, StoredCycle successor) {
        verify(expected);
        verify(successor);
        int updated = jdbc.update("""
                UPDATE rg_test_suite_stability_observation_external_inventory_cycles
                SET cycle_status = ?, trust_domain = ?, archive_set_id = ?,
                    failure_domain = ?, snapshot_id = ?, snapshot_at = ?,
                    snapshot_object_count = ?, snapshot_root = ?,
                    next_after_object_id = ?, next_page_sequence = ?,
                    accumulated_object_count = ?, accumulated_root = ?, last_object_id = ?,
                    revision = ?, started_at = ?, completed_at = ?, updated_at = ?,
                    record_fingerprint = ?
                WHERE cycle_id = ? AND revision = ? AND record_fingerprint = ?
                """, successor.updateArguments(expected));
        if (updated != 1) {
            throw new LeaseLostException();
        }
    }

    private StoredAuthority fingerprinted(StoredAuthority state) {
        state.validate();
        return state.withFingerprint(authorityFingerprint(state));
    }

    private StoredCycle fingerprinted(StoredCycle cycle) {
        cycle.validate();
        return cycle.withFingerprint(cycleFingerprint(cycle));
    }

    private void verify(StoredAuthority state) {
        state.validate();
        if (!FINGERPRINT.matcher(normalized(state.recordFingerprint())).matches()
                || !state.recordFingerprint().equals(authorityFingerprint(state))) {
            throw new IllegalStateException(
                    "External inventory authority record fingerprint is corrupt");
        }
    }

    private void verify(StoredCycle cycle) {
        cycle.validate();
        if (!FINGERPRINT.matcher(normalized(cycle.recordFingerprint())).matches()
                || !cycle.recordFingerprint().equals(cycleFingerprint(cycle))) {
            throw new IllegalStateException(
                    "External inventory cycle record fingerprint is corrupt");
        }
    }

    private String authorityFingerprint(StoredAuthority state) {
        return ExternalArchiveInventoryStateIntegrity.authorityFingerprint(
                objectMapper, state.authorityId(), state.leaseOwner(), state.leaseToken(),
                state.leaseEpoch(), state.leaseUntil(), state.revision(), state.activeCycleId(),
                state.lastCompletedCycleId(), state.lastSuccessAt(), state.updatedAt());
    }

    private String cycleFingerprint(StoredCycle cycle) {
        return ExternalArchiveInventoryStateIntegrity.cycleFingerprint(
                objectMapper, cycle.cycleId(), cycle.authorityId(), cycle.status(),
                cycle.trustDomain(), cycle.archiveSetId(), cycle.failureDomain(),
                cycle.snapshotId(), cycle.snapshotAt(), cycle.snapshotObjectCount(),
                cycle.snapshotRoot(), cycle.nextAfterObjectId(), cycle.nextPageSequence(),
                cycle.accumulatedObjectCount(), cycle.accumulatedRoot(), cycle.lastObjectId(),
                cycle.revision(), cycle.startedAt(), cycle.completedAt(), cycle.updatedAt());
    }

    private void initializeAuthority(String authorityId) {
        Instant now = databaseNow();
        StoredAuthority initial = fingerprinted(new StoredAuthority(
                authorityId, "", "", 0, now, 0, "", "", null, now, ""));
        try {
            jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_inventory_authorities (
                        authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                        revision, active_cycle_id, last_completed_cycle_id,
                        last_success_at, updated_at, record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
        } catch (DuplicateKeyException ignored) {
            // Another replica already initialized the authority row.
        }
    }

    private StoredCycle insertCycle(String authorityId, Instant now) {
        String cycleId = UUID.randomUUID().toString();
        StoredCycle initial = fingerprinted(new StoredCycle(
                cycleId, authorityId, "ACTIVE", "", "", "", "", null, -1, "", "",
                0, 0, TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT,
                "", 0, now, null, now, ""));
        try {
            int inserted = jdbc.update("""
                    INSERT INTO
                        rg_test_suite_stability_observation_external_inventory_cycles (
                        cycle_id, authority_id, cycle_status, trust_domain,
                        archive_set_id, failure_domain, snapshot_id, snapshot_at,
                        snapshot_object_count, snapshot_root, next_after_object_id,
                        next_page_sequence, accumulated_object_count, accumulated_root,
                        last_object_id, revision, started_at, completed_at, updated_at,
                        record_fingerprint
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, initial.sqlArguments());
            if (inserted != 1) {
                throw new IllegalStateException("External inventory cycle insert was incomplete");
            }
        } catch (DuplicateKeyException duplicate) {
            throw new IllegalStateException("External inventory cycle identity collided", duplicate);
        }
        return lockCycle(cycleId);
    }

    private StoredAuthority lockAuthority(String authorityId) {
        List<StoredAuthority> rows = jdbc.query("""
                SELECT authority_id, lease_owner, lease_token, lease_epoch, lease_until,
                       revision, active_cycle_id, last_completed_cycle_id,
                       last_success_at, updated_at, record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_authorities
                WHERE authority_id = ?
                FOR UPDATE
                """, this::storedAuthority, authorityId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External inventory authority state is missing");
        }
        return rows.getFirst();
    }

    private StoredCycle lockCycle(String cycleId) {
        List<StoredCycle> rows = jdbc.query("""
                SELECT cycle_id, authority_id, cycle_status, trust_domain,
                       archive_set_id, failure_domain, snapshot_id, snapshot_at,
                       snapshot_object_count, snapshot_root, next_after_object_id,
                       next_page_sequence, accumulated_object_count, accumulated_root,
                       last_object_id, revision, started_at, completed_at, updated_at,
                       record_fingerprint
                FROM rg_test_suite_stability_observation_external_inventory_cycles
                WHERE cycle_id = ?
                FOR UPDATE
                """, this::storedCycle, cycleId);
        if (rows.size() != 1) {
            throw new IllegalStateException("External inventory active cycle is missing");
        }
        return rows.getFirst();
    }

    private StoredAuthority storedAuthority(ResultSet result, int row) throws SQLException {
        StoredAuthority state = unverifiedAuthority(result, row);
        verify(state);
        return state;
    }

    private StoredAuthority unverifiedAuthority(ResultSet result, int row) throws SQLException {
        return new StoredAuthority(
                result.getString("authority_id"), result.getString("lease_owner"),
                result.getString("lease_token"), result.getLong("lease_epoch"),
                instant(result, "lease_until"), result.getLong("revision"),
                result.getString("active_cycle_id"),
                result.getString("last_completed_cycle_id"),
                nullableInstant(result, "last_success_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private StoredCycle storedCycle(ResultSet result, int row) throws SQLException {
        StoredCycle cycle = unverifiedCycle(result, row);
        verify(cycle);
        return cycle;
    }

    private StoredCycle unverifiedCycle(ResultSet result, int row) throws SQLException {
        return new StoredCycle(
                result.getString("cycle_id"), result.getString("authority_id"),
                result.getString("cycle_status"), result.getString("trust_domain"),
                result.getString("archive_set_id"), result.getString("failure_domain"),
                result.getString("snapshot_id"),
                nullableInstant(result, "snapshot_at"),
                result.getLong("snapshot_object_count"), result.getString("snapshot_root"),
                result.getString("next_after_object_id"),
                result.getLong("next_page_sequence"),
                result.getLong("accumulated_object_count"),
                result.getString("accumulated_root"), result.getString("last_object_id"),
                result.getLong("revision"), instant(result, "started_at"),
                nullableInstant(result, "completed_at"), instant(result, "updated_at"),
                result.getString("record_fingerprint"));
    }

    private String configuredAuthority(String authorityId) {
        String exact = normalized(authorityId);
        if (!IDENTIFIER.matcher(exact).matches()) {
            throw new IllegalArgumentException("Complete external inventory authority ID is required");
        }
        List<String> configured = authority.inventoryAuthorities();
        if (configured == null || configured.isEmpty()) {
            throw new IllegalStateException("External inventory has no configured authorities");
        }
        HashSet<String> identities = new HashSet<>();
        String previous = "";
        boolean found = false;
        for (String candidate : configured) {
            String current = normalized(candidate);
            if (!IDENTIFIER.matcher(current).matches() || !identities.add(current)
                    || previous.compareTo(current) >= 0) {
                throw new IllegalStateException(
                        "External inventory authority order or identity is invalid");
            }
            found |= current.equals(exact);
            previous = current;
        }
        if (!found) {
            throw new IllegalArgumentException("External inventory authority is not configured");
        }
        return exact;
    }

    private String writePage(
            TestSuiteStabilityObservationExternalArchiveInventoryPage page) {
        try {
            return objectMapper.writeValueAsString(page);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot serialize external inventory page", failure);
        }
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException("Database clock returned no timestamp");
        }
        return timestamp.toInstant();
    }

    private static long increment(long value, String name) {
        try {
            return Math.incrementExact(value);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException(name + " overflow", overflow);
        }
    }

    private static PlatformTransactionManager localTransactionManager(JdbcTemplate jdbc) {
        Objects.requireNonNull(jdbc, "jdbc");
        if (jdbc.getDataSource() == null) {
            throw new IllegalArgumentException(
                    "External inventory JDBC adapter requires a datasource");
        }
        return new DataSourceTransactionManager(jdbc.getDataSource());
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        if (value == null) {
            throw new IllegalStateException("Required timestamp column is null: " + column);
        }
        return value.toInstant();
    }

    private static Instant nullableInstant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    /**
     * Replica-owned bounded page-staging settings.
     *
     * @param ownerId stable Resource Gateway replica identity
     * @param leaseDuration whole-second database-clock page lease
     * @param maximumItems fixed requested page size for a cycle
     */
    public record Settings(String ownerId, Duration leaseDuration, int maximumItems) {
        /** Enforces a stable owner, whole-second 1..3600 second lease, and 1..500 item page. */
        public Settings {
            ownerId = normalized(ownerId);
            if (!IDENTIFIER.matcher(ownerId).matches()) {
                throw new IllegalArgumentException("External inventory replica owner ID is required");
            }
            if (leaseDuration == null || leaseDuration.getNano() != 0
                    || leaseDuration.compareTo(MINIMUM_LEASE) < 0
                    || leaseDuration.compareTo(MAXIMUM_LEASE) > 0) {
                throw new IllegalArgumentException(
                        "External inventory lease must be 1 through 3600 whole seconds");
            }
            if (maximumItems < 1
                    || maximumItems
                    > TestSuiteStabilityObservationExternalArchiveInventoryRequest.MAXIMUM_ITEMS) {
                throw new IllegalArgumentException(
                        "External inventory page must request 1 through 500 items");
            }
        }
    }

    /** Closed outcome of one bounded page-stage attempt. */
    public enum PageStatus {
        /** Another replica owns a live authority lease; no remote call occurred. */
        BUSY,
        /** One verified non-terminal page and its successor cursor were committed. */
        STAGED,
        /** One terminal page reproduced the signed count/root and completed the cycle. */
        COMPLETED,
        /** The provider expired the pinned snapshot and the old cycle was closed. */
        SNAPSHOT_EXPIRED
    }

    /**
     * Identity-free database-clock progress for one configured inventory authority.
     *
     * @param observedAt database observation time
     * @param leaseActive whether a replica currently owns a live inventory-page lease
     * @param activeCycle whether an incomplete pinned snapshot cycle exists
     * @param completedCycle whether at least one terminal verified cycle exists
     * @param nextPageSequence next zero-based page sequence for the active cycle
     * @param accumulatedObjectCount verified objects committed by the active cycle
     * @param activeCycleStartedAt active cycle start, or {@code null}
     * @param activeCycleUpdatedAt active cycle update time, or {@code null}
     * @param lastCompletedAt latest completed cycle time, or {@code null}
     */
    public record OperationalSnapshot(
            Instant observedAt,
            boolean leaseActive,
            boolean activeCycle,
            boolean completedCycle,
            long nextPageSequence,
            long accumulatedObjectCount,
            Instant activeCycleStartedAt,
            Instant activeCycleUpdatedAt,
            Instant lastCompletedAt) {
        /** Rejects impossible lifecycle combinations before they drive orchestration. */
        public OperationalSnapshot {
            boolean activeTimes = activeCycleStartedAt != null && activeCycleUpdatedAt != null;
            if (observedAt == null || leaseActive && !activeCycle
                    || nextPageSequence < 0 || accumulatedObjectCount < 0
                    || activeCycle != activeTimes
                    || (!activeCycle && (nextPageSequence != 0 || accumulatedObjectCount != 0))
                    || (activeCycle && (activeCycleUpdatedAt.isBefore(activeCycleStartedAt)
                    || activeCycleUpdatedAt.isAfter(observedAt)))
                    || completedCycle != (lastCompletedAt != null)
                    || (lastCompletedAt != null && lastCompletedAt.isAfter(observedAt))) {
                throw new IllegalArgumentException(
                        "Invalid external inventory operational snapshot");
            }
        }
    }

    /**
     * Payload-free result for one authority page.
     *
     * @param status closed stage outcome
     * @param authorityId exact configured authority
     * @param cycleId durable cycle identity, empty only while busy
     * @param pageSequence attempted zero-based page sequence
     * @param pageItemCount items committed by this attempt
     * @param accumulatedObjectCount items durably accumulated by this cycle
     */
    public record PageAttempt(
            PageStatus status,
            String authorityId,
            String cycleId,
            long pageSequence,
            int pageItemCount,
            long accumulatedObjectCount) {
        /** Rejects malformed operational results before callers can mistake them for progress. */
        public PageAttempt {
            Objects.requireNonNull(status, "status");
            authorityId = normalized(authorityId);
            cycleId = normalized(cycleId);
            boolean busy = status == PageStatus.BUSY;
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || busy != cycleId.isEmpty()
                    || (!busy && !isUuid(cycleId))
                    || pageSequence < 0 || pageItemCount < 0
                    || pageItemCount
                    > TestSuiteStabilityObservationExternalArchiveInventoryRequest.MAXIMUM_ITEMS
                    || accumulatedObjectCount < 0
                    || (busy && (pageSequence != 0 || pageItemCount != 0
                    || accumulatedObjectCount != 0))
                    || (!busy && accumulatedObjectCount < pageItemCount)
                    || (status == PageStatus.STAGED && pageItemCount == 0)
                    || (status == PageStatus.SNAPSHOT_EXPIRED && pageItemCount != 0)) {
                throw new IllegalArgumentException("Invalid external inventory page attempt");
            }
        }

        private static PageAttempt busy(String authorityId) {
            return new PageAttempt(PageStatus.BUSY, authorityId, "", 0, 0, 0);
        }
    }

    /** Fail-closed signal that the database-clock owner fence no longer belongs to this replica. */
    public static final class LeaseLostException extends IllegalStateException {
        /** Creates a payload-free lease-loss failure without exposing its token. */
        public LeaseLostException() {
            super("External inventory page lease is no longer live");
        }
    }

    private record PageLease(
            String authorityId,
            String cycleId,
            String ownerId,
            String token,
            long epoch,
            long revision,
            Instant leaseUntil,
            TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor cursor) {
    }

    private record StagedReplay(long objectCount, String root, String lastObjectId) {
    }

    private record PageSpan(long pageCount, long minimumSequence, long maximumSequence) {
    }

    private record StoredAuthority(
            String authorityId,
            String leaseOwner,
            String leaseToken,
            long leaseEpoch,
            Instant leaseUntil,
            long revision,
            String activeCycleId,
            String lastCompletedCycleId,
            Instant lastSuccessAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void validate() {
            if (!IDENTIFIER.matcher(authorityId).matches()
                    || (leaseOwner.isEmpty() != leaseToken.isEmpty())
                    || (!leaseOwner.isEmpty() && !IDENTIFIER.matcher(leaseOwner).matches())
                    || (!leaseToken.isEmpty() && !isUuid(leaseToken))
                    || leaseEpoch < 0 || revision < 0 || leaseUntil == null || updatedAt == null
                    || (!leaseToken.isEmpty() && activeCycleId.isEmpty())
                    || (!activeCycleId.isEmpty() && !isUuid(activeCycleId))
                    || (!lastCompletedCycleId.isEmpty()
                    && !isUuid(lastCompletedCycleId))
                    || (lastCompletedCycleId.isEmpty() != (lastSuccessAt == null))
                    || (lastSuccessAt != null && updatedAt.isBefore(lastSuccessAt))) {
                throw new IllegalStateException("External inventory authority state is corrupt");
            }
        }

        private boolean leaseLiveAt(Instant now) {
            return !leaseToken.isEmpty() && now.isBefore(leaseUntil);
        }

        private boolean matches(PageLease lease) {
            return authorityId.equals(lease.authorityId())
                    && activeCycleId.equals(lease.cycleId())
                    && leaseOwner.equals(lease.ownerId())
                    && leaseToken.equals(lease.token())
                    && leaseEpoch == lease.epoch()
                    && revision == lease.revision()
                    && leaseUntil.equals(lease.leaseUntil());
        }

        private StoredAuthority withFingerprint(String fingerprint) {
            return new StoredAuthority(authorityId, leaseOwner, leaseToken, leaseEpoch,
                    leaseUntil, revision, activeCycleId, lastCompletedCycleId, lastSuccessAt,
                    updatedAt, fingerprint);
        }

        private Object[] sqlArguments() {
            return new Object[]{authorityId, leaseOwner, leaseToken, leaseEpoch,
                    Timestamp.from(leaseUntil), revision, activeCycleId, lastCompletedCycleId,
                    lastSuccessAt == null ? null : Timestamp.from(lastSuccessAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] updateArguments(StoredAuthority expected) {
            return new Object[]{leaseOwner, leaseToken, leaseEpoch, Timestamp.from(leaseUntil),
                    revision, activeCycleId, lastCompletedCycleId,
                    lastSuccessAt == null ? null : Timestamp.from(lastSuccessAt),
                    Timestamp.from(updatedAt), recordFingerprint, expected.authorityId,
                    expected.revision, expected.recordFingerprint};
        }
    }

    private record StoredCycle(
            String cycleId,
            String authorityId,
            String status,
            String trustDomain,
            String archiveSetId,
            String failureDomain,
            String snapshotId,
            Instant snapshotAt,
            long snapshotObjectCount,
            String snapshotRoot,
            String nextAfterObjectId,
            long nextPageSequence,
            long accumulatedObjectCount,
            String accumulatedRoot,
            String lastObjectId,
            long revision,
            Instant startedAt,
            Instant completedAt,
            Instant updatedAt,
            String recordFingerprint) {
        private void validate() {
            boolean knownStatus = "ACTIVE".equals(status) || "COMPLETED".equals(status)
                    || "SNAPSHOT_EXPIRED".equals(status);
            boolean initial = nextPageSequence == 0 && trustDomain.isEmpty()
                    && archiveSetId.isEmpty() && failureDomain.isEmpty() && snapshotId.isEmpty()
                    && snapshotAt == null && snapshotObjectCount == -1 && snapshotRoot.isEmpty()
                    && nextAfterObjectId.isEmpty() && accumulatedObjectCount == 0
                    && TestSuiteStabilityObservationExternalArchiveInventoryIntegrity.EMPTY_ROOT
                    .equals(accumulatedRoot)
                    && lastObjectId.isEmpty();
            boolean progressed = nextPageSequence > 0 && !snapshotId.isEmpty()
                    && IDENTIFIER.matcher(trustDomain).matches()
                    && IDENTIFIER.matcher(archiveSetId).matches()
                    && IDENTIFIER.matcher(failureDomain).matches()
                    && SNAPSHOT_ID.matcher(snapshotId).matches()
                    && snapshotAt != null && snapshotObjectCount >= 0
                    && FINGERPRINT.matcher(snapshotRoot).matches()
                    && accumulatedObjectCount >= 0
                    && accumulatedObjectCount <= snapshotObjectCount
                    && FINGERPRINT.matcher(accumulatedRoot).matches()
                    && (lastObjectId.isEmpty() || OBJECT_ID.matcher(lastObjectId).matches());
            boolean activeProgress = "ACTIVE".equals(status) && progressed
                    && accumulatedObjectCount < snapshotObjectCount
                    && OBJECT_ID.matcher(nextAfterObjectId).matches()
                    && nextAfterObjectId.equals(lastObjectId);
            boolean completed = "COMPLETED".equals(status) && progressed
                    && nextAfterObjectId.isEmpty()
                    && accumulatedObjectCount == snapshotObjectCount
                    && accumulatedRoot.equals(snapshotRoot);
            boolean expired = "SNAPSHOT_EXPIRED".equals(status)
                    && (initial || (progressed
                    && OBJECT_ID.matcher(nextAfterObjectId).matches()
                    && nextAfterObjectId.equals(lastObjectId)));
            if (!isUuid(cycleId) || !IDENTIFIER.matcher(authorityId).matches() || !knownStatus
                    || !("ACTIVE".equals(status) && initial || activeProgress
                    || completed || expired) || revision < 0
                    || startedAt == null || updatedAt == null
                    || ("ACTIVE".equals(status) && completedAt != null)
                    || (!"ACTIVE".equals(status) && completedAt == null)
                    || updatedAt.isBefore(startedAt)
                    || (completedAt != null && (completedAt.isBefore(startedAt)
                    || updatedAt.isBefore(completedAt)))) {
                throw new IllegalStateException("External inventory cycle state is corrupt");
            }
        }

        private void requireActiveFor(String expectedAuthority) {
            if (!"ACTIVE".equals(status) || !authorityId.equals(expectedAuthority)) {
                throw new IllegalStateException("External inventory cycle is not active");
            }
        }

        private TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor cursor() {
            return nextPageSequence == 0
                    ? TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor.initial()
                    : new TestSuiteStabilityObservationExternalArchiveInventoryAuthority.Cursor(
                    snapshotId, nextAfterObjectId, nextPageSequence);
        }

        private StoredCycle withFingerprint(String fingerprint) {
            return new StoredCycle(cycleId, authorityId, status, trustDomain, archiveSetId,
                    failureDomain, snapshotId, snapshotAt, snapshotObjectCount, snapshotRoot,
                    nextAfterObjectId, nextPageSequence, accumulatedObjectCount, accumulatedRoot,
                    lastObjectId, revision, startedAt, completedAt, updatedAt, fingerprint);
        }

        private Object[] sqlArguments() {
            return new Object[]{cycleId, authorityId, status, trustDomain, archiveSetId,
                    failureDomain, snapshotId, snapshotAt == null ? null : Timestamp.from(snapshotAt),
                    snapshotObjectCount, snapshotRoot, nextAfterObjectId, nextPageSequence,
                    accumulatedObjectCount, accumulatedRoot, lastObjectId, revision,
                    Timestamp.from(startedAt),
                    completedAt == null ? null : Timestamp.from(completedAt),
                    Timestamp.from(updatedAt), recordFingerprint};
        }

        private Object[] updateArguments(StoredCycle expected) {
            Object[] inserted = sqlArguments();
            return new Object[]{inserted[2], inserted[3], inserted[4], inserted[5], inserted[6],
                    inserted[7], inserted[8], inserted[9], inserted[10], inserted[11],
                    inserted[12], inserted[13], inserted[14], inserted[15], inserted[16],
                    inserted[17], inserted[18], inserted[19], expected.cycleId,
                    expected.revision, expected.recordFingerprint};
        }
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }
}
