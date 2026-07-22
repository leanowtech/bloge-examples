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
 * H2-backed append-only repository for signed, payload-free mirror evidence bundles.
 *
 * <p>Writes and reads require successful detached-signature verification. The complete enterprise
 * scope and run id form the primary key, while duplicated plan and fingerprint metadata is checked
 * against the signed JSON on every read. No fixture, replay, graph-context, node-value, or result
 * payload column exists in this store.</p>
 */
public class DatabaseMirrorEvidenceRepository implements MirrorEvidenceRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_run_evidence (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                run_id VARCHAR(512) NOT NULL,
                plan_id VARCHAR(512) NOT NULL,
                plan_fingerprint VARCHAR(71) NOT NULL,
                bundle_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                completed_at VARCHAR(64) NOT NULL,
                evidence_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region, run_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_run_evidence (
                tenant_id, organization_id, project_id, environment_id, region,
                run_id, plan_id, plan_fingerprint, bundle_fingerprint, schema_version,
                completed_at, evidence_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT run_id, plan_id, plan_fingerprint, bundle_fingerprint, schema_version,
                   evidence_json
            FROM mirror_run_evidence
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND run_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final MirrorEvidenceIntegrityService integrity;

    /**
     * Creates a durable mirror-evidence repository.
     *
     * @param jdbc application JDBC boundary available to the isolated mirror composition
     * @param mapper canonical protocol mapper
     * @param integrity detached-signature verification boundary
     */
    public DatabaseMirrorEvidenceRepository(
            JdbcTemplate jdbc, ObjectMapper mapper, MirrorEvidenceIntegrityService integrity) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.integrity = Objects.requireNonNull(integrity, "integrity");
    }

    /** Creates the append-only mirror-evidence table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public MirrorEvidenceBundle create(MirrorEvidenceBundle bundle) {
        Objects.requireNonNull(bundle, "bundle");
        verify(bundle);
        String serialized = serialize(bundle);
        MirrorRunEvidence evidence = bundle.evidence();
        Optional<MirrorEvidenceBundle> exact = find(evidence.scope(), evidence.runId());
        if (exact.isPresent()) {
            return sameOrConflict(exact.get(), bundle);
        }
        CapabilitySnapshot.Scope scope = evidence.scope();
        try {
            jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), evidence.runId(), evidence.planId(),
                    evidence.planFingerprint(), bundle.bundleFingerprint(), bundle.schemaVersion(),
                    evidence.completedAt().toString(), serialized);
            return bundle;
        } catch (DuplicateKeyException duplicate) {
            MirrorEvidenceBundle existing = find(scope, evidence.runId())
                    .orElseThrow(() -> duplicate);
            return sameOrConflict(existing, bundle);
        }
    }

    @Override
    public Optional<MirrorEvidenceBundle> find(CapabilitySnapshot.Scope scope, String runId) {
        Objects.requireNonNull(scope, "scope");
        String id = required(runId, "runId");
        List<MirrorEvidenceBundle> bundles = jdbc.query(SELECT_EXACT,
                (rs, rowNumber) -> deserialize(rs.getString("evidence_json"), scope, id,
                        rs.getString("plan_id"), rs.getString("plan_fingerprint"),
                        rs.getString("bundle_fingerprint"), rs.getString("schema_version")),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), id);
        return bundles.stream().findFirst();
    }

    private MirrorEvidenceBundle deserialize(String value,
                                             CapabilitySnapshot.Scope expectedScope,
                                             String expectedRunId,
                                             String expectedPlanId,
                                             String expectedPlanFingerprint,
                                             String expectedBundleFingerprint,
                                             String expectedSchemaVersion) {
        try {
            MirrorEvidenceBundle bundle = mapper.readValue(value, MirrorEvidenceBundle.class);
            verify(bundle);
            MirrorRunEvidence evidence = bundle.evidence();
            if (!expectedScope.equals(evidence.scope()) || !expectedRunId.equals(evidence.runId())
                    || !expectedPlanId.equals(evidence.planId())
                    || !expectedPlanFingerprint.equals(evidence.planFingerprint())
                    || !expectedBundleFingerprint.equals(bundle.bundleFingerprint())
                    || !expectedSchemaVersion.equals(bundle.schemaVersion())) {
                throw new IllegalArgumentException(
                        "mirror evidence indexed identity does not match signed JSON");
            }
            return bundle;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Stored mirror evidence failed integrity validation", failure);
        }
    }

    private void verify(MirrorEvidenceBundle bundle) {
        MirrorEvidenceIntegrityService.Verification verification = integrity.verify(bundle);
        if (verification != MirrorEvidenceIntegrityService.Verification.VERIFIED) {
            throw new IllegalArgumentException(
                    "Mirror evidence must have an independently verified signature: " + verification);
        }
    }

    private String serialize(MirrorEvidenceBundle bundle) {
        try {
            return mapper.writeValueAsString(bundle);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Mirror evidence cannot be serialized", failure);
        }
    }

    private static MirrorEvidenceBundle sameOrConflict(
            MirrorEvidenceBundle existing, MirrorEvidenceBundle requested) {
        if (existing.bundleFingerprint().equals(requested.bundleFingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException("Mirror run id already exists with different evidence");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
