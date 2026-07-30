package com.leanowtech.bloge.gateway.visual.authoring.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.testing.AuthoringTestEvidenceProtocol.EvidenceRecord;
import com.leanowtech.bloge.gateway.visual.runtime.VisualEvidenceSigner;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC evidence ledger with immutable rows, projected lookup keys and signed record verification.
 */
public class DatabaseAuthoringTestEvidenceRepository
        implements AuthoringTestEvidenceRepository {

    private static final String COLUMNS = """
            tenant_id, organization_id, project_id, environment_id, region,
            run_id, draft_id, authoring_revision, asset_kind, asset_ref,
            executed_at, material_fingerprint, record_json
            """;
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_test_evidence (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                run_id VARCHAR(160) NOT NULL,
                draft_id VARCHAR(255) NOT NULL,
                authoring_revision BIGINT NOT NULL,
                asset_kind VARCHAR(32) NOT NULL,
                asset_ref VARCHAR(320) NOT NULL,
                executed_at VARCHAR(64) NOT NULL,
                material_fingerprint VARCHAR(96) NOT NULL,
                record_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region, run_id
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualEvidenceSigner signer;

    public DatabaseAuthoringTestEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            VisualEvidenceSigner signer) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.signer = Objects.requireNonNull(signer, "signer");
    }

    @PostConstruct
    public void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_visual_authoring_test_evidence_draft
                ON visual_library_authoring_test_evidence (
                    tenant_id, organization_id, project_id, environment_id, region,
                    draft_id, executed_at
                )
                """);
        jdbc.execute("""
                CREATE INDEX IF NOT EXISTS idx_visual_authoring_test_evidence_asset
                ON visual_library_authoring_test_evidence (
                    tenant_id, organization_id, project_id, environment_id, region,
                    draft_id, asset_kind, asset_ref, executed_at
                )
                """);
    }

    @Override
    @Transactional
    public EvidenceRecord create(EvidenceRecord evidence) {
        EvidenceRecord signed =
                AuthoringTestEvidenceIntegrity.attach(objectMapper, signer, evidence);
        AuthoringTestScope scope = signed.scope();
        try {
            jdbc.update("""
                            INSERT INTO visual_library_authoring_test_evidence (
                                tenant_id, organization_id, project_id, environment_id, region,
                                run_id, draft_id, authoring_revision, asset_kind, asset_ref,
                                executed_at, material_fingerprint, record_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    signed.runId(),
                    signed.draftId(),
                    signed.authoringRevision(),
                    signed.assetKind().name(),
                    signed.assetRef(),
                    signed.executedAt().toString(),
                    signed.materialFingerprint(),
                    json(signed));
            return signed;
        } catch (DuplicateKeyException duplicate) {
            throw new AuthoringTestEvidenceIntegrityException(duplicate);
        }
    }

    @Override
    public Optional<EvidenceRecord> find(
            AuthoringTestScope scope,
            String runId) {
        AuthoringTestScope requiredScope = Objects.requireNonNull(scope, "scope");
        String requiredRunId = normalized(runId);
        return jdbc.query("""
                        SELECT %s
                        FROM visual_library_authoring_test_evidence
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND run_id = ?
                        """.formatted(COLUMNS), this::row,
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                requiredRunId).stream()
                .findFirst()
                .map(stored -> verified(stored, requiredScope, requiredRunId));
    }

    @Override
    public List<EvidenceRecord> findByDraft(
            AuthoringTestScope scope,
            String draftId) {
        AuthoringTestScope requiredScope = Objects.requireNonNull(scope, "scope");
        String requiredDraftId = normalized(draftId);
        return jdbc.query("""
                        SELECT %s
                        FROM visual_library_authoring_test_evidence
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND draft_id = ?
                        ORDER BY executed_at DESC, run_id
                        """.formatted(COLUMNS), this::row,
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                requiredDraftId).stream()
                .map(stored -> verified(
                        stored, requiredScope, stored.evidence().runId()))
                .sorted(Comparator.comparing(EvidenceRecord::executedAt).reversed()
                        .thenComparing(EvidenceRecord::runId))
                .toList();
    }

    private StoredRow row(ResultSet resultSet, int rowNumber) throws SQLException {
        try {
            AuthoringTestScope scope = new AuthoringTestScope(
                    resultSet.getString("tenant_id"),
                    resultSet.getString("organization_id"),
                    resultSet.getString("project_id"),
                    resultSet.getString("environment_id"),
                    resultSet.getString("region"));
            EvidenceRecord evidence = objectMapper.readValue(
                    resultSet.getString("record_json"), EvidenceRecord.class);
            return new StoredRow(
                    scope,
                    resultSet.getString("run_id"),
                    resultSet.getString("draft_id"),
                    resultSet.getLong("authoring_revision"),
                    resultSet.getString("asset_kind"),
                    resultSet.getString("asset_ref"),
                    Instant.parse(resultSet.getString("executed_at")),
                    resultSet.getString("material_fingerprint"),
                    evidence);
        } catch (AuthoringTestEvidenceIntegrityException invalid) {
            throw invalid;
        } catch (RuntimeException | JsonProcessingException invalid) {
            throw new AuthoringTestEvidenceIntegrityException(invalid);
        }
    }

    private EvidenceRecord verified(
            StoredRow row,
            AuthoringTestScope expectedScope,
            String expectedRunId) {
        EvidenceRecord evidence =
                AuthoringTestEvidenceIntegrity.verify(objectMapper, signer, row.evidence());
        if (!row.scope().equals(expectedScope)
                || !row.scope().equals(evidence.scope())
                || !row.runId().equals(expectedRunId)
                || !row.runId().equals(evidence.runId())
                || !row.draftId().equals(evidence.draftId())
                || row.authoringRevision() != evidence.authoringRevision()
                || !row.assetKind().equals(evidence.assetKind().name())
                || !row.assetRef().equals(evidence.assetRef())
                || !row.executedAt().equals(evidence.executedAt())
                || !row.materialFingerprint().equals(evidence.materialFingerprint())) {
            throw new AuthoringTestEvidenceIntegrityException();
        }
        return evidence;
    }

    private String json(EvidenceRecord evidence) {
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException failure) {
            throw new AuthoringTestEvidenceIntegrityException(failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record StoredRow(
            AuthoringTestScope scope,
            String runId,
            String draftId,
            long authoringRevision,
            String assetKind,
            String assetRef,
            java.time.Instant executedAt,
            String materialFingerprint,
            EvidenceRecord evidence
    ) {
    }
}
