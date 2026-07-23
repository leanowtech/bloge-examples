package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * H2-backed append-only repository for owner-reviewed trajectory publications.
 *
 * <p>The primary key contains complete enterprise scope and trajectory revision. Every read
 * recomputes the artifact fingerprint and compares all duplicated lineage, capability, corpus,
 * policy, retry, request, reviewer, and horizon indexes with the canonical JSON row.</p>
 */
public class DatabaseCapabilityCorpusTrajectoryRepository
        implements CapabilityCorpusTrajectoryRepository {
    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS mirror_capability_corpus_trajectories (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                trajectory_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                trajectory_fingerprint VARCHAR(71) NOT NULL,
                command_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71),
                capability_id VARCHAR(512) NOT NULL,
                capability_revision BIGINT NOT NULL,
                capability_fingerprint VARCHAR(71) NOT NULL,
                corpus_id VARCHAR(512) NOT NULL,
                corpus_publication_revision BIGINT NOT NULL,
                corpus_publication_fingerprint VARCHAR(71) NOT NULL,
                corpus_revision BIGINT NOT NULL,
                corpus_revision_fingerprint VARCHAR(71) NOT NULL,
                policy_fingerprint VARCHAR(71) NOT NULL,
                retry_policy_fingerprint VARCHAR(71) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                attempt_count INTEGER NOT NULL,
                reviewed_by VARCHAR(512) NOT NULL,
                published_at VARCHAR(64) NOT NULL,
                usable_until VARCHAR(64) NOT NULL,
                trajectory_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, trajectory_id, revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_capability_corpus_trajectories (
                tenant_id, organization_id, project_id, environment_id, region,
                trajectory_id, revision, trajectory_fingerprint,
                command_fingerprint, predecessor_fingerprint,
                capability_id, capability_revision, capability_fingerprint,
                corpus_id, corpus_publication_revision,
                corpus_publication_fingerprint, corpus_revision,
                corpus_revision_fingerprint, policy_fingerprint,
                retry_policy_fingerprint, request_fingerprint, attempt_count,
                reviewed_by, published_at, usable_until, trajectory_json
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?
            )
            """;
    private static final String COLUMNS = """
            trajectory_fingerprint, command_fingerprint,
            predecessor_fingerprint, capability_id, capability_revision,
            capability_fingerprint, corpus_id, corpus_publication_revision,
            corpus_publication_fingerprint, corpus_revision,
            corpus_revision_fingerprint, policy_fingerprint,
            retry_policy_fingerprint, request_fingerprint, attempt_count,
            reviewed_by, published_at, usable_until, trajectory_json
            """;
    private static final String SELECT = """
            SELECT %s
            FROM mirror_capability_corpus_trajectories
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND trajectory_id = ?
              AND revision = ?
            """.formatted(COLUMNS);
    private static final String SELECT_LATEST = """
            SELECT %s
            FROM mirror_capability_corpus_trajectories
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND trajectory_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """.formatted(COLUMNS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityCorpusIntegrity integrity;

    /**
     * Creates the durable trajectory repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity corpus content-addressing boundary
     */
    public DatabaseCapabilityCorpusTrajectoryRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CapabilityCorpusIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the append-only full-scope trajectory table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE);
    }

    @Override
    @Transactional
    public CapabilityCorpusTrajectoryPublication append(
            CapabilityCorpusTrajectoryPublication publication) {
        CapabilityCorpusTrajectoryPublication exact = verify(publication);
        Optional<CapabilityCorpusTrajectoryPublication> existing = find(
                exact.scope(), exact.trajectoryId(), exact.revision());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), exact);
        }
        requireLineage(exact);
        CapabilitySnapshot.Scope scope = exact.scope();
        MirrorArtifactRef capability = exact.capabilityRef();
        MirrorArtifactRef corpusPublication = exact.corpusPublicationRef();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.trajectoryId(),
                    exact.revision(),
                    exact.trajectoryFingerprint(),
                    exact.sourceCommandFingerprint(),
                    predecessorFingerprint(exact.predecessorRef()),
                    capability.id(),
                    capability.revision(),
                    capability.fingerprint(),
                    corpusPublication.id(),
                    corpusPublication.revision(),
                    corpusPublication.fingerprint(),
                    exact.corpusRevisionRef().revision(),
                    exact.corpusRevisionRef().fingerprint(),
                    exact.publicationPolicyRef().fingerprint(),
                    exact.retryPolicyRef().fingerprint(),
                    exact.requestFingerprint(),
                    exact.attempts().size(),
                    exact.reviewedBy(),
                    exact.publishedAt().toString(),
                    exact.usableUntil().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            CapabilityCorpusTrajectoryPublication stored = find(
                    scope, exact.trajectoryId(), exact.revision())
                    .orElseThrow(() -> concurrent);
            return sameOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<CapabilityCorpusTrajectoryPublication> find(
            CapabilitySnapshot.Scope scope,
            String trajectoryId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return query(
                SELECT,
                Objects.requireNonNull(scope, "scope"),
                identifier(trajectoryId),
                revision);
    }

    @Override
    public Optional<CapabilityCorpusTrajectoryPublication> findLatest(
            CapabilitySnapshot.Scope scope,
            String trajectoryId) {
        return query(
                SELECT_LATEST,
                Objects.requireNonNull(scope, "scope"),
                identifier(trajectoryId));
    }

    private void requireLineage(
            CapabilityCorpusTrajectoryPublication candidate) {
        Optional<CapabilityCorpusTrajectoryPublication> latest = findLatest(
                candidate.scope(), candidate.trajectoryId());
        if (latest.isEmpty()) {
            if (candidate.revision() != 1
                    || candidate.predecessorRef() != null) {
                throw new Violation(Reason.LINEAGE_CONFLICT);
            }
            return;
        }
        CapabilityCorpusTrajectoryPublication current = latest.get();
        if (candidate.revision() != current.revision() + 1
                || !current.artifactRef().equals(candidate.predecessorRef())
                || !current.capabilityRef().equals(candidate.capabilityRef())) {
            throw new Violation(Reason.LINEAGE_CONFLICT);
        }
    }

    private Optional<CapabilityCorpusTrajectoryPublication> query(
            String sql,
            CapabilitySnapshot.Scope scope,
            String trajectoryId,
            Object... trailing) {
        Object[] arguments = arguments(scope, trajectoryId, trailing);
        List<CapabilityCorpusTrajectoryPublication> found = jdbc.query(
                sql,
                (result, rowNumber) -> deserialize(
                        scope, trajectoryId, result),
                arguments);
        return found.stream().findFirst();
    }

    private CapabilityCorpusTrajectoryPublication deserialize(
            CapabilitySnapshot.Scope expectedScope,
            String expectedTrajectoryId,
            ResultSet result) throws SQLException {
        try {
            CapabilityCorpusTrajectoryPublication publication = verify(
                    mapper.readValue(
                            result.getString("trajectory_json"),
                            CapabilityCorpusTrajectoryPublication.class));
            MirrorArtifactRef capability = publication.capabilityRef();
            MirrorArtifactRef corpusPublication =
                    publication.corpusPublicationRef();
            if (!expectedScope.equals(publication.scope())
                    || !expectedTrajectoryId.equals(publication.trajectoryId())
                    || !result.getString("trajectory_fingerprint").equals(
                    publication.trajectoryFingerprint())
                    || !result.getString("command_fingerprint").equals(
                    publication.sourceCommandFingerprint())
                    || !Objects.equals(
                    result.getString("predecessor_fingerprint"),
                    predecessorFingerprint(publication.predecessorRef()))
                    || !result.getString("capability_id").equals(capability.id())
                    || result.getLong("capability_revision")
                    != capability.revision()
                    || !result.getString("capability_fingerprint").equals(
                    capability.fingerprint())
                    || !result.getString("corpus_id").equals(
                    corpusPublication.id())
                    || result.getLong("corpus_publication_revision")
                    != corpusPublication.revision()
                    || !result.getString("corpus_publication_fingerprint")
                    .equals(corpusPublication.fingerprint())
                    || result.getLong("corpus_revision")
                    != publication.corpusRevisionRef().revision()
                    || !result.getString("corpus_revision_fingerprint")
                    .equals(publication.corpusRevisionRef().fingerprint())
                    || !result.getString("policy_fingerprint").equals(
                    publication.publicationPolicyRef().fingerprint())
                    || !result.getString("retry_policy_fingerprint").equals(
                    publication.retryPolicyRef().fingerprint())
                    || !result.getString("request_fingerprint").equals(
                    publication.requestFingerprint())
                    || result.getInt("attempt_count")
                    != publication.attempts().size()
                    || !result.getString("reviewed_by").equals(
                    publication.reviewedBy())
                    || !result.getString("published_at").equals(
                    publication.publishedAt().toString())
                    || !result.getString("usable_until").equals(
                    publication.usableUntil().toString())) {
                throw new Violation(Reason.STORED_STATE_CORRUPT);
            }
            return publication;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new Violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private CapabilityCorpusTrajectoryPublication verify(
            CapabilityCorpusTrajectoryPublication publication) {
        try {
            CapabilityCorpusTrajectoryPublication exact =
                    Objects.requireNonNull(publication, "publication");
            if (!integrity.trajectoryVerified(exact)) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private static CapabilityCorpusTrajectoryPublication sameOrConflict(
            CapabilityCorpusTrajectoryPublication stored,
            CapabilityCorpusTrajectoryPublication candidate) {
        if (stored.sourceCommandFingerprint().equals(
                candidate.sourceCommandFingerprint())
                && stored.trajectoryFingerprint().equals(
                candidate.trajectoryFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static Object[] arguments(
            CapabilitySnapshot.Scope scope,
            String trajectoryId,
            Object[] trailing) {
        Object[] values = new Object[6 + trailing.length];
        values[0] = scope.tenantId();
        values[1] = scope.organizationId();
        values[2] = scope.projectId();
        values[3] = scope.environmentId();
        values[4] = scope.region();
        values[5] = trajectoryId;
        System.arraycopy(trailing, 0, values, 6, trailing.length);
        return values;
    }

    private static String predecessorFingerprint(
            MirrorArtifactRef predecessor) {
        return predecessor == null ? null : predecessor.fingerprint();
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException("trajectoryId is invalid");
        }
        return exact;
    }
}
