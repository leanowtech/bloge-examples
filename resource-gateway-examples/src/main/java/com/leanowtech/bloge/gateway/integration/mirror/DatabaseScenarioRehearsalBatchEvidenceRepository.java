package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed append-only store for signed payload-free Scenario batch evidence.
 */
public final class DatabaseScenarioRehearsalBatchEvidenceRepository
        implements ScenarioRehearsalBatchEvidenceRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_batch_evidence (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                job_id VARCHAR(512) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                request_fingerprint VARCHAR(71) NOT NULL,
                manifest_fingerprint VARCHAR(71) NOT NULL,
                terminal_job_fingerprint VARCHAR(71) NOT NULL,
                index_fingerprint VARCHAR(71) NOT NULL,
                bundle_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                completed_at VARCHAR(64) NOT NULL,
                evidence_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, job_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO scenario_rehearsal_batch_evidence (
                tenant_id, organization_id, project_id, environment_id, region,
                job_id, request_id, request_fingerprint, manifest_fingerprint,
                terminal_job_fingerprint, index_fingerprint, bundle_fingerprint,
                schema_version, completed_at, evidence_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT job_id, request_id, request_fingerprint,
                   manifest_fingerprint, terminal_job_fingerprint,
                   index_fingerprint, bundle_fingerprint,
                   schema_version, completed_at, evidence_json
            FROM scenario_rehearsal_batch_evidence
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND job_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ScenarioRehearsalBatchEvidenceIntegrityService
            integrity;

    /**
     * Creates the scope-isolated signed batch-evidence repository.
     *
     * @param jdbc application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity batch detached-signature verifier
     */
    public DatabaseScenarioRehearsalBatchEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ScenarioRehearsalBatchEvidenceIntegrityService
                    integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
    }

    /** Creates the append-only batch evidence table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public ScenarioRehearsalBatchEvidenceBundle create(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        ScenarioRehearsalBatchEvidenceBundle exact =
                Objects.requireNonNull(bundle, "bundle");
        verify(exact);
        ScenarioRehearsalBatchEvidenceIndex index = exact.index();
        ScenarioRehearsalBatchJob job = index.job();
        CapabilitySnapshot.Scope scope = job.scope();
        Optional<ScenarioRehearsalBatchEvidenceBundle> existing =
                find(scope, job.jobId());
        if (existing.isPresent()) {
            return sameOrConflict(
                    existing.orElseThrow(), exact);
        }
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    job.jobId(),
                    job.requestId(),
                    job.requestFingerprint(),
                    job.manifestFingerprint(),
                    job.recordFingerprint(),
                    index.indexFingerprint(),
                    exact.bundleFingerprint(),
                    exact.schemaVersion(),
                    job.completedAt().toString(),
                    serialize(exact));
            return exact;
        } catch (DuplicateKeyException duplicate) {
            ScenarioRehearsalBatchEvidenceBundle retained =
                    find(scope, job.jobId())
                            .orElseThrow(() -> duplicate);
            return sameOrConflict(retained, exact);
        }
    }

    @Override
    public Optional<ScenarioRehearsalBatchEvidenceBundle> find(
            CapabilitySnapshot.Scope scope,
            String jobId) {
        CapabilitySnapshot.Scope required =
                Objects.requireNonNull(scope, "scope");
        String id = required(jobId, "jobId");
        List<ScenarioRehearsalBatchEvidenceBundle> bundles =
                jdbc.query(
                        SELECT_EXACT,
                        (result, rowNumber) -> deserialize(
                                result.getString("evidence_json"),
                                required,
                                id,
                                result.getString("request_id"),
                                result.getString(
                                        "request_fingerprint"),
                                result.getString(
                                        "manifest_fingerprint"),
                                result.getString(
                                        "terminal_job_fingerprint"),
                                result.getString(
                                        "index_fingerprint"),
                                result.getString(
                                        "bundle_fingerprint"),
                                result.getString("schema_version"),
                                result.getString("completed_at")),
                        required.tenantId(),
                        required.organizationId(),
                        required.projectId(),
                        required.environmentId(),
                        required.region(),
                        id);
        return bundles.stream().findFirst();
    }

    private ScenarioRehearsalBatchEvidenceBundle deserialize(
            String value,
            CapabilitySnapshot.Scope expectedScope,
            String expectedJobId,
            String expectedRequestId,
            String expectedRequestFingerprint,
            String expectedManifestFingerprint,
            String expectedTerminalJobFingerprint,
            String expectedIndexFingerprint,
            String expectedBundleFingerprint,
            String expectedSchemaVersion,
            String expectedCompletedAt) {
        try {
            ScenarioRehearsalBatchEvidenceBundle bundle =
                    mapper.readValue(
                            value,
                            ScenarioRehearsalBatchEvidenceBundle.class);
            verify(bundle);
            ScenarioRehearsalBatchEvidenceIndex index =
                    bundle.index();
            ScenarioRehearsalBatchJob job = index.job();
            if (!expectedScope.equals(job.scope())
                    || !expectedJobId.equals(job.jobId())
                    || !expectedRequestId.equals(job.requestId())
                    || !expectedRequestFingerprint.equals(
                    job.requestFingerprint())
                    || !expectedManifestFingerprint.equals(
                    job.manifestFingerprint())
                    || !expectedTerminalJobFingerprint.equals(
                    job.recordFingerprint())
                    || !expectedIndexFingerprint.equals(
                    index.indexFingerprint())
                    || !expectedBundleFingerprint.equals(
                    bundle.bundleFingerprint())
                    || !expectedSchemaVersion.equals(
                    bundle.schemaVersion())
                    || !expectedCompletedAt.equals(
                    job.completedAt().toString())) {
                throw new IllegalArgumentException(
                        "Scenario batch evidence index differs from signed JSON");
            }
            return bundle;
        } catch (ScenarioRehearsalBatchEvidenceStoreException
                 classified) {
            throw classified;
        } catch (JsonProcessingException
                 | IllegalArgumentException failure) {
            throw new ScenarioRehearsalBatchEvidenceStoreException(
                    ScenarioRehearsalBatchEvidenceStoreException
                            .Reason.INTEGRITY_INVALID,
                    "Stored Scenario batch evidence failed integrity validation",
                    failure);
        }
    }

    private void verify(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        ScenarioRehearsalBatchEvidenceIntegrityService.Verification
                verification = integrity.verify(bundle);
        if (verification
                == ScenarioRehearsalBatchEvidenceIntegrityService
                .Verification.UNAVAILABLE) {
            throw new ScenarioRehearsalBatchEvidenceStoreException(
                    ScenarioRehearsalBatchEvidenceStoreException
                            .Reason.VERIFICATION_UNAVAILABLE,
                    "Scenario batch evidence verification authority is unavailable",
                    null);
        }
        if (verification
                == ScenarioRehearsalBatchEvidenceIntegrityService
                .Verification.INVALID) {
            throw new ScenarioRehearsalBatchEvidenceStoreException(
                    ScenarioRehearsalBatchEvidenceStoreException
                            .Reason.INTEGRITY_INVALID,
                    "Scenario batch evidence failed independent verification",
                    null);
        }
    }

    private String serialize(
            ScenarioRehearsalBatchEvidenceBundle bundle) {
        try {
            return mapper.writeValueAsString(bundle);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Scenario batch evidence cannot be serialized",
                    failure);
        }
    }

    private static ScenarioRehearsalBatchEvidenceBundle
    sameOrConflict(
            ScenarioRehearsalBatchEvidenceBundle existing,
            ScenarioRehearsalBatchEvidenceBundle requested) {
        if (existing.bundleFingerprint().equals(
                requested.bundleFingerprint())
                || existing.index().indexFingerprint().equals(
                requested.index().indexFingerprint())) {
            return existing;
        }
        throw new ScenarioRehearsalBatchEvidenceStoreException(
                ScenarioRehearsalBatchEvidenceStoreException
                        .Reason.CONFLICT,
                "Scenario batch id already exists with different evidence material",
                null);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(
                    field + " must not be blank");
        }
        return normalized;
    }
}
