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
 * H2-backed append-only repository for sealed, payload-free mirror plans.
 *
 * <p>The complete enterprise scope participates in the primary key. Indexed identity columns are
 * checked against the deserialized protocol value on every read, and the canonical plan seal is
 * recomputed before a value crosses the repository boundary. This prevents a damaged or manually
 * moved row from being served under another tenant, plan id, or fingerprint.</p>
 */
public class DatabaseMirrorPlanRepository implements MirrorPlanRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS mirror_plans (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                plan_id VARCHAR(512) NOT NULL,
                plan_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                compiled_at VARCHAR(64) NOT NULL,
                expires_at VARCHAR(64) NOT NULL,
                plan_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region, plan_id
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO mirror_plans (
                tenant_id, organization_id, project_id, environment_id, region,
                plan_id, plan_fingerprint, schema_version, compiled_at, expires_at, plan_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT plan_id, plan_fingerprint, schema_version, plan_json
            FROM mirror_plans
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND plan_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * Creates a durable mirror-plan repository.
     *
     * @param jdbc application JDBC boundary available to the isolated mirror composition
     * @param mapper canonical protocol mapper
     */
    public DatabaseMirrorPlanRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates the append-only mirror-plan table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public MirrorPlan create(MirrorPlan plan) {
        Objects.requireNonNull(plan, "plan");
        MirrorPlanIntegrity.verify(mapper, plan);
        String serialized = serialize(plan);
        Optional<MirrorPlan> exact = find(plan.scope(), plan.planId());
        if (exact.isPresent()) {
            return sameOrConflict(exact.get(), plan);
        }
        CapabilitySnapshot.Scope scope = plan.scope();
        try {
            jdbc.update(INSERT, scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), plan.planId(), plan.planFingerprint(),
                    plan.schemaVersion(), plan.compiledAt().toString(), plan.expiresAt().toString(),
                    serialized);
            return plan;
        } catch (DuplicateKeyException duplicate) {
            MirrorPlan existing = find(scope, plan.planId()).orElseThrow(() -> duplicate);
            return sameOrConflict(existing, plan);
        }
    }

    @Override
    public Optional<MirrorPlan> find(CapabilitySnapshot.Scope scope, String planId) {
        Objects.requireNonNull(scope, "scope");
        String id = required(planId, "planId");
        List<MirrorPlan> plans = jdbc.query(SELECT_EXACT, (rs, rowNumber) -> deserialize(
                        rs.getString("plan_json"), scope, id,
                        rs.getString("plan_fingerprint"), rs.getString("schema_version")),
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environmentId(),
                scope.region(), id);
        return plans.stream().findFirst();
    }

    private MirrorPlan deserialize(String value,
                                   CapabilitySnapshot.Scope expectedScope,
                                   String expectedPlanId,
                                   String expectedFingerprint,
                                   String expectedSchemaVersion) {
        try {
            MirrorPlan plan = mapper.readValue(value, MirrorPlan.class);
            MirrorPlanIntegrity.verify(mapper, plan);
            if (!expectedScope.equals(plan.scope()) || !expectedPlanId.equals(plan.planId())
                    || !expectedFingerprint.equals(plan.planFingerprint())
                    || !expectedSchemaVersion.equals(plan.schemaVersion())) {
                throw new IllegalArgumentException("mirror plan indexed identity does not match JSON");
            }
            return plan;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException("Stored mirror plan failed integrity validation", failure);
        }
    }

    private String serialize(MirrorPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException("Mirror plan cannot be serialized", failure);
        }
    }

    private static MirrorPlan sameOrConflict(MirrorPlan existing, MirrorPlan requested) {
        if (existing.planFingerprint().equals(requested.planFingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException("Mirror plan id already exists with different content");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
