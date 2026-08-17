package com.leanowtech.bloge.gateway.capabilitystudio;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Durable SQL boundary for the Capability Studio tutorial branch.
 *
 * <p>The head is the only mutable row. Revisions are append-only and contain only the
 * business-shaped behavior fields; payload, fixture, mock, and secret material has no column in
 * either table.</p>
 */
final class CapabilityStudioTutorialBranchRepository {
    private static final String CREATE_REVISIONS = """
            CREATE TABLE IF NOT EXISTS capability_studio_tutorial_branch_revisions (
                branch_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL CHECK (revision >= 1),
                fingerprint VARCHAR(71) NOT NULL,
                canonical_baseline_fingerprint VARCHAR(71) NOT NULL,
                dependency_id VARCHAR(255) NOT NULL,
                condition_text VARCHAR(200) NOT NULL,
                behavior VARCHAR(32) NOT NULL,
                duration_ms BIGINT NOT NULL CHECK (duration_ms >= 100 AND duration_ms <= 30000),
                PRIMARY KEY (branch_id, revision),
                UNIQUE (branch_id, revision, fingerprint)
            )
            """;
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS capability_studio_tutorial_branch_heads (
                branch_id VARCHAR(255) NOT NULL PRIMARY KEY,
                revision BIGINT NOT NULL CHECK (revision >= 1),
                fingerprint VARCHAR(71) NOT NULL,
                canonical_baseline_fingerprint VARCHAR(71) NOT NULL,
                dependency_id VARCHAR(255) NOT NULL,
                condition_text VARCHAR(200) NOT NULL,
                behavior VARCHAR(32) NOT NULL,
                duration_ms BIGINT NOT NULL CHECK (duration_ms >= 100 AND duration_ms <= 30000)
            )
            """;
    private static final String SELECT_HEAD = """
            SELECT branch_id, revision, fingerprint, canonical_baseline_fingerprint,
                   dependency_id, condition_text, behavior, duration_ms
            FROM capability_studio_tutorial_branch_heads
            WHERE branch_id = ?
            """;
    private static final String SELECT_REVISION = """
            SELECT branch_id, revision, fingerprint, canonical_baseline_fingerprint,
                   dependency_id, condition_text, behavior, duration_ms
            FROM capability_studio_tutorial_branch_revisions
            WHERE branch_id = ? AND revision = ?
            """;
    private static final String INSERT_REVISION = """
            INSERT INTO capability_studio_tutorial_branch_revisions (
                branch_id, revision, fingerprint, canonical_baseline_fingerprint,
                dependency_id, condition_text, behavior, duration_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_HEAD = """
            INSERT INTO capability_studio_tutorial_branch_heads (
                branch_id, revision, fingerprint, canonical_baseline_fingerprint,
                dependency_id, condition_text, behavior, duration_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_HEAD_IF_REVISION = """
            UPDATE capability_studio_tutorial_branch_heads
            SET revision = ?, fingerprint = ?, canonical_baseline_fingerprint = ?,
                dependency_id = ?, condition_text = ?, behavior = ?, duration_ms = ?
            WHERE branch_id = ? AND revision = ?
              AND canonical_baseline_fingerprint = ?
            """;

    private final JdbcTemplate jdbc;

    CapabilityStudioTutorialBranchRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Creates the local example tables when the demo profile is enabled. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_REVISIONS);
        jdbc.execute(CREATE_HEADS);
    }

    Optional<StoredBranch> findHead(String branchId) {
        List<StoredBranch> heads = jdbc.query(SELECT_HEAD, this::map, branchId);
        if (heads.isEmpty()) {
            return Optional.empty();
        }
        StoredBranch head = heads.getFirst();
        StoredBranch revision = findRevision(branchId, head.revision())
                .orElseThrow(() -> new IllegalStateException(
                        "Capability Studio head has no immutable revision: " + branchId
                                + "@" + head.revision()));
        if (!head.equals(revision)) {
            throw new IllegalStateException(
                    "Capability Studio head does not match its immutable revision: "
                            + branchId + "@" + head.revision());
        }
        return Optional.of(head);
    }

    Optional<StoredBranch> findRevision(String branchId, long revision) {
        return jdbc.query(SELECT_REVISION, this::map, branchId, revision)
                .stream()
                .findFirst();
    }

    void insertRevision(StoredBranch value) {
        jdbc.update(INSERT_REVISION, args(value));
    }

    void insertHead(StoredBranch value) {
        jdbc.update(INSERT_HEAD, args(value));
    }

    int compareAndSetHead(StoredBranch expected, StoredBranch replacement) {
        return jdbc.update(
                UPDATE_HEAD_IF_REVISION,
                replacement.revision(), replacement.fingerprint(),
                replacement.canonicalBaselineFingerprint(), replacement.dependencyId(),
                replacement.condition(), replacement.behavior(), replacement.durationMs(),
                expected.branchId(), expected.revision(), expected.canonicalBaselineFingerprint());
    }

    private Object[] args(StoredBranch value) {
        return new Object[]{
                value.branchId(), value.revision(), value.fingerprint(),
                value.canonicalBaselineFingerprint(), value.dependencyId(), value.condition(),
                value.behavior(), value.durationMs()};
    }

    private StoredBranch map(java.sql.ResultSet rs, int rowNumber)
            throws java.sql.SQLException {
        return new StoredBranch(
                rs.getString("branch_id"),
                rs.getLong("revision"),
                rs.getString("fingerprint"),
                rs.getString("canonical_baseline_fingerprint"),
                rs.getString("dependency_id"),
                rs.getString("condition_text"),
                rs.getString("behavior"),
                rs.getLong("duration_ms"));
    }

    record StoredBranch(
            String branchId,
            long revision,
            String fingerprint,
            String canonicalBaselineFingerprint,
            String dependencyId,
            String condition,
            String behavior,
            long durationMs) {
    }
}
