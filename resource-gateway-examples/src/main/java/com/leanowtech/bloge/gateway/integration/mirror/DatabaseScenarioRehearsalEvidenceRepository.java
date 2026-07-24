package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed append-only store for signed payload-free Scenario evidence.
 */
public class DatabaseScenarioRehearsalEvidenceRepository
        implements ScenarioRehearsalEvidenceRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS scenario_rehearsal_evidence (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                request_id VARCHAR(256) NOT NULL,
                compiled_plan_id VARCHAR(512) NOT NULL,
                compiled_plan_revision BIGINT NOT NULL,
                compiled_plan_fingerprint VARCHAR(71) NOT NULL,
                result_fingerprint VARCHAR(71) NOT NULL,
                bundle_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                completed_at VARCHAR(64) NOT NULL,
                evidence_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id,
                    environment_id, region, run_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO scenario_rehearsal_evidence (
                tenant_id, organization_id, project_id, environment_id, region,
                run_id, request_id, compiled_plan_id, compiled_plan_revision,
                compiled_plan_fingerprint, result_fingerprint, bundle_fingerprint,
                schema_version, completed_at, evidence_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT run_id, request_id, compiled_plan_id, compiled_plan_revision,
                   compiled_plan_fingerprint, result_fingerprint,
                   bundle_fingerprint, schema_version, completed_at, evidence_json
            FROM scenario_rehearsal_evidence
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND run_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ScenarioRehearsalEvidenceIntegrityService integrity;

    /**
     * Creates the scope-isolated signed-evidence repository.
     *
     * @param jdbc application JDBC boundary
     * @param mapper canonical protocol mapper
     * @param integrity aggregate detached-signature verifier
     */
    public DatabaseScenarioRehearsalEvidenceRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ScenarioRehearsalEvidenceIntegrityService integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(
                integrity, "integrity");
    }

    /** Creates the append-only evidence table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public ScenarioRehearsalEvidenceBundle create(
            ScenarioRehearsalEvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        verify(bundle);
        ScenarioRehearsalResult result = bundle.result();
        String runId = bundle.attestation().runId();
        Optional<ScenarioRehearsalEvidenceBundle> exact =
                find(result.scope(), runId);
        if (exact.isPresent()) {
            return sameOrConflict(exact.get(), bundle);
        }
        String serialized = serialize(bundle);
        MirrorArtifactRef plan = result.compiledPlanRef();
        CapabilitySnapshot.Scope scope = result.scope();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    runId,
                    result.requestId(),
                    plan.id(),
                    plan.revision(),
                    plan.fingerprint(),
                    result.resultFingerprint(),
                    bundle.bundleFingerprint(),
                    bundle.schemaVersion(),
                    result.completedAt().toString(),
                    serialized);
            return bundle;
        } catch (DuplicateKeyException duplicate) {
            ScenarioRehearsalEvidenceBundle existing =
                    find(scope, runId).orElseThrow(() -> duplicate);
            return sameOrConflict(existing, bundle);
        }
    }

    @Override
    public Optional<ScenarioRehearsalEvidenceBundle> find(
            CapabilitySnapshot.Scope scope, String runId) {
        Objects.requireNonNull(scope, "scope");
        String id = required(runId, "runId");
        List<ScenarioRehearsalEvidenceBundle> bundles =
                jdbc.query(
                        SELECT_EXACT,
                        (rs, rowNumber) -> deserialize(
                                rs.getString("evidence_json"),
                                scope,
                                id,
                                rs.getString("request_id"),
                                rs.getString("compiled_plan_id"),
                                rs.getLong("compiled_plan_revision"),
                                rs.getString(
                                        "compiled_plan_fingerprint"),
                                rs.getString("result_fingerprint"),
                                rs.getString("bundle_fingerprint"),
                                rs.getString("schema_version"),
                                rs.getString("completed_at")),
                        scope.tenantId(),
                        scope.organizationId(),
                        scope.projectId(),
                        scope.environmentId(),
                        scope.region(),
                        id);
        return bundles.stream().findFirst();
    }

    private ScenarioRehearsalEvidenceBundle deserialize(
            String value,
            CapabilitySnapshot.Scope expectedScope,
            String expectedRunId,
            String expectedRequestId,
            String expectedPlanId,
            long expectedPlanRevision,
            String expectedPlanFingerprint,
            String expectedResultFingerprint,
            String expectedBundleFingerprint,
            String expectedSchemaVersion,
            String expectedCompletedAt) {
        try {
            ScenarioRehearsalEvidenceBundle bundle =
                    mapper.readValue(
                            value,
                            ScenarioRehearsalEvidenceBundle.class);
            verify(bundle);
            ScenarioRehearsalResult result = bundle.result();
            MirrorArtifactRef plan = result.compiledPlanRef();
            if (!expectedScope.equals(result.scope())
                    || !expectedRunId.equals(
                    bundle.attestation().runId())
                    || !expectedRequestId.equals(result.requestId())
                    || !expectedPlanId.equals(plan.id())
                    || expectedPlanRevision != plan.revision()
                    || !expectedPlanFingerprint.equals(
                    plan.fingerprint())
                    || !expectedResultFingerprint.equals(
                    result.resultFingerprint())
                    || !expectedBundleFingerprint.equals(
                    bundle.bundleFingerprint())
                    || !expectedSchemaVersion.equals(
                    bundle.schemaVersion())
                    || !expectedCompletedAt.equals(
                    result.completedAt().toString())) {
                throw new IllegalArgumentException(
                        "Scenario evidence index differs from signed JSON");
            }
            return bundle;
        } catch (ScenarioRehearsalEvidenceStoreException classified) {
            throw classified;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new ScenarioRehearsalEvidenceStoreException(
                    ScenarioRehearsalEvidenceStoreException.Reason
                            .INTEGRITY_INVALID,
                    "Stored Scenario rehearsal evidence failed integrity validation",
                    failure);
        }
    }

    private void verify(
            ScenarioRehearsalEvidenceBundle bundle) {
        ScenarioRehearsalEvidenceIntegrityService.Verification verification =
                integrity.verify(bundle);
        if (verification
                == ScenarioRehearsalEvidenceIntegrityService
                .Verification.UNAVAILABLE) {
            throw new ScenarioRehearsalEvidenceStoreException(
                    ScenarioRehearsalEvidenceStoreException.Reason
                            .VERIFICATION_UNAVAILABLE,
                    "Scenario evidence verification authority is unavailable",
                    null);
        }
        if (verification
                == ScenarioRehearsalEvidenceIntegrityService
                .Verification.INVALID) {
            throw new ScenarioRehearsalEvidenceStoreException(
                    ScenarioRehearsalEvidenceStoreException.Reason
                            .INTEGRITY_INVALID,
                    "Scenario evidence failed independent verification",
                    null);
        }
    }

    private String serialize(
            ScenarioRehearsalEvidenceBundle bundle) {
        try {
            return mapper.writeValueAsString(bundle);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "Scenario evidence cannot be serialized",
                    failure);
        }
    }

    private static ScenarioRehearsalEvidenceBundle sameOrConflict(
            ScenarioRehearsalEvidenceBundle existing,
            ScenarioRehearsalEvidenceBundle requested) {
        if (existing.bundleFingerprint().equals(
                requested.bundleFingerprint())
                || existing.result().resultFingerprint().equals(
                requested.result().resultFingerprint())) {
            return existing;
        }
        throw new ScenarioRehearsalEvidenceStoreException(
                ScenarioRehearsalEvidenceStoreException.Reason.CONFLICT,
                "Scenario run id already exists with different result material",
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
