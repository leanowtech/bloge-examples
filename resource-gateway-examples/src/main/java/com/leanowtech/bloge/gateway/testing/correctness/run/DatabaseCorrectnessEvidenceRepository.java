package com.leanowtech.bloge.gateway.testing.correctness.run;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL/H2 immutable repository with indexed-column and canonical-JSON integrity checks. */
public final class DatabaseCorrectnessEvidenceRepository
        implements CorrectnessEvidenceRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCorrectnessEvidenceRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<StoredCorrectnessEvidenceCompanion> find(
            EnterpriseScope scope, String suiteRunId) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactRunId = required(suiteRunId, "suiteRunId");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_evidence_companions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND suite_run_id = ?
                        """,
                (result, row) -> read(result, exactScope, exactRunId),
                exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                exactScope.environment(), exactScope.region(), exactRunId).stream()
                .flatMap(Optional::stream).findFirst();
    }

    @Override
    @Transactional
    public StoredCorrectnessEvidenceCompanion saveIfAbsent(
            EnterpriseScope scope,
            StoredCorrectnessEvidenceCompanion candidate
    ) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        if (candidate == null || !exactScope.equals(candidate.companion().scope())) {
            throw new IllegalArgumentException("Evidence companion scope is required");
        }
        CorrectnessEvidenceCompanion value = candidate.companion();
        try {
            jdbc.update("""
                            INSERT INTO rg_correctness_evidence_companions (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                suite_run_id, evidence_companion_id, companion_fingerprint,
                                publication_id, publication_fingerprint,
                                suite_evidence_fingerprint, canonical_json, created_at, created_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    exactScope.tenantId(), exactScope.organizationId(), exactScope.projectId(),
                    exactScope.environment(), exactScope.region(), value.suiteRunId(),
                    value.evidenceCompanionId(), candidate.companionFingerprint(),
                    value.publicationRef().publicationId(), value.publicationRef().fingerprint(),
                    value.suiteEvidenceFingerprint(), serialize(candidate),
                    value.metadata().createdAt(), value.metadata().createdBy().id());
        } catch (DuplicateKeyException idempotentOrConflict) {
            StoredCorrectnessEvidenceCompanion existing = find(
                    exactScope, value.suiteRunId()).orElse(null);
            if (candidate.equals(existing)) return existing;
            throw new IllegalStateException(
                    "Correctness evidence immutable identity conflicts with stored content");
        }
        StoredCorrectnessEvidenceCompanion persisted = find(
                exactScope, value.suiteRunId()).orElseThrow(() -> new IllegalStateException(
                "Correctness evidence companion failed independent read-after-write verification"));
        if (!candidate.equals(persisted)) {
            throw new IllegalStateException(
                    "Correctness evidence companion changed during persistence");
        }
        return persisted;
    }

    private Optional<StoredCorrectnessEvidenceCompanion> read(
            ResultSet result,
            EnterpriseScope scope,
            String suiteRunId
    ) throws SQLException {
        try {
            StoredCorrectnessEvidenceCompanion stored = mapper.readValue(
                    result.getString("canonical_json"),
                    StoredCorrectnessEvidenceCompanion.class);
            CorrectnessEvidenceCompanion value = stored.companion();
            String computed = CorrectnessProtocolFingerprint.derivedFingerprint(mapper, value);
            boolean valid = value.scope().equals(scope)
                    && value.suiteRunId().equals(suiteRunId)
                    && value.evidenceCompanionId().equals(
                    result.getString("evidence_companion_id"))
                    && stored.companionFingerprint().equals(
                    result.getString("companion_fingerprint"))
                    && stored.companionFingerprint().equals(computed)
                    && value.publicationRef().publicationId().equals(
                    result.getString("publication_id"))
                    && value.publicationRef().fingerprint().equals(
                    result.getString("publication_fingerprint"))
                    && value.suiteEvidenceFingerprint().equals(
                    result.getString("suite_evidence_fingerprint"));
            if (!valid) {
                throw new IllegalStateException(
                        "Stored Correctness evidence companion integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to decode Correctness evidence companion", failure);
        }
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to encode Correctness evidence companion", failure);
        }
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }
}
