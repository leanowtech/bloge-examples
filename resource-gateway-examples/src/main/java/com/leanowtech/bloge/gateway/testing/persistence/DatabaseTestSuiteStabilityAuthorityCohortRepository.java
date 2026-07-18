package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortPolicy;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityAuthorityCohortRepository;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Database-clock lease registry for exact authority-trust cohort convergence.
 *
 * <p>Each process start owns a distinct row, so two live processes claiming one serving slot are
 * visible as a collision instead of overwriting each other. Reads use one repeatable-read database
 * time boundary and never infer membership from application clocks. Complete configured set
 * equality, shared policy identity, artifact/protocol/authority identity, local trust health and
 * one trust-generation fingerprint and, for external inventories, one witnessed publication
 * generation are all required before convergence.</p>
 */
public final class DatabaseTestSuiteStabilityAuthorityCohortRepository
        implements TestSuiteStabilityAuthorityCohortRepository {

    private static final int MAXIMUM_LIVE_ROWS =
            TestSuiteStabilityAuthorityCohortPolicy.maximumReplicas() * 2;
    private static final int PURGE_BATCH = 256;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TestSuiteStabilityAuthorityCohortPolicy policy;
    private final String policyFingerprint;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one isolated test-runtime cohort registry.
     *
     * @param jdbc test-runtime JDBC facade
     * @param objectMapper canonical fingerprint mapper
     * @param policy exact deployment-owned cohort policy
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseTestSuiteStabilityAuthorityCohortRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            TestSuiteStabilityAuthorityCohortPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.policyFingerprint = policy.cohortFingerprint(objectMapper);
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates or additively upgrades the bounded membership registry and expiry index. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_authority_cohort_scope_locks (
                    scope_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_authority_active_cohorts (
                    scope_id VARCHAR(255) PRIMARY KEY,
                    cohort_id VARCHAR(255) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_authority_inventory_floors (
                    scope_id VARCHAR(255) PRIMARY KEY,
                    revision BIGINT NOT NULL,
                    material_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_suite_stability_authority_cohort_members (
                    scope_id VARCHAR(255) NOT NULL,
                    cohort_id VARCHAR(255) NOT NULL,
                    instance_id VARCHAR(255) NOT NULL,
                    startup_id VARCHAR(36) NOT NULL,
                    artifact_fingerprint VARCHAR(71) NOT NULL,
                    policy_fingerprint VARCHAR(71) NOT NULL,
                    protocol_version VARCHAR(255) NOT NULL,
                    authority_id VARCHAR(255) NOT NULL,
                    provider_type VARCHAR(64) NOT NULL,
                    trust_available BOOLEAN NOT NULL,
                    refresh_state VARCHAR(64) NOT NULL,
                    snapshot_fingerprint VARCHAR(71) NOT NULL,
                    serving_inventory_source_sequence BIGINT NOT NULL,
                    serving_inventory_generation_fingerprint VARCHAR(71) NOT NULL,
                    active_key_count BIGINT NOT NULL,
                    last_successful_refresh_at TIMESTAMP WITH TIME ZONE,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    purge_after TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (scope_id, cohort_id, instance_id, startup_id)
                )
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_authority_cohort_members
                ADD COLUMN IF NOT EXISTS serving_inventory_source_sequence
                    BIGINT NOT NULL DEFAULT 0
                """);
        jdbc.execute("""
                ALTER TABLE rg_test_suite_stability_authority_cohort_members
                ADD COLUMN IF NOT EXISTS serving_inventory_generation_fingerprint
                    VARCHAR(71) NOT NULL DEFAULT ''
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_suite_stability_authority_cohort_expiry_idx
                ON rg_test_suite_stability_authority_cohort_members (
                    purge_after, scope_id, cohort_id, instance_id, startup_id)
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot heartbeat(Member member) {
        requireLocalMember(member);
        Snapshot result = mutations.execute(status -> {
            Instant now = databaseNow();
            Instant leaseExpiresAt = now.plus(policy.leaseDuration());
            Instant purgeAfter = leaseExpiresAt.plus(policy.recordRetention());
            boolean ownsActiveCohort = claimOrRenewActiveCohort(now, leaseExpiresAt);
            if (ownsActiveCohort) {
                enforceServingInventoryFloor(now);
            }
            String recordFingerprint = recordFingerprint(
                    member, now, leaseExpiresAt, purgeAfter);
            jdbc.update("""
                    MERGE INTO rg_test_suite_stability_authority_cohort_members (
                        scope_id, cohort_id, instance_id, startup_id, artifact_fingerprint,
                        policy_fingerprint, protocol_version, authority_id, provider_type,
                        trust_available, refresh_state, snapshot_fingerprint,
                        serving_inventory_source_sequence,
                        serving_inventory_generation_fingerprint, active_key_count,
                        last_successful_refresh_at, observed_at, lease_expires_at,
                        purge_after, record_fingerprint
                    ) KEY (scope_id, cohort_id, instance_id, startup_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    member.scopeId(), member.cohortId(), member.instanceId(), member.startupId(),
                    member.artifactFingerprint(), member.policyFingerprint(),
                    member.protocolVersion(), member.authorityId(), member.providerType(),
                    member.trustAvailable(), member.refreshState(),
                    member.snapshotFingerprint(), member.servingInventorySourceSequence(),
                    member.servingInventoryGenerationFingerprint(), member.activeKeyCount(),
                    timestamp(member.lastSuccessfulRefreshAt()), Timestamp.from(now),
                    Timestamp.from(leaseExpiresAt), Timestamp.from(purgeAfter),
                    recordFingerprint);
            purgeExpired(now);
            return snapshotAt(now);
        });
        return Objects.requireNonNull(result, "cohort heartbeat transaction result");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> snapshotAt(databaseNow()));
        return Objects.requireNonNull(result, "cohort snapshot transaction result");
    }

    /** {@inheritDoc} */
    @Override
    public void withdraw(String instanceId, String startupId) {
        if (!policy.instanceId().equals(normalized(instanceId))
                || !policy.startupId().equals(normalized(startupId))) {
            throw new IllegalArgumentException(
                    "Cohort withdrawal must match the local process identity");
        }
        mutations.executeWithoutResult(status -> jdbc.update("""
                DELETE FROM rg_test_suite_stability_authority_cohort_members
                WHERE scope_id = ? AND cohort_id = ? AND instance_id = ? AND startup_id = ?
                """, policy.scopeId(), policy.cohortId(),
                policy.instanceId(), policy.startupId()));
    }

    private Snapshot snapshotAt(Instant now) {
        List<MemberRow> selected = jdbc.query("""
                SELECT scope_id, cohort_id, instance_id, startup_id, artifact_fingerprint,
                       policy_fingerprint, protocol_version, authority_id, provider_type,
                       trust_available, refresh_state, snapshot_fingerprint,
                       serving_inventory_source_sequence,
                       serving_inventory_generation_fingerprint, active_key_count,
                       last_successful_refresh_at, observed_at, lease_expires_at,
                       purge_after, record_fingerprint
                FROM rg_test_suite_stability_authority_cohort_members
                WHERE scope_id = ? AND cohort_id = ?
                ORDER BY instance_id, startup_id
                LIMIT ?
                """, this::row, policy.scopeId(), policy.cohortId(), MAXIMUM_LIVE_ROWS + 1);

        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        ActiveCohort activeCohort = activeCohort();
        boolean ownsActiveCohort = false;
        if (activeCohort == null) {
            blockers.add("COHORT_NOT_ACTIVE");
        } else if (!activeCohort.valid(objectMapper)) {
            blockers.add("COHORT_AUTHORITY_CORRUPT");
        } else if (!activeCohort.leaseExpiresAt().isAfter(now)) {
            blockers.add("COHORT_NOT_ACTIVE");
        } else if (!policy.cohortId().equals(activeCohort.cohortId())
                || !policyFingerprint.equals(activeCohort.policyFingerprint())) {
            blockers.add("COHORT_NOT_ACTIVE");
        } else {
            ownsActiveCohort = true;
        }
        if (ownsActiveCohort && policy.servingInventory().externallyAttested()) {
            validateServingInventoryFloor(blockers);
        }
        if (selected.size() > MAXIMUM_LIVE_ROWS) {
            blockers.add("INVENTORY_OVERFLOW");
            selected = selected.subList(0, MAXIMUM_LIVE_ROWS);
        }
        List<MemberRow> live = new ArrayList<>();
        for (MemberRow row : selected) {
            if (row.leaseExpiresAt() == null || row.observedAt() == null
                    || row.purgeAfter() == null
                    || row.leaseExpiresAt().isBefore(row.observedAt())
                    || row.purgeAfter().isBefore(row.leaseExpiresAt())) {
                blockers.add("INVENTORY_CORRUPT");
                continue;
            }
            if (!row.leaseExpiresAt().isAfter(now)) {
                continue;
            }
            if (!row.valid(objectMapper, policy.leaseDuration(), policy.recordRetention())) {
                blockers.add("INVENTORY_CORRUPT");
                continue;
            }
            if (!servingInventoryGenerationMatchesPolicy(row.member())) {
                blockers.add("INVENTORY_CORRUPT");
                continue;
            }
            live.add(row);
        }

        Map<String, List<MemberRow>> byInstance = new HashMap<>();
        live.forEach(row -> byInstance.computeIfAbsent(
                row.member().instanceId(), ignored -> new ArrayList<>()).add(row));
        Set<String> expected = policy.expectedInstanceIds();
        int missing = (int) expected.stream().filter(id -> !byInstance.containsKey(id)).count();
        int unexpected = (int) live.stream().filter(
                row -> !expected.contains(row.member().instanceId())).count();
        int duplicates = (int) expected.stream().filter(
                id -> byInstance.getOrDefault(id, List.of()).size() > 1).count();
        boolean localProcessPresent = live.stream().anyMatch(row ->
                policy.instanceId().equals(row.member().instanceId())
                        && policy.startupId().equals(row.member().startupId()));
        int divergentArtifact = (int) live.stream().filter(row ->
                !policy.artifactFingerprint().equals(row.member().artifactFingerprint())).count();
        int divergentPolicy = (int) live.stream().filter(row ->
                !policyFingerprint.equals(row.member().policyFingerprint())).count();
        int divergentProtocol = (int) live.stream().filter(row ->
                !policy.protocolVersion().equals(row.member().protocolVersion())).count();
        int divergentAuthority = (int) live.stream().filter(row ->
                !policy.authorityId().equals(row.member().authorityId())
                        || !"DYNAMIC_JWKS_ED25519".equals(row.member().providerType())).count();
        int healthy = (int) live.stream().filter(row -> row.member().trustAvailable()
                && "HEALTHY".equals(row.member().refreshState())
                && row.member().activeKeyCount() > 0
                && row.member().lastSuccessfulRefreshAt() != null).count();
        Set<String> generations = new HashSet<>();
        live.stream().map(row -> row.member().snapshotFingerprint())
                .filter(value -> value.matches("sha256:[a-f0-9]{64}"))
                .forEach(generations::add);
        Set<String> servingInventoryGenerations = new HashSet<>();
        if (policy.servingInventory().externallyAttested()) {
            live.stream().map(MemberRow::member)
                    .map(member -> member.servingInventorySourceSequence() + ":"
                            + member.servingInventoryGenerationFingerprint())
                    .forEach(servingInventoryGenerations::add);
        }

        if (missing > 0) {
            blockers.add("MEMBER_MISSING");
        }
        if (unexpected > 0) {
            blockers.add("UNEXPECTED_MEMBER");
        }
        if (duplicates > 0) {
            blockers.add("DUPLICATE_INSTANCE");
        }
        if (!localProcessPresent) {
            blockers.add("LOCAL_PROCESS_NOT_REGISTERED");
        }
        if (divergentPolicy > 0) {
            blockers.add("POLICY_DIVERGED");
        }
        if (divergentArtifact > 0) {
            blockers.add("ARTIFACT_DIVERGED");
        }
        if (divergentProtocol > 0) {
            blockers.add("PROTOCOL_DIVERGED");
        }
        if (divergentAuthority > 0) {
            blockers.add("AUTHORITY_DIVERGED");
        }
        if (healthy != live.size()) {
            blockers.add("MEMBER_UNHEALTHY");
        }
        if (!live.isEmpty() && generations.size() != 1) {
            blockers.add("SNAPSHOT_DIVERGED");
        }
        if (!live.isEmpty() && policy.servingInventory().externallyAttested()
                && servingInventoryGenerations.size() != 1) {
            blockers.add("SERVING_INVENTORY_GENERATION_DIVERGED");
        }
        List<String> exactBlockers = List.copyOf(blockers);
        boolean converged = exactBlockers.isEmpty()
                && live.size() == expected.size() && healthy == expected.size();
        String status = converged ? "CONVERGED" : exactBlockers.getFirst();
        Instant nextExpiry = live.stream().map(MemberRow::leaseExpiresAt)
                .min(Instant::compareTo).orElse(null);
        return new Snapshot(
                "bloge.testSuiteStabilityAuthorityCohortSnapshot.v1",
                converged, status, expected.size(), live.size(), healthy,
                generations.size(), servingInventoryGenerations.size(),
                missing, unexpected, duplicates,
                divergentArtifact, divergentPolicy, divergentProtocol,
                divergentAuthority, now, nextExpiry, exactBlockers);
    }

    private void requireLocalMember(Member member) {
        if (member == null
                || !policy.scopeId().equals(member.scopeId())
                || !policy.cohortId().equals(member.cohortId())
                || !policy.instanceId().equals(member.instanceId())
                || !policy.startupId().equals(member.startupId())
                || !policy.artifactFingerprint().equals(member.artifactFingerprint())
                || !policyFingerprint.equals(member.policyFingerprint())
                || !policy.protocolVersion().equals(member.protocolVersion())
                || !policy.authorityId().equals(member.authorityId())
                || !"DYNAMIC_JWKS_ED25519".equals(member.providerType())
                || !servingInventoryGenerationMatchesPolicy(member)) {
            throw new IllegalArgumentException(
                    "Cohort heartbeat does not match the local deployment policy");
        }
    }

    private boolean servingInventoryGenerationMatchesPolicy(Member member) {
        if (policy.servingInventory().externallyAttested()) {
            return member.servingInventorySourceSequence() > 0
                    && !member.servingInventoryGenerationFingerprint().isBlank();
        }
        return member.servingInventorySourceSequence() == 0
                && member.servingInventoryGenerationFingerprint().isBlank();
    }

    private boolean claimOrRenewActiveCohort(Instant now, Instant leaseExpiresAt) {
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_authority_cohort_scope_locks (scope_id)
                KEY (scope_id) VALUES (?)
                """, policy.scopeId());
        jdbc.queryForObject("""
                SELECT scope_id
                FROM rg_test_suite_stability_authority_cohort_scope_locks
                WHERE scope_id = ? FOR UPDATE
                """, String.class, policy.scopeId());
        ActiveCohort current = activeCohort();
        if (current != null && !current.valid(objectMapper)) {
            throw new IllegalStateException("Active authority cohort record is corrupt");
        }
        if (current != null && current.leaseExpiresAt().isAfter(now)
                && (!policy.cohortId().equals(current.cohortId())
                || !policyFingerprint.equals(current.policyFingerprint()))) {
            return false;
        }
        String fingerprint = activeCohortFingerprint(now, leaseExpiresAt);
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_authority_active_cohorts (
                    scope_id, cohort_id, policy_fingerprint, observed_at,
                    lease_expires_at, record_fingerprint
                ) KEY (scope_id) VALUES (?, ?, ?, ?, ?, ?)
                """, policy.scopeId(), policy.cohortId(), policyFingerprint,
                Timestamp.from(now), Timestamp.from(leaseExpiresAt), fingerprint);
        return true;
    }

    private void enforceServingInventoryFloor(Instant observedAt) {
        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation inventory =
                policy.servingInventory();
        if (!inventory.externallyAttested()) {
            return;
        }
        InventoryFloor current = inventoryFloor();
        if (current != null && !current.valid(objectMapper)) {
            throw new IllegalStateException("Serving inventory revision floor is corrupt");
        }
        if (current != null && (current.revision() > inventory.revision()
                || current.revision() == inventory.revision()
                && (!current.materialFingerprint().equals(inventory.materialFingerprint())
                || !current.policyFingerprint().equals(inventory.policyFingerprint())))) {
            throw new IllegalStateException(
                    "Serving inventory revision is rolled back or forked");
        }
        if (current != null && current.revision() == inventory.revision()) {
            return;
        }
        String fingerprint = inventoryFloorFingerprint(inventory, observedAt);
        jdbc.update("""
                MERGE INTO rg_test_suite_stability_authority_inventory_floors (
                    scope_id, revision, material_fingerprint, policy_fingerprint,
                    observed_at, record_fingerprint
                ) KEY (scope_id) VALUES (?, ?, ?, ?, ?, ?)
                """, policy.scopeId(), inventory.revision(),
                inventory.materialFingerprint(), inventory.policyFingerprint(),
                Timestamp.from(observedAt), fingerprint);
    }

    private void validateServingInventoryFloor(LinkedHashSet<String> blockers) {
        TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation inventory =
                policy.servingInventory();
        InventoryFloor floor = inventoryFloor();
        if (floor == null) {
            blockers.add("SERVING_INVENTORY_FLOOR_MISSING");
        } else if (!floor.valid(objectMapper)) {
            blockers.add("SERVING_INVENTORY_FLOOR_CORRUPT");
        } else if (floor.revision() > inventory.revision()) {
            blockers.add("SERVING_INVENTORY_ROLLBACK");
        } else if (floor.revision() < inventory.revision()) {
            blockers.add("SERVING_INVENTORY_FLOOR_STALE");
        } else if (!floor.materialFingerprint().equals(inventory.materialFingerprint())
                || !floor.policyFingerprint().equals(inventory.policyFingerprint())) {
            blockers.add("SERVING_INVENTORY_FORKED");
        }
    }

    private InventoryFloor inventoryFloor() {
        List<InventoryFloor> rows = jdbc.query("""
                SELECT scope_id, revision, material_fingerprint, policy_fingerprint,
                       observed_at, record_fingerprint
                FROM rg_test_suite_stability_authority_inventory_floors
                WHERE scope_id = ?
                """, (result, rowNumber) -> new InventoryFloor(
                result.getString("scope_id"), result.getLong("revision"),
                result.getString("material_fingerprint"),
                result.getString("policy_fingerprint"), instant(result, "observed_at"),
                result.getString("record_fingerprint")), policy.scopeId());
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate serving inventory revision floor");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String inventoryFloorFingerprint(
            TestSuiteStabilityAuthorityCohortPolicy.ServingInventoryAttestation inventory,
            Instant observedAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.testSuiteStabilityServingInventoryFloor.v1"),
                Map.entry("scopeId", policy.scopeId()),
                Map.entry("revision", inventory.revision()),
                Map.entry("materialFingerprint", inventory.materialFingerprint()),
                Map.entry("policyFingerprint", inventory.policyFingerprint()),
                Map.entry("observedAt", observedAt.toString())));
    }

    private ActiveCohort activeCohort() {
        List<ActiveCohort> rows = jdbc.query("""
                SELECT scope_id, cohort_id, policy_fingerprint, observed_at,
                       lease_expires_at, record_fingerprint
                FROM rg_test_suite_stability_authority_active_cohorts
                WHERE scope_id = ?
                """, (result, rowNumber) -> new ActiveCohort(
                result.getString("scope_id"), result.getString("cohort_id"),
                result.getString("policy_fingerprint"), instant(result, "observed_at"),
                instant(result, "lease_expires_at"),
                result.getString("record_fingerprint")), policy.scopeId());
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate active authority cohort scope");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String activeCohortFingerprint(Instant observedAt, Instant leaseExpiresAt) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion",
                        "bloge.testSuiteStabilityAuthorityActiveCohort.v1"),
                Map.entry("scopeId", policy.scopeId()),
                Map.entry("cohortId", policy.cohortId()),
                Map.entry("policyFingerprint", policyFingerprint),
                Map.entry("observedAt", observedAt.toString()),
                Map.entry("leaseExpiresAt", leaseExpiresAt.toString())));
    }

    private void purgeExpired(Instant now) {
        List<PurgeKey> keys = jdbc.query("""
                SELECT scope_id, cohort_id, instance_id, startup_id
                FROM rg_test_suite_stability_authority_cohort_members
                WHERE purge_after < ?
                ORDER BY purge_after, cohort_id, instance_id, startup_id
                LIMIT ?
                """, (rs, rowNum) -> new PurgeKey(
                rs.getString("scope_id"), rs.getString("cohort_id"), rs.getString("instance_id"),
                rs.getString("startup_id")), Timestamp.from(now), PURGE_BATCH);
        keys.forEach(key -> jdbc.update("""
                DELETE FROM rg_test_suite_stability_authority_cohort_members
                WHERE scope_id = ? AND cohort_id = ? AND instance_id = ? AND startup_id = ?
                  AND purge_after < ?
                """, key.scopeId(), key.cohortId(), key.instanceId(), key.startupId(),
                Timestamp.from(now)));
    }

    private String recordFingerprint(
            Member member, Instant observedAt, Instant leaseExpiresAt, Instant purgeAfter) {
        return ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                Map.entry("schemaVersion", "bloge.testSuiteStabilityAuthorityCohortRecord.v1"),
                Map.entry("member", member),
                Map.entry("observedAt", observedAt.toString()),
                Map.entry("leaseExpiresAt", leaseExpiresAt.toString()),
                Map.entry("purgeAfter", purgeAfter.toString())));
    }

    private MemberRow row(ResultSet result, int rowNumber) throws SQLException {
        return new MemberRow(
                result.getString("scope_id"), result.getString("cohort_id"),
                result.getString("instance_id"),
                result.getString("startup_id"), result.getString("artifact_fingerprint"),
                result.getString("policy_fingerprint"), result.getString("protocol_version"),
                result.getString("authority_id"), result.getString("provider_type"),
                result.getBoolean("trust_available"), result.getString("refresh_state"),
                result.getString("snapshot_fingerprint"),
                result.getLong("serving_inventory_source_sequence"),
                result.getString("serving_inventory_generation_fingerprint"),
                result.getLong("active_key_count"),
                instant(result, "last_successful_refresh_at"),
                instant(result, "observed_at"), instant(result, "lease_expires_at"),
                instant(result, "purge_after"),
                result.getString("record_fingerprint"));
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", (result, row) ->
                        instant(result, 1)), "database time");
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet result, int column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record MemberRow(
            String scopeId,
            String cohortId,
            String instanceId,
            String startupId,
            String artifactFingerprint,
            String policyFingerprint,
            String protocolVersion,
            String authorityId,
            String providerType,
            boolean trustAvailable,
            String refreshState,
            String snapshotFingerprint,
            long servingInventorySourceSequence,
            String servingInventoryGenerationFingerprint,
            long activeKeyCount,
            Instant lastSuccessfulRefreshAt,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter,
            String recordFingerprint) {

        private Member member() {
            return new Member(Member.SCHEMA_VERSION,
                    scopeId, cohortId, instanceId, startupId,
                    artifactFingerprint, policyFingerprint,
                    protocolVersion, authorityId, providerType, trustAvailable, refreshState,
                    snapshotFingerprint, servingInventorySourceSequence,
                    servingInventoryGenerationFingerprint, activeKeyCount,
                    lastSuccessfulRefreshAt);
        }

        private boolean valid(
                ObjectMapper objectMapper,
                java.time.Duration leaseDuration,
                java.time.Duration recordRetention) {
            try {
                Member value = member();
                if (!leaseExpiresAt.equals(observedAt.plus(leaseDuration))
                        || !purgeAfter.equals(leaseExpiresAt.plus(recordRetention))) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                        Map.entry("schemaVersion",
                                "bloge.testSuiteStabilityAuthorityCohortRecord.v1"),
                        Map.entry("member", value),
                        Map.entry("observedAt", observedAt.toString()),
                        Map.entry("leaseExpiresAt", leaseExpiresAt.toString()),
                        Map.entry("purgeAfter", purgeAfter.toString())));
                return expected.equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private record PurgeKey(
            String scopeId, String cohortId, String instanceId, String startupId) {
    }

    private record ActiveCohort(
            String scopeId,
            String cohortId,
            String policyFingerprint,
            Instant observedAt,
            Instant leaseExpiresAt,
            String recordFingerprint) {

        private boolean valid(ObjectMapper objectMapper) {
            try {
                if (scopeId == null || cohortId == null || policyFingerprint == null
                        || observedAt == null || leaseExpiresAt == null
                        || recordFingerprint == null
                        || !scopeId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                        || !cohortId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                        || !policyFingerprint.matches("sha256:[a-f0-9]{64}")
                        || !recordFingerprint.matches("sha256:[a-f0-9]{64}")
                        || !leaseExpiresAt.isAfter(observedAt)) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                        Map.entry("schemaVersion",
                                "bloge.testSuiteStabilityAuthorityActiveCohort.v1"),
                        Map.entry("scopeId", scopeId),
                        Map.entry("cohortId", cohortId),
                        Map.entry("policyFingerprint", policyFingerprint),
                        Map.entry("observedAt", observedAt.toString()),
                        Map.entry("leaseExpiresAt", leaseExpiresAt.toString())));
                return expected.equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private record InventoryFloor(
            String scopeId,
            long revision,
            String materialFingerprint,
            String policyFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(ObjectMapper objectMapper) {
            try {
                if (scopeId == null || revision < 1 || materialFingerprint == null
                        || policyFingerprint == null || observedAt == null
                        || recordFingerprint == null
                        || !scopeId.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}")
                        || !materialFingerprint.matches("sha256:[a-f0-9]{64}")
                        || !policyFingerprint.matches("sha256:[a-f0-9]{64}")
                        || !recordFingerprint.matches("sha256:[a-f0-9]{64}")) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.ofEntries(
                        Map.entry("schemaVersion",
                                "bloge.testSuiteStabilityServingInventoryFloor.v1"),
                        Map.entry("scopeId", scopeId),
                        Map.entry("revision", revision),
                        Map.entry("materialFingerprint", materialFingerprint),
                        Map.entry("policyFingerprint", policyFingerprint),
                        Map.entry("observedAt", observedAt.toString())));
                return expected.equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }
}
