package com.leanowtech.bloge.gateway.testing.authoring.fixture;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.TestRuntimeTransactionMutation;
import com.leanowtech.bloge.gateway.testing.api.TestingArtifactScope;
import com.leanowtech.bloge.gateway.testing.authoring.fixture.AuthoringFixtureProtocol.FixtureReceipt;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Encrypted authoring-fixture vault in the isolated test-runtime database.
 *
 * <p>Fixture content and its security event commit in one local transaction. Every read binds the
 * complete enterprise scope, searchable projections, immutable descriptor, protected envelope and
 * payload-free record commitment. Expiry erases the envelope while preserving a verifiable lineage
 * tombstone.</p>
 */
public final class DatabaseAuthoringFixtureRepository
        implements AuthoringFixtureRepository {

    private static final String COLUMN_LIST = """
            tenant_id, organization_id, project_id, environment_id, region,
            fixture_id, revision, source_kind, asset_kind, asset_ref,
            draft_id, authoring_revision, artifact_fingerprint, payload_fingerprint,
            classification, state, expires_at, descriptor_json, protected_payload,
            record_fingerprint
            """;
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS rg_visual_authoring_fixtures (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                fixture_id VARCHAR(160) NOT NULL,
                revision BIGINT NOT NULL,
                source_kind VARCHAR(48) NOT NULL,
                asset_kind VARCHAR(32) NOT NULL,
                asset_ref VARCHAR(320) NOT NULL,
                draft_id VARCHAR(160) NOT NULL,
                authoring_revision BIGINT NOT NULL,
                artifact_fingerprint VARCHAR(96) NOT NULL,
                payload_fingerprint VARCHAR(96) NOT NULL,
                classification VARCHAR(32) NOT NULL,
                state VARCHAR(32) NOT NULL,
                expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                descriptor_json CLOB NOT NULL,
                protected_payload CLOB,
                record_fingerprint VARCHAR(96) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    fixture_id, revision
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public DatabaseAuthoringFixtureRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_visual_authoring_fixture_expiry
                ON rg_visual_authoring_fixtures (state, expires_at)
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_rg_visual_authoring_fixture_asset
                ON rg_visual_authoring_fixtures (
                    tenant_id, organization_id, project_id, environment_id, region,
                    asset_kind, asset_ref
                )
                """);
    }

    @Override
    public StoredAuthoringFixture create(
            TestingArtifactScope scope,
            StoredAuthoringFixture fixture,
            long expectedRevision,
            TestRuntimeTransactionMutation audit) {
        TestingArtifactScope requiredScope = Objects.requireNonNull(scope, "scope");
        StoredAuthoringFixture snapshot =
                AuthoringFixtureIntegrity.verify(objectMapper, fixture);
        TestRuntimeTransactionMutation requiredAudit =
                Objects.requireNonNull(audit, "audit");
        FixtureReceipt descriptor = snapshot.descriptor();
        if (!requiredScope.equals(snapshot.scope())
                || !StoredAuthoringFixture.AVAILABLE.equals(snapshot.state())
                || !snapshot.payloadAvailable()
                || descriptor.revision() != expectedRevision + 1) {
            throw new AuthoringFixtureIntegrityException();
        }
        try {
            StoredAuthoringFixture created = transactions.execute(status -> {
                long current = latestRevision(requiredScope, descriptor.fixtureId());
                if (current != expectedRevision) {
                    throw new AuthoringFixtureRevisionConflictException(current);
                }
                if (expectedRevision > 0) {
                    StoredAuthoringFixture previous = queryExact(
                                    requiredScope,
                                    descriptor.fixtureId(),
                                    expectedRevision)
                            .map(row -> verifiedLookup(
                                    row,
                                    requiredScope,
                                    descriptor.fixtureId(),
                                    expectedRevision))
                            .orElseThrow(AuthoringFixtureIntegrityException::new);
                    if (!sameLineage(previous.descriptor(), descriptor)) {
                        throw new AuthoringFixtureLineageConflictException();
                    }
                }
                insert(snapshot);
                requiredAudit.apply(jdbc);
                return snapshot;
            });
            if (created == null) {
                throw new IllegalStateException(
                        "Authoring fixture transaction returned no result");
            }
            return created;
        } catch (DuplicateKeyException conflict) {
            throw new AuthoringFixtureRevisionConflictException(
                    latestRevision(requiredScope, descriptor.fixtureId()));
        }
    }

    @Override
    public Optional<StoredAuthoringFixture> find(
            TestingArtifactScope scope,
            String fixtureId,
            long revision) {
        TestingArtifactScope requiredScope = Objects.requireNonNull(scope, "scope");
        String requiredFixtureId = normalized(fixtureId);
        Optional<StoredRow> found = queryExact(
                requiredScope, requiredFixtureId, revision);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        StoredAuthoringFixture fixture = verifiedLookup(
                found.get(), requiredScope, requiredFixtureId, revision);
        Instant observedAt = currentTime();
        if (fixture.payloadAvailable()
                && !fixture.descriptor().expiresAt().isAfter(observedAt)) {
            StoredAuthoringFixture tombstone = expire(
                    found.get(), fixture, observedAt);
            if (tombstone != null) {
                return Optional.of(tombstone);
            }
            return queryExact(requiredScope, requiredFixtureId, revision)
                    .map(row -> verifiedLookup(
                            row, requiredScope, requiredFixtureId, revision));
        }
        return Optional.of(fixture);
    }

    @Override
    public long latestRevision(TestingArtifactScope scope, String fixtureId) {
        TestingArtifactScope requiredScope = Objects.requireNonNull(scope, "scope");
        String requiredFixtureId = normalized(fixtureId);
        return jdbc.query("""
                        SELECT %s
                        FROM rg_visual_authoring_fixtures
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND fixture_id = ?
                        ORDER BY revision DESC
                        LIMIT 1
                        """.formatted(COLUMN_LIST), (rs, row) -> storedRow(rs),
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                requiredFixtureId).stream()
                .findFirst()
                .map(row -> verifiedLookup(
                        row,
                        requiredScope,
                        requiredFixtureId,
                        row.revision()).descriptor().revision())
                .orElse(0L);
    }

    @Override
    public int expireDue(Instant observedAt, int limit) {
        Instant cutoff = Objects.requireNonNull(observedAt, "observedAt");
        int bounded = Math.max(1, Math.min(1_000, limit));
        List<StoredRow> due = jdbc.query("""
                        SELECT %s
                        FROM rg_visual_authoring_fixtures
                        WHERE state = ? AND expires_at <= ?
                        ORDER BY expires_at, tenant_id, organization_id, project_id,
                                 environment_id, region, fixture_id, revision
                        LIMIT ?
                        """.formatted(COLUMN_LIST), (rs, row) -> storedRow(rs),
                StoredAuthoringFixture.AVAILABLE,
                Timestamp.from(cutoff),
                bounded);
        int expired = 0;
        for (StoredRow row : due) {
            StoredAuthoringFixture fixture = verifiedStoredRow(row);
            if (expire(row, fixture, cutoff) != null) {
                expired++;
            }
        }
        return expired;
    }

    private void insert(StoredAuthoringFixture fixture) {
        TestingArtifactScope scope = fixture.scope();
        FixtureReceipt descriptor = fixture.descriptor();
        jdbc.update("""
                        INSERT INTO rg_visual_authoring_fixtures (
                            tenant_id, organization_id, project_id, environment_id, region,
                            fixture_id, revision, source_kind, asset_kind, asset_ref,
                            draft_id, authoring_revision, artifact_fingerprint,
                            payload_fingerprint, classification, state, expires_at,
                            descriptor_json, protected_payload, record_fingerprint
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                descriptor.fixtureId(),
                descriptor.revision(),
                descriptor.sourceKind().name(),
                descriptor.assetKind().name(),
                descriptor.assetRef(),
                descriptor.draftId(),
                descriptor.authoringRevision(),
                descriptor.artifactFingerprint(),
                descriptor.payloadFingerprint(),
                descriptor.classification(),
                fixture.state(),
                Timestamp.from(descriptor.expiresAt()),
                json(descriptor),
                fixture.protectedPayload(),
                fixture.recordFingerprint());
    }

    private Optional<StoredRow> queryExact(
            TestingArtifactScope scope,
            String fixtureId,
            long revision) {
        return jdbc.query("""
                        SELECT %s
                        FROM rg_visual_authoring_fixtures
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                          AND fixture_id = ? AND revision = ?
                        """.formatted(COLUMN_LIST), (rs, row) -> storedRow(rs),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                fixtureId,
                revision).stream().findFirst();
    }

    private StoredAuthoringFixture verifiedLookup(
            StoredRow row,
            TestingArtifactScope scope,
            String fixtureId,
            long revision) {
        return AuthoringFixtureIntegrity.verifyLookup(
                objectMapper,
                verifiedStoredRow(row),
                scope,
                fixtureId,
                revision);
    }

    private StoredAuthoringFixture verifiedStoredRow(StoredRow row) {
        StoredAuthoringFixture fixture =
                AuthoringFixtureIntegrity.verify(objectMapper, row.fixture());
        FixtureReceipt descriptor = fixture.descriptor();
        TestingArtifactScope scope = fixture.scope();
        if (!Objects.equals(row.scope(), scope)
                || !Objects.equals(row.fixtureId(), descriptor.fixtureId())
                || row.revision() != descriptor.revision()
                || !Objects.equals(row.sourceKind(), descriptor.sourceKind().name())
                || !Objects.equals(row.assetKind(), descriptor.assetKind().name())
                || !Objects.equals(row.assetRef(), descriptor.assetRef())
                || !Objects.equals(row.draftId(), descriptor.draftId())
                || row.authoringRevision() != descriptor.authoringRevision()
                || !Objects.equals(
                        row.artifactFingerprint(),
                        descriptor.artifactFingerprint())
                || !Objects.equals(
                        row.payloadFingerprint(),
                        descriptor.payloadFingerprint())
                || !Objects.equals(
                        row.classification(),
                        descriptor.classification())
                || !Objects.equals(row.state(), fixture.state())
                || !Objects.equals(row.expiresAt(), descriptor.expiresAt())) {
            throw new AuthoringFixtureIntegrityException();
        }
        return fixture;
    }

    private static boolean sameLineage(
            FixtureReceipt previous, FixtureReceipt candidate) {
        return previous.sourceKind() == candidate.sourceKind()
                && previous.assetKind() == candidate.assetKind()
                && previous.assetRef().equals(candidate.assetRef())
                && previous.draftId().equals(candidate.draftId())
                && previous.classification().equals(candidate.classification());
    }

    private StoredAuthoringFixture expire(
            StoredRow row,
            StoredAuthoringFixture fixture,
            Instant observedAt) {
        StoredAuthoringFixture tombstone = AuthoringFixtureIntegrity.attach(
                objectMapper, fixture.expired());
        TestingArtifactScope scope = fixture.scope();
        FixtureReceipt descriptor = fixture.descriptor();
        int changed = jdbc.update("""
                        UPDATE rg_visual_authoring_fixtures
                        SET state = ?, protected_payload = NULL, record_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                          AND fixture_id = ? AND revision = ?
                          AND state = ? AND record_fingerprint = ? AND expires_at <= ?
                        """,
                StoredAuthoringFixture.EXPIRED,
                tombstone.recordFingerprint(),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                descriptor.fixtureId(),
                descriptor.revision(),
                StoredAuthoringFixture.AVAILABLE,
                row.recordFingerprint(),
                Timestamp.from(observedAt));
        return changed == 1 ? tombstone : null;
    }

    private StoredRow storedRow(ResultSet rs) throws SQLException {
        try {
            TestingArtifactScope scope = new TestingArtifactScope(
                    rs.getString("tenant_id"),
                    rs.getString("organization_id"),
                    rs.getString("project_id"),
                    rs.getString("environment_id"),
                    rs.getString("region"));
            FixtureReceipt descriptor = readJson(
                    rs.getString("descriptor_json"), FixtureReceipt.class);
            String envelope = rs.getString("protected_payload");
            StoredAuthoringFixture fixture = new StoredAuthoringFixture(
                    StoredAuthoringFixture.SCHEMA_VERSION,
                    scope,
                    descriptor,
                    rs.getString("state"),
                    envelope != null,
                    envelope,
                    rs.getString("record_fingerprint"));
            return new StoredRow(
                    scope,
                    rs.getString("fixture_id"),
                    rs.getLong("revision"),
                    rs.getString("source_kind"),
                    rs.getString("asset_kind"),
                    rs.getString("asset_ref"),
                    rs.getString("draft_id"),
                    rs.getLong("authoring_revision"),
                    rs.getString("artifact_fingerprint"),
                    rs.getString("payload_fingerprint"),
                    rs.getString("classification"),
                    rs.getString("state"),
                    rs.getTimestamp("expires_at").toInstant(),
                    fixture,
                    rs.getString("record_fingerprint"));
        } catch (AuthoringFixtureIntegrityException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new AuthoringFixtureIntegrityException(invalid);
        }
    }

    private Instant currentTime() {
        Timestamp value = jdbc.queryForObject(
                "SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (value == null) {
            throw new IllegalStateException(
                    "Authoring fixture database did not return current time");
        }
        return value.toInstant();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new AuthoringFixtureIntegrityException(failure);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException failure) {
            throw new AuthoringFixtureIntegrityException(failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredRow(
            TestingArtifactScope scope,
            String fixtureId,
            long revision,
            String sourceKind,
            String assetKind,
            String assetRef,
            String draftId,
            long authoringRevision,
            String artifactFingerprint,
            String payloadFingerprint,
            String classification,
            String state,
            Instant expiresAt,
            StoredAuthoringFixture fixture,
            String recordFingerprint
    ) {
    }
}
