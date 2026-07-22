package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority;
import com.leanowtech.bloge.gateway.testing.api.DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority.CohortBinding;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryCohortGate.Observation;
import com.leanowtech.bloge.gateway.testing.api.TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Database-clock lease registry for an exact signed physical provider-inventory cohort.
 *
 * <p>Scope, cohort, expected replicas, source sequence, and source generation are read from the
 * verified dynamic publication on every operation. Local deployment properties cannot narrow the
 * expected set. A distinct row is retained for every process start, making duplicate live starts
 * visible rather than allowing last-writer-wins self-attestation.</p>
 *
 * <p>Every row is whole-record fingerprinted, reads use a repeatable-read transaction and one
 * database time boundary, expired rows are excluded, and bounded retention prevents an abandoned
 * cohort from growing without limit. The repository performs no provider I/O.</p>
 */
public final class DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository
        implements TestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository {

    private static final int MAXIMUM_LIVE_ROWS = 512;
    private static final int PURGE_BATCH = 256;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority source;
    private final LocalPolicy policy;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one durable signed-cohort registry.
     *
     * @param jdbc isolated test-runtime JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param source verified dynamic publication authority; never a local expected-set supplier
     * @param policy local replica, artifact, protocol, and bounded lease policy
     * @param transactionManager manager for the same datasource
     */
    public DatabaseTestSuiteStabilityPhysicalAttemptProviderInventoryCohortRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            DynamicTestSuiteStabilityPhysicalAttemptProviderInventoryAuthority source,
            LocalPolicy policy,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.source = Objects.requireNonNull(source, "source");
        this.policy = Objects.requireNonNull(policy, "policy").validated();
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

    /** Creates the bounded, whole-record-fingerprinted membership registry and expiry index. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_test_physical_provider_inventory_cohort_members (
                    scope_id VARCHAR(255) NOT NULL,
                    cohort_id VARCHAR(255) NOT NULL,
                    replica_id VARCHAR(255) NOT NULL,
                    startup_id VARCHAR(36) NOT NULL,
                    expected_set_fingerprint VARCHAR(71) NOT NULL,
                    inventory_source_sequence BIGINT NOT NULL,
                    inventory_generation_fingerprint VARCHAR(71) NOT NULL,
                    inventory_available BOOLEAN NOT NULL,
                    artifact_fingerprint VARCHAR(71) NOT NULL,
                    protocol_version VARCHAR(255) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    lease_expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    purge_after TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (scope_id, cohort_id, replica_id, startup_id)
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS rg_test_physical_provider_inventory_cohort_expiry_idx
                ON rg_test_physical_provider_inventory_cohort_members (
                    purge_after, scope_id, cohort_id, replica_id, startup_id)
                """);
    }

    /** {@inheritDoc} */
    @Override
    public Observation heartbeat(String startupId) {
        String processStart = validStartupId(startupId);
        CohortBinding binding = source.cohortBinding();
        Observation result = mutations.execute(status -> {
            Instant now = databaseNow();
            Instant leaseExpiresAt = now.plus(policy.leaseDuration());
            Instant purgeAfter = leaseExpiresAt.plus(policy.recordRetention());
            String expectedSetFingerprint = expectedSetFingerprint(binding);
            Member member = new Member(Member.SCHEMA_VERSION, binding.scopeId(),
                    binding.cohortId(), policy.replicaId(), processStart,
                    expectedSetFingerprint, binding.sourceSequence(),
                    binding.sourceGenerationFingerprint(), binding.inventoryAvailable(),
                    policy.artifactFingerprint(), policy.protocolVersion());
            String recordFingerprint = recordFingerprint(
                    member, now, leaseExpiresAt, purgeAfter);
            jdbc.update("""
                    MERGE INTO rg_test_physical_provider_inventory_cohort_members (
                        scope_id, cohort_id, replica_id, startup_id,
                        expected_set_fingerprint, inventory_source_sequence,
                        inventory_generation_fingerprint, inventory_available,
                        artifact_fingerprint, protocol_version, observed_at,
                        lease_expires_at, purge_after, record_fingerprint
                    ) KEY (scope_id, cohort_id, replica_id, startup_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, member.scopeId(), member.cohortId(), member.replicaId(),
                    member.startupId(), member.expectedSetFingerprint(),
                    member.inventorySourceSequence(),
                    member.inventoryGenerationFingerprint(), member.inventoryAvailable(),
                    member.artifactFingerprint(), member.protocolVersion(),
                    Timestamp.from(now), Timestamp.from(leaseExpiresAt),
                    Timestamp.from(purgeAfter), recordFingerprint);
            purgeExpired(now);
            return observationAt(binding, now);
        });
        return Objects.requireNonNull(result, "physical provider cohort heartbeat result");
    }

    /** {@inheritDoc} */
    @Override
    public Observation observation() {
        CohortBinding binding = source.cohortBinding();
        Observation result = reads.execute(status -> observationAt(binding, databaseNow()));
        return Objects.requireNonNull(result, "physical provider cohort observation result");
    }

    /** {@inheritDoc} */
    @Override
    public Duration leaseDuration() {
        return policy.leaseDuration();
    }

    /** {@inheritDoc} */
    @Override
    public void withdraw(String startupId) {
        String processStart = validStartupId(startupId);
        mutations.executeWithoutResult(status -> jdbc.update("""
                DELETE FROM rg_test_physical_provider_inventory_cohort_members
                WHERE replica_id = ? AND startup_id = ?
                """, policy.replicaId(), processStart));
    }

    private Observation observationAt(CohortBinding binding, Instant now) {
        List<MemberRow> selected = jdbc.query("""
                SELECT scope_id, cohort_id, replica_id, startup_id,
                       expected_set_fingerprint, inventory_source_sequence,
                       inventory_generation_fingerprint, inventory_available,
                       artifact_fingerprint, protocol_version, observed_at,
                       lease_expires_at, purge_after, record_fingerprint
                FROM rg_test_physical_provider_inventory_cohort_members
                WHERE scope_id = ? AND cohort_id = ?
                ORDER BY replica_id, startup_id
                LIMIT ?
                """, this::row, binding.scopeId(), binding.cohortId(),
                MAXIMUM_LIVE_ROWS + 1);

        LinkedHashSet<String> blockers = new LinkedHashSet<>();
        if (!binding.inventoryAvailable()) {
            blockers.add("SOURCE_UNAVAILABLE");
        }
        if (selected.size() > MAXIMUM_LIVE_ROWS) {
            blockers.add("INVENTORY_OVERFLOW");
            selected = selected.subList(0, MAXIMUM_LIVE_ROWS);
        }

        List<MemberRow> live = new ArrayList<>();
        for (MemberRow row : selected) {
            if (!row.timeShapeValid() || !row.valid(
                    objectMapper, policy.leaseDuration(), policy.recordRetention())) {
                blockers.add("INVENTORY_CORRUPT");
                continue;
            }
            if (row.leaseExpiresAt().isAfter(now)) {
                live.add(row);
            }
        }

        Set<String> expected = Set.copyOf(binding.expectedReplicaIds());
        String expectedSetFingerprint = expectedSetFingerprint(binding);
        Map<String, List<MemberRow>> byReplica = new HashMap<>();
        live.forEach(row -> byReplica.computeIfAbsent(
                row.member().replicaId(), ignored -> new ArrayList<>()).add(row));
        int missing = (int) expected.stream().filter(
                replica -> !byReplica.containsKey(replica)).count();
        int unexpected = (int) live.stream().filter(
                row -> !expected.contains(row.member().replicaId())).count();
        int duplicates = (int) expected.stream().filter(
                replica -> byReplica.getOrDefault(replica, List.of()).size() > 1).count();
        int expectedSetDivergence = (int) live.stream().filter(row ->
                !expectedSetFingerprint.equals(row.member().expectedSetFingerprint())).count();
        int artifactDivergence = (int) live.stream().filter(row ->
                !policy.artifactFingerprint().equals(row.member().artifactFingerprint())).count();
        int protocolDivergence = (int) live.stream().filter(row ->
                !policy.protocolVersion().equals(row.member().protocolVersion())).count();
        int generationDivergence = (int) live.stream().filter(row ->
                row.member().inventorySourceSequence() != binding.sourceSequence()
                        || !row.member().inventoryGenerationFingerprint().equals(
                        binding.sourceGenerationFingerprint())).count();
        int unavailable = (int) live.stream().filter(
                row -> !row.member().inventoryAvailable()).count();
        Set<String> generations = new HashSet<>();
        live.forEach(row -> generations.add(row.member().inventorySourceSequence() + ":"
                + row.member().inventoryGenerationFingerprint()));

        if (missing > 0) {
            blockers.add("MEMBER_MISSING");
        }
        if (unexpected > 0) {
            blockers.add("UNEXPECTED_MEMBER");
        }
        if (duplicates > 0) {
            blockers.add("DUPLICATE_REPLICA");
        }
        if (expectedSetDivergence > 0) {
            blockers.add("EXPECTED_SET_DIVERGED");
        }
        if (artifactDivergence > 0) {
            blockers.add("ARTIFACT_DIVERGED");
        }
        if (protocolDivergence > 0) {
            blockers.add("PROTOCOL_DIVERGED");
        }
        if (generationDivergence > 0 || generations.size() > 1) {
            blockers.add("INVENTORY_GENERATION_DIVERGED");
        }
        if (unavailable > 0) {
            blockers.add("MEMBER_UNAVAILABLE");
        }

        int ready = 0;
        for (String replica : expected) {
            List<MemberRow> rows = byReplica.getOrDefault(replica, List.of());
            if (rows.size() == 1 && ready(rows.getFirst().member(), binding,
                    expectedSetFingerprint)) {
                ready++;
            }
        }
        boolean converged = blockers.isEmpty() && ready == expected.size()
                && live.size() == expected.size() && generations.size() == 1;
        String status = converged ? "CONVERGED" : blockers.isEmpty()
                ? "NOT_CONVERGED" : blockers.getFirst();
        return new Observation(Observation.SCHEMA_VERSION, converged, status,
                binding.sourceSequence(), binding.sourceGenerationFingerprint(),
                expected.size(), ready, Math.min(generations.size(), expected.size()), now);
    }

    private boolean ready(
            Member member,
            CohortBinding binding,
            String expectedSetFingerprint) {
        return member.inventoryAvailable()
                && expectedSetFingerprint.equals(member.expectedSetFingerprint())
                && member.inventorySourceSequence() == binding.sourceSequence()
                && member.inventoryGenerationFingerprint().equals(
                binding.sourceGenerationFingerprint())
                && policy.artifactFingerprint().equals(member.artifactFingerprint())
                && policy.protocolVersion().equals(member.protocolVersion());
    }

    private void purgeExpired(Instant now) {
        List<PurgeKey> keys = jdbc.query("""
                SELECT scope_id, cohort_id, replica_id, startup_id
                FROM rg_test_physical_provider_inventory_cohort_members
                WHERE purge_after < ?
                ORDER BY purge_after, cohort_id, replica_id, startup_id
                LIMIT ?
                """, (result, rowNumber) -> new PurgeKey(
                result.getString("scope_id"), result.getString("cohort_id"),
                result.getString("replica_id"), result.getString("startup_id")),
                Timestamp.from(now), PURGE_BATCH);
        keys.forEach(key -> jdbc.update("""
                DELETE FROM rg_test_physical_provider_inventory_cohort_members
                WHERE scope_id = ? AND cohort_id = ? AND replica_id = ? AND startup_id = ?
                  AND purge_after < ?
                """, key.scopeId(), key.cohortId(), key.replicaId(), key.startupId(),
                Timestamp.from(now)));
    }

    private String expectedSetFingerprint(CohortBinding binding) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryExpectedSet.v1",
                "scopeId", binding.scopeId(),
                "cohortId", binding.cohortId(),
                "expectedReplicaIds", binding.expectedReplicaIds()));
    }

    private String recordFingerprint(
            Member member,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter) {
        return ProtocolFingerprint.of(objectMapper, Map.of(
                "schemaVersion",
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryCohortRecord.v1",
                "member", member,
                "observedAt", observedAt.toString(),
                "leaseExpiresAt", leaseExpiresAt.toString(),
                "purgeAfter", purgeAfter.toString()));
    }

    private MemberRow row(ResultSet result, int rowNumber) throws SQLException {
        return new MemberRow(result.getString("scope_id"), result.getString("cohort_id"),
                result.getString("replica_id"), result.getString("startup_id"),
                result.getString("expected_set_fingerprint"),
                result.getLong("inventory_source_sequence"),
                result.getString("inventory_generation_fingerprint"),
                result.getBoolean("inventory_available"),
                result.getString("artifact_fingerprint"),
                result.getString("protocol_version"), instant(result, "observed_at"),
                instant(result, "lease_expires_at"), instant(result, "purge_after"),
                result.getString("record_fingerprint"));
    }

    private Instant databaseNow() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", (result, row) -> instant(result, 1)),
                "database time");
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Instant instant(ResultSet result, int column) throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static String validStartupId(String value) {
        String normalized = normalized(value);
        try {
            if (!UUID.fromString(normalized).toString().equals(normalized)) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Physical provider cohort startup identity is invalid", invalid);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    /**
     * Replica-local policy that deliberately excludes scope, cohort, and expected membership.
     *
     * @param replicaId this deployment slot's stable identity
     * @param artifactFingerprint immutable Resource Gateway artifact identity
     * @param protocolVersion Resource Gateway integration protocol identity
     * @param leaseDuration database-clock live-member lease
     * @param recordRetention retention after lease expiry before bounded purge
     */
    public record LocalPolicy(
            String replicaId,
            String artifactFingerprint,
            String protocolVersion,
            Duration leaseDuration,
            Duration recordRetention) {

        private static final Pattern IDENTIFIER =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
        private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

        /**
         * Validates identity and bounded lease relationships.
         *
         * @return normalized immutable policy
         */
        public LocalPolicy validated() {
            String replica = normalized(replicaId);
            String artifact = normalized(artifactFingerprint);
            String protocol = normalized(protocolVersion);
            Duration lease = Objects.requireNonNull(leaseDuration, "leaseDuration");
            Duration retention = Objects.requireNonNull(recordRetention, "recordRetention");
            if (!IDENTIFIER.matcher(replica).matches()
                    || !FINGERPRINT.matcher(artifact).matches()
                    || !IDENTIFIER.matcher(protocol).matches()
                    || lease.compareTo(Duration.ofSeconds(2)) < 0
                    || lease.compareTo(Duration.ofMinutes(10)) > 0
                    || retention.compareTo(lease) < 0
                    || retention.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalArgumentException(
                        "Physical provider cohort local policy is invalid");
            }
            return new LocalPolicy(replica, artifact, protocol, lease, retention);
        }
    }

    private record Member(
            String schemaVersion,
            String scopeId,
            String cohortId,
            String replicaId,
            String startupId,
            String expectedSetFingerprint,
            long inventorySourceSequence,
            String inventoryGenerationFingerprint,
            boolean inventoryAvailable,
            String artifactFingerprint,
            String protocolVersion) {

        private static final String SCHEMA_VERSION =
                "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryCohortMember.v1";
    }

    private record MemberRow(
            String scopeId,
            String cohortId,
            String replicaId,
            String startupId,
            String expectedSetFingerprint,
            long inventorySourceSequence,
            String inventoryGenerationFingerprint,
            boolean inventoryAvailable,
            String artifactFingerprint,
            String protocolVersion,
            Instant observedAt,
            Instant leaseExpiresAt,
            Instant purgeAfter,
            String recordFingerprint) {

        private Member member() {
            return new Member(Member.SCHEMA_VERSION, scopeId, cohortId, replicaId, startupId,
                    expectedSetFingerprint, inventorySourceSequence,
                    inventoryGenerationFingerprint, inventoryAvailable,
                    artifactFingerprint, protocolVersion);
        }

        private boolean timeShapeValid() {
            return observedAt != null && leaseExpiresAt != null && purgeAfter != null
                    && leaseExpiresAt.isAfter(observedAt)
                    && !purgeAfter.isBefore(leaseExpiresAt);
        }

        private boolean valid(
                ObjectMapper objectMapper,
                Duration leaseDuration,
                Duration recordRetention) {
            try {
                if (!leaseExpiresAt.equals(observedAt.plus(leaseDuration))
                        || !purgeAfter.equals(leaseExpiresAt.plus(recordRetention))) {
                    return false;
                }
                String expected = ProtocolFingerprint.of(objectMapper, Map.of(
                        "schemaVersion",
                        "bloge.testSuiteStabilityPhysicalAttemptProviderInventoryCohortRecord.v1",
                        "member", member(),
                        "observedAt", observedAt.toString(),
                        "leaseExpiresAt", leaseExpiresAt.toString(),
                        "purgeAfter", purgeAfter.toString()));
                return expected.equals(recordFingerprint);
            } catch (RuntimeException invalid) {
                return false;
            }
        }
    }

    private record PurgeKey(
            String scopeId, String cohortId, String replicaId, String startupId) {
    }
}
