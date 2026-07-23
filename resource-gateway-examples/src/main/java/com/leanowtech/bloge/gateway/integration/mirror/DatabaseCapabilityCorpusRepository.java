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
 * H2-backed append-only corpus revision and publication repository.
 *
 * <p>Both monotonic lineages use complete enterprise scope in their primary key. Every read
 * recomputes canonical fingerprints and compares duplicated identity, lineage, policy, risk, and
 * time indexes with JSON. Latest revision and latest publication are deliberately separate so
 * candidate creation cannot silently change serving behavior.</p>
 */
public class DatabaseCapabilityCorpusRepository
        implements CapabilityCorpusRepository {
    private static final String CREATE_REVISIONS = """
            CREATE TABLE IF NOT EXISTS mirror_capability_corpus_revisions (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                corpus_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                revision_fingerprint VARCHAR(71) NOT NULL,
                command_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71),
                capability_id VARCHAR(512) NOT NULL,
                capability_revision BIGINT NOT NULL,
                capability_fingerprint VARCHAR(71) NOT NULL,
                policy_fingerprint VARCHAR(71) NOT NULL,
                sample_count INTEGER NOT NULL,
                eligibility VARCHAR(32) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                usable_until VARCHAR(64) NOT NULL,
                revision_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, corpus_id, revision
                )
            )
            """;
    private static final String CREATE_PUBLICATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_capability_corpus_publications (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                corpus_id VARCHAR(512) NOT NULL,
                revision BIGINT NOT NULL,
                publication_fingerprint VARCHAR(71) NOT NULL,
                command_fingerprint VARCHAR(71) NOT NULL,
                predecessor_fingerprint VARCHAR(71),
                corpus_revision BIGINT NOT NULL,
                corpus_revision_fingerprint VARCHAR(71) NOT NULL,
                policy_fingerprint VARCHAR(71) NOT NULL,
                reviewed_by VARCHAR(255) NOT NULL,
                published_at VARCHAR(64) NOT NULL,
                usable_until VARCHAR(64) NOT NULL,
                publication_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, corpus_id, revision
                )
            )
            """;
    private static final String INSERT_REVISION = """
            INSERT INTO mirror_capability_corpus_revisions (
                tenant_id, organization_id, project_id, environment_id, region,
                corpus_id, revision, revision_fingerprint, command_fingerprint,
                predecessor_fingerprint, capability_id, capability_revision,
                capability_fingerprint, policy_fingerprint, sample_count,
                eligibility, created_at, usable_until, revision_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_PUBLICATION = """
            INSERT INTO mirror_capability_corpus_publications (
                tenant_id, organization_id, project_id, environment_id, region,
                corpus_id, revision, publication_fingerprint, command_fingerprint,
                predecessor_fingerprint, corpus_revision,
                corpus_revision_fingerprint, policy_fingerprint, reviewed_by,
                published_at, usable_until, publication_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_REVISION = """
            SELECT revision_fingerprint, command_fingerprint,
                   predecessor_fingerprint, capability_id, capability_revision,
                   capability_fingerprint, policy_fingerprint, sample_count,
                   eligibility, created_at, usable_until, revision_json
            FROM mirror_capability_corpus_revisions
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND corpus_id = ?
              AND revision = ?
            """;
    private static final String SELECT_LATEST_REVISION = """
            SELECT revision_fingerprint, command_fingerprint,
                   predecessor_fingerprint, capability_id, capability_revision,
                   capability_fingerprint, policy_fingerprint, sample_count,
                   eligibility, created_at, usable_until, revision_json
            FROM mirror_capability_corpus_revisions
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND corpus_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """;
    private static final String SELECT_PUBLICATION = """
            SELECT publication_fingerprint, command_fingerprint,
                   predecessor_fingerprint, corpus_revision,
                   corpus_revision_fingerprint, policy_fingerprint,
                   reviewed_by, published_at, usable_until, publication_json
            FROM mirror_capability_corpus_publications
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND corpus_id = ?
              AND revision = ?
            """;
    private static final String SELECT_LATEST_PUBLICATION = """
            SELECT publication_fingerprint, command_fingerprint,
                   predecessor_fingerprint, corpus_revision,
                   corpus_revision_fingerprint, policy_fingerprint,
                   reviewed_by, published_at, usable_until, publication_json
            FROM mirror_capability_corpus_publications
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND corpus_id = ?
            ORDER BY revision DESC
            FETCH FIRST 1 ROW ONLY
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CapabilityCorpusIntegrity integrity;

    /**
     * Creates the durable corpus repository.
     *
     * @param jdbc transaction-aware application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity corpus content-addressing boundary
     */
    public DatabaseCapabilityCorpusRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            CapabilityCorpusIntegrity integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the independent revision and publication tables when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_REVISIONS);
        jdbc.execute(CREATE_PUBLICATIONS);
    }

    @Override
    @Transactional
    public CapabilityCorpusRevision appendRevision(
            CapabilityCorpusRevision revision) {
        CapabilityCorpusRevision exact = verifyRevision(revision);
        Optional<CapabilityCorpusRevision> existing = findRevision(
                exact.scope(), exact.corpusId(), exact.revision());
        if (existing.isPresent()) {
            return sameRevisionOrConflict(existing.get(), exact);
        }
        requireRevisionLineage(exact);
        CapabilitySnapshot.Scope scope = exact.scope();
        MirrorArtifactRef capability = exact.capabilityRef();
        try {
            jdbc.update(
                    INSERT_REVISION,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.corpusId(),
                    exact.revision(),
                    exact.revisionFingerprint(),
                    exact.sourceCommandFingerprint(),
                    predecessorFingerprint(exact.predecessorRef()),
                    capability.id(),
                    capability.revision(),
                    capability.fingerprint(),
                    exact.governancePolicyRef().fingerprint(),
                    exact.riskSummary().sampleCount(),
                    exact.riskSummary().eligibility().name(),
                    exact.createdAt().toString(),
                    exact.usableUntil().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            CapabilityCorpusRevision stored = findRevision(
                    scope, exact.corpusId(), exact.revision())
                    .orElseThrow(() -> concurrent);
            return sameRevisionOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<CapabilityCorpusRevision> findRevision(
            CapabilitySnapshot.Scope scope, String corpusId, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return queryRevision(
                SELECT_REVISION,
                Objects.requireNonNull(scope, "scope"),
                identifier(corpusId),
                revision);
    }

    @Override
    public Optional<CapabilityCorpusRevision> findLatestRevision(
            CapabilitySnapshot.Scope scope, String corpusId) {
        return queryRevision(
                SELECT_LATEST_REVISION,
                Objects.requireNonNull(scope, "scope"),
                identifier(corpusId));
    }

    @Override
    @Transactional
    public CapabilityCorpusPublication appendPublication(
            CapabilityCorpusPublication publication) {
        CapabilityCorpusPublication exact = verifyPublication(publication);
        Optional<CapabilityCorpusPublication> existing = findPublication(
                exact.scope(), exact.corpusId(), exact.revision());
        if (existing.isPresent()) {
            return samePublicationOrConflict(existing.get(), exact);
        }
        requirePublicationLineage(exact);
        CapabilitySnapshot.Scope scope = exact.scope();
        try {
            jdbc.update(
                    INSERT_PUBLICATION,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    exact.corpusId(),
                    exact.revision(),
                    exact.publicationFingerprint(),
                    exact.sourceCommandFingerprint(),
                    predecessorFingerprint(exact.predecessorRef()),
                    exact.corpusRevisionRef().revision(),
                    exact.corpusRevisionRef().fingerprint(),
                    exact.publicationPolicyRef().fingerprint(),
                    exact.reviewedBy(),
                    exact.publishedAt().toString(),
                    exact.usableUntil().toString(),
                    mapper.writeValueAsString(exact));
            return exact;
        } catch (DuplicateKeyException concurrent) {
            CapabilityCorpusPublication stored = findPublication(
                    scope, exact.corpusId(), exact.revision())
                    .orElseThrow(() -> concurrent);
            return samePublicationOrConflict(stored, exact);
        } catch (JsonProcessingException invalid) {
            throw new Violation(Reason.CANONICAL_INVALID);
        }
    }

    @Override
    public Optional<CapabilityCorpusPublication> findPublication(
            CapabilitySnapshot.Scope scope, String corpusId, long revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive");
        }
        return queryPublication(
                SELECT_PUBLICATION,
                Objects.requireNonNull(scope, "scope"),
                identifier(corpusId),
                revision);
    }

    @Override
    public Optional<CapabilityCorpusPublication> findLatestPublication(
            CapabilitySnapshot.Scope scope, String corpusId) {
        return queryPublication(
                SELECT_LATEST_PUBLICATION,
                Objects.requireNonNull(scope, "scope"),
                identifier(corpusId));
    }

    private void requireRevisionLineage(CapabilityCorpusRevision candidate) {
        Optional<CapabilityCorpusRevision> latest = findLatestRevision(
                candidate.scope(), candidate.corpusId());
        if (latest.isEmpty()) {
            if (candidate.revision() != 1 || candidate.predecessorRef() != null) {
                throw new Violation(Reason.LINEAGE_CONFLICT);
            }
            return;
        }
        CapabilityCorpusRevision current = latest.get();
        if (candidate.revision() != current.revision() + 1
                || !current.artifactRef().equals(candidate.predecessorRef())
                || !current.capabilityRef().equals(candidate.capabilityRef())) {
            throw new Violation(Reason.LINEAGE_CONFLICT);
        }
    }

    private void requirePublicationLineage(
            CapabilityCorpusPublication candidate) {
        Optional<CapabilityCorpusPublication> latest = findLatestPublication(
                candidate.scope(), candidate.corpusId());
        if (latest.isEmpty()) {
            if (candidate.revision() != 1 || candidate.predecessorRef() != null) {
                throw new Violation(Reason.LINEAGE_CONFLICT);
            }
            return;
        }
        CapabilityCorpusPublication current = latest.get();
        if (candidate.revision() != current.revision() + 1
                || !current.artifactRef().equals(candidate.predecessorRef())) {
            throw new Violation(Reason.LINEAGE_CONFLICT);
        }
    }

    private Optional<CapabilityCorpusRevision> queryRevision(
            String sql,
            CapabilitySnapshot.Scope scope,
            String corpusId,
            Object... trailing) {
        Object[] arguments = arguments(scope, corpusId, trailing);
        List<CapabilityCorpusRevision> found = jdbc.query(
                sql,
                (result, rowNumber) -> deserializeRevision(
                        scope, corpusId, result),
                arguments);
        return found.stream().findFirst();
    }

    private CapabilityCorpusRevision deserializeRevision(
            CapabilitySnapshot.Scope expectedScope,
            String expectedCorpusId,
            ResultSet result) throws SQLException {
        try {
            CapabilityCorpusRevision revision = verifyRevision(
                    mapper.readValue(
                            result.getString("revision_json"),
                            CapabilityCorpusRevision.class));
            if (!expectedScope.equals(revision.scope())
                    || !expectedCorpusId.equals(revision.corpusId())
                    || !result.getString("revision_fingerprint").equals(
                    revision.revisionFingerprint())
                    || !result.getString("command_fingerprint").equals(
                    revision.sourceCommandFingerprint())
                    || !Objects.equals(
                    result.getString("predecessor_fingerprint"),
                    predecessorFingerprint(revision.predecessorRef()))
                    || !result.getString("capability_id").equals(
                    revision.capabilityRef().id())
                    || result.getLong("capability_revision")
                    != revision.capabilityRef().revision()
                    || !result.getString("capability_fingerprint").equals(
                    revision.capabilityRef().fingerprint())
                    || !result.getString("policy_fingerprint").equals(
                    revision.governancePolicyRef().fingerprint())
                    || result.getInt("sample_count")
                    != revision.riskSummary().sampleCount()
                    || !result.getString("eligibility").equals(
                    revision.riskSummary().eligibility().name())
                    || !result.getString("created_at").equals(
                    revision.createdAt().toString())
                    || !result.getString("usable_until").equals(
                    revision.usableUntil().toString())) {
                throw new Violation(Reason.STORED_STATE_CORRUPT);
            }
            return revision;
        } catch (Violation expected) {
            throw expected;
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            throw new Violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private Optional<CapabilityCorpusPublication> queryPublication(
            String sql,
            CapabilitySnapshot.Scope scope,
            String corpusId,
            Object... trailing) {
        Object[] arguments = arguments(scope, corpusId, trailing);
        List<CapabilityCorpusPublication> found = jdbc.query(
                sql,
                (result, rowNumber) -> deserializePublication(
                        scope, corpusId, result),
                arguments);
        return found.stream().findFirst();
    }

    private CapabilityCorpusPublication deserializePublication(
            CapabilitySnapshot.Scope expectedScope,
            String expectedCorpusId,
            ResultSet result) throws SQLException {
        try {
            CapabilityCorpusPublication publication = verifyPublication(
                    mapper.readValue(
                            result.getString("publication_json"),
                            CapabilityCorpusPublication.class));
            if (!expectedScope.equals(publication.scope())
                    || !expectedCorpusId.equals(publication.corpusId())
                    || !result.getString("publication_fingerprint").equals(
                    publication.publicationFingerprint())
                    || !result.getString("command_fingerprint").equals(
                    publication.sourceCommandFingerprint())
                    || !Objects.equals(
                    result.getString("predecessor_fingerprint"),
                    predecessorFingerprint(publication.predecessorRef()))
                    || result.getLong("corpus_revision")
                    != publication.corpusRevisionRef().revision()
                    || !result.getString("corpus_revision_fingerprint").equals(
                    publication.corpusRevisionRef().fingerprint())
                    || !result.getString("policy_fingerprint").equals(
                    publication.publicationPolicyRef().fingerprint())
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

    private CapabilityCorpusRevision verifyRevision(
            CapabilityCorpusRevision revision) {
        try {
            CapabilityCorpusRevision exact =
                    Objects.requireNonNull(revision, "revision");
            if (!integrity.revisionVerified(exact)) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private CapabilityCorpusPublication verifyPublication(
            CapabilityCorpusPublication publication) {
        try {
            CapabilityCorpusPublication exact =
                    Objects.requireNonNull(publication, "publication");
            if (!integrity.publicationVerified(exact)) {
                throw new Violation(Reason.CANONICAL_INVALID);
            }
            return exact;
        } catch (Violation expected) {
            throw expected;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new Violation(Reason.IDENTITY_MISMATCH);
        }
    }

    private static CapabilityCorpusRevision sameRevisionOrConflict(
            CapabilityCorpusRevision stored,
            CapabilityCorpusRevision candidate) {
        if (stored.sourceCommandFingerprint().equals(
                candidate.sourceCommandFingerprint())
                && stored.revisionFingerprint().equals(
                candidate.revisionFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static CapabilityCorpusPublication samePublicationOrConflict(
            CapabilityCorpusPublication stored,
            CapabilityCorpusPublication candidate) {
        if (stored.sourceCommandFingerprint().equals(
                candidate.sourceCommandFingerprint())
                && stored.publicationFingerprint().equals(
                candidate.publicationFingerprint())) {
            return stored;
        }
        throw new Violation(Reason.CONTENT_CONFLICT);
    }

    private static Object[] arguments(
            CapabilitySnapshot.Scope scope,
            String corpusId,
            Object[] trailing) {
        Object[] values = new Object[6 + trailing.length];
        values[0] = scope.tenantId();
        values[1] = scope.organizationId();
        values[2] = scope.projectId();
        values[3] = scope.environmentId();
        values[4] = scope.region();
        values[5] = corpusId;
        System.arraycopy(trailing, 0, values, 6, trailing.length);
        return values;
    }

    private static String predecessorFingerprint(MirrorArtifactRef predecessor) {
        return predecessor == null ? null : predecessor.fingerprint();
    }

    private static String identifier(String value) {
        String exact = value == null ? "" : value.trim();
        if (!exact.matches("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,511}")) {
            throw new IllegalArgumentException("corpusId is invalid");
        }
        return exact;
    }
}
