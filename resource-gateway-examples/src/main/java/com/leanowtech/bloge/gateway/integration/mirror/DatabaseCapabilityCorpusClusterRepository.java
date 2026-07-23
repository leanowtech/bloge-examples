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
 * H2-backed append-only repository for owner-reviewed cluster publications.
 *
 * <p>The primary key contains complete enterprise scope and cluster revision. Every read
 * recomputes the canonical artifact fingerprint and compares duplicated lineage, corpus, policy,
 * validation, support, holdout, confidence, reviewer, and horizon indexes with the canonical JSON
 * row. This makes partial database mutation fail closed instead of silently changing serving
 * behavior.</p>
 */
public class DatabaseCapabilityCorpusClusterRepository
        implements CapabilityCorpusClusterRepository {
    private static final String CREATE = """
            CREATE TABLE IF NOT EXISTS mirror_capability_corpus_clusters (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                cluster_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                cluster_fingerprint VARCHAR(71) NOT NULL,
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
                publication_policy_fingerprint VARCHAR(71) NOT NULL,
                cluster_policy_fingerprint VARCHAR(71) NOT NULL,
                validation_id VARCHAR(512) NOT NULL,
                validation_revision BIGINT NOT NULL,
                validation_fingerprint VARCHAR(71) NOT NULL,
                member_count INTEGER NOT NULL,
                distinct_identity_count INTEGER NOT NULL,
                holdout_accepted_count INTEGER NOT NULL,
                holdout_false_positive_count INTEGER NOT NULL,
                confidence_lower_bound DOUBLE PRECISION NOT NULL,
                reviewed_by VARCHAR(512) NOT NULL,
                published_at VARCHAR(64) NOT NULL,
                usable_until VARCHAR(64) NOT NULL,
                cluster_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, cluster_id, revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_capability_corpus_clusters (
                tenant_id, organization_id, project_id, environment_id, region,
                cluster_id, revision, cluster_fingerprint, command_fingerprint,
                predecessor_fingerprint, capability_id, capability_revision,
                capability_fingerprint, corpus_id, corpus_publication_revision,
                corpus_publication_fingerprint, corpus_revision,
                corpus_revision_fingerprint, publication_policy_fingerprint,
                cluster_policy_fingerprint, validation_id, validation_revision,
                validation_fingerprint, member_count, distinct_identity_count,
                holdout_accepted_count, holdout_false_positive_count,
                confidence_lower_bound, reviewed_by, published_at, usable_until,
                cluster_json
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """;
    private static final String COLUMNS = """
            cluster_fingerprint, command_fingerprint, predecessor_fingerprint,
            capability_id, capability_revision, capability_fingerprint,
            corpus_id, corpus_publication_revision,
            corpus_publication_fingerprint, corpus_revision,
            corpus_revision_fingerprint, publication_policy_fingerprint,
            cluster_policy_fingerprint, validation_id, validation_revision,
            validation_fingerprint, member_count, distinct_identity_count,
            holdout_accepted_count, holdout_false_positive_count,
            confidence_lower_bound, reviewed_by, published_at, usable_until,
            cluster_json
            """;
    private static final String SELECT = """
            SELECT %s
            FROM mirror_capability_corpus_clusters
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND cluster_id = ?
              AND revision = ?
            """.formatted(COLUMNS);
    private static final String SELECT_LATEST = """
            SELECT %s
            FROM mirror_capability_corpus_clusters
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND cluster_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """.formatted(COLUMNS);

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityCorpusIntegrity integrity;

    /**
     * Creates the durable cluster repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity cluster content-addressing boundary
     */
    public DatabaseCapabilityCorpusClusterRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CapabilityCorpusIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the append-only full-scope cluster table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE);
    }

    @Override
    @Transactional
    public CapabilityCorpusClusterPublication append(
            CapabilityCorpusClusterPublication publication) {
        CapabilityCorpusClusterPublication exact = verify(publication);
        Optional<CapabilityCorpusClusterPublication> existing = find(
                exact.scope(), exact.clusterId(), exact.revision());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), exact);
        }
        requireLineage(exact);
        CapabilitySnapshot.Scope scope = exact.scope();
        MirrorArtifactRef capability = exact.capabilityRef();
        MirrorArtifactRef corpusPublication = exact.corpusPublicationRef();
        MirrorArtifactRef validation = exact.validationRef();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.clusterId(),
                    exact.revision(),
                    exact.clusterFingerprint(),
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
                    exact.clusterPolicyRef().fingerprint(),
                    validation.id(),
                    validation.revision(),
                    validation.fingerprint(),
                    exact.members().size(),
                    exact.distinctIdentityCount(),
                    exact.holdout().acceptedCount(),
                    exact.holdout().falsePositiveCount(),
                    exact.confidence().lowerBound(),
                    exact.reviewedBy(),
                    exact.publishedAt().toString(),
                    exact.usableUntil().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            CapabilityCorpusClusterPublication stored = find(
                    scope, exact.clusterId(), exact.revision())
                    .orElseThrow(() -> concurrent);
            return sameOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<CapabilityCorpusClusterPublication> find(
            CapabilitySnapshot.Scope scope,
            String clusterId,
            long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return query(
                SELECT,
                Objects.requireNonNull(scope, "scope"),
                identifier(clusterId),
                revision);
    }

    @Override
    public Optional<CapabilityCorpusClusterPublication> findLatest(
            CapabilitySnapshot.Scope scope,
            String clusterId) {
        return query(
                SELECT_LATEST,
                Objects.requireNonNull(scope, "scope"),
                identifier(clusterId));
    }

    private void requireLineage(CapabilityCorpusClusterPublication candidate) {
        Optional<CapabilityCorpusClusterPublication> latest = findLatest(
                candidate.scope(), candidate.clusterId());
        if (latest.isEmpty()) {
            if (candidate.revision() != 1
                    || candidate.predecessorRef() != null) {
                throw new Violation(Reason.LINEAGE_CONFLICT);
            }
            return;
        }
        CapabilityCorpusClusterPublication current = latest.get();
        if (candidate.revision() != current.revision() + 1
                || !current.artifactRef().equals(candidate.predecessorRef())
                || !current.capabilityRef().equals(candidate.capabilityRef())) {
            throw new Violation(Reason.LINEAGE_CONFLICT);
        }
    }

    private Optional<CapabilityCorpusClusterPublication> query(
            String sql,
            CapabilitySnapshot.Scope scope,
            String clusterId,
            Object... trailing) {
        Object[] arguments = arguments(scope, clusterId, trailing);
        List<CapabilityCorpusClusterPublication> found = jdbc.query(
                sql,
                (result, rowNumber) -> deserialize(scope, clusterId, result),
                arguments);
        return found.stream().findFirst();
    }

    private CapabilityCorpusClusterPublication deserialize(
            CapabilitySnapshot.Scope expectedScope,
            String expectedClusterId,
            ResultSet result) throws SQLException {
        try {
            CapabilityCorpusClusterPublication publication = verify(
                    mapper.readValue(
                            result.getString("cluster_json"),
                            CapabilityCorpusClusterPublication.class));
            MirrorArtifactRef capability = publication.capabilityRef();
            MirrorArtifactRef corpusPublication =
                    publication.corpusPublicationRef();
            MirrorArtifactRef validation = publication.validationRef();
            if (!expectedScope.equals(publication.scope())
                    || !expectedClusterId.equals(publication.clusterId())
                    || !result.getString("cluster_fingerprint").equals(
                    publication.clusterFingerprint())
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
                    || !result.getString("publication_policy_fingerprint")
                    .equals(publication.publicationPolicyRef().fingerprint())
                    || !result.getString("cluster_policy_fingerprint")
                    .equals(publication.clusterPolicyRef().fingerprint())
                    || !result.getString("validation_id").equals(
                    validation.id())
                    || result.getLong("validation_revision")
                    != validation.revision()
                    || !result.getString("validation_fingerprint").equals(
                    validation.fingerprint())
                    || result.getInt("member_count")
                    != publication.members().size()
                    || result.getInt("distinct_identity_count")
                    != publication.distinctIdentityCount()
                    || result.getInt("holdout_accepted_count")
                    != publication.holdout().acceptedCount()
                    || result.getInt("holdout_false_positive_count")
                    != publication.holdout().falsePositiveCount()
                    || Double.compare(
                    result.getDouble("confidence_lower_bound"),
                    publication.confidence().lowerBound()) != 0
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

    private CapabilityCorpusClusterPublication verify(
            CapabilityCorpusClusterPublication publication) {
        try {
            CapabilityCorpusClusterPublication exact =
                    Objects.requireNonNull(publication, "publication");
            if (!integrity.clusterVerified(exact)) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private static CapabilityCorpusClusterPublication sameOrConflict(
            CapabilityCorpusClusterPublication stored,
            CapabilityCorpusClusterPublication candidate) {
        if (stored.sourceCommandFingerprint().equals(
                candidate.sourceCommandFingerprint())
                && stored.clusterFingerprint().equals(
                candidate.clusterFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static Object[] arguments(
            CapabilitySnapshot.Scope scope,
            String clusterId,
            Object[] trailing) {
        Object[] values = new Object[6 + trailing.length];
        values[0] = scope.tenantId();
        values[1] = scope.organizationId();
        values[2] = scope.projectId();
        values[3] = scope.environmentId();
        values[4] = scope.region();
        values[5] = clusterId;
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
            throw new IllegalArgumentException("clusterId is invalid");
        }
        return exact;
    }
}
