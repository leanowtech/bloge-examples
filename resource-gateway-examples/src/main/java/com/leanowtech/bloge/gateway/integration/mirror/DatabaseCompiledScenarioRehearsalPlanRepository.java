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
 * H2-backed append-only registry for payload-free compiled rehearsal plans.
 */
public class DatabaseCompiledScenarioRehearsalPlanRepository
        implements CompiledScenarioRehearsalPlanRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS compiled_scenario_rehearsal_plans (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                plan_id VARCHAR(512) NOT NULL,
                plan_revision BIGINT NOT NULL,
                plan_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                plan_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    plan_id, plan_revision
                )
            )
            """;
    private static final String INSERT = """
            INSERT INTO compiled_scenario_rehearsal_plans (
                tenant_id, organization_id, project_id, environment_id, region,
                plan_id, plan_revision, plan_fingerprint, schema_version, plan_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String SELECT_EXACT = """
            SELECT plan_fingerprint, schema_version, plan_json
            FROM compiled_scenario_rehearsal_plans
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ?
              AND plan_id = ? AND plan_revision = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** Creates the durable compiled-plan registry. */
    public DatabaseCompiledScenarioRehearsalPlanRepository(
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /** Creates the append-only table when absent. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    @Transactional
    public CompiledScenarioRehearsalPlan create(
            CompiledScenarioRehearsalPlan plan) {
        CompiledScenarioRehearsalPlanIntegrity.verify(mapper, plan);
        Optional<CompiledScenarioRehearsalPlan> existing =
                find(plan.scope(), plan.planId(), plan.revision());
        if (existing.isPresent()) {
            return sameOrConflict(existing.get(), plan);
        }
        CapabilitySnapshot.Scope scope = plan.scope();
        try {
            jdbc.update(
                    INSERT,
                    scope.tenantId(),
                    scope.organizationId(),
                    scope.projectId(),
                    scope.environmentId(),
                    scope.region(),
                    plan.planId(),
                    plan.revision(),
                    plan.fingerprint(),
                    plan.schemaVersion(),
                    serialize(plan));
            return plan;
        } catch (DuplicateKeyException duplicate) {
            CompiledScenarioRehearsalPlan stored =
                    find(scope, plan.planId(), plan.revision())
                            .orElseThrow(() -> duplicate);
            return sameOrConflict(stored, plan);
        }
    }

    @Override
    public Optional<CompiledScenarioRehearsalPlan> find(
            CapabilitySnapshot.Scope scope, String planId, long revision) {
        Objects.requireNonNull(scope, "scope");
        String id = required(planId, "planId");
        if (revision < 1) {
            throw new IllegalArgumentException(
                    "compiled plan revision must be positive");
        }
        List<CompiledScenarioRehearsalPlan> plans = jdbc.query(
                SELECT_EXACT,
                (rs, rowNumber) -> deserialize(
                        rs.getString("plan_json"),
                        scope,
                        id,
                        revision,
                        rs.getString("plan_fingerprint"),
                        rs.getString("schema_version")),
                scope.tenantId(),
                scope.organizationId(),
                scope.projectId(),
                scope.environmentId(),
                scope.region(),
                id,
                revision);
        return plans.stream().findFirst();
    }

    private CompiledScenarioRehearsalPlan deserialize(
            String json,
            CapabilitySnapshot.Scope expectedScope,
            String expectedId,
            long expectedRevision,
            String expectedFingerprint,
            String expectedSchemaVersion) {
        try {
            CompiledScenarioRehearsalPlan plan = mapper.readValue(
                    json, CompiledScenarioRehearsalPlan.class);
            CompiledScenarioRehearsalPlanIntegrity.verify(mapper, plan);
            if (!expectedScope.equals(plan.scope())
                    || !expectedId.equals(plan.planId())
                    || expectedRevision != plan.revision()
                    || !expectedFingerprint.equals(plan.fingerprint())
                    || !expectedSchemaVersion.equals(plan.schemaVersion())) {
                throw new IllegalArgumentException(
                        "compiled rehearsal plan indexed identity does not match JSON");
            }
            return plan;
        } catch (JsonProcessingException | IllegalArgumentException failure) {
            throw new IllegalStateException(
                    "Stored compiled rehearsal plan failed integrity validation",
                    failure);
        }
    }

    private String serialize(CompiledScenarioRehearsalPlan plan) {
        try {
            return mapper.writeValueAsString(plan);
        } catch (JsonProcessingException failure) {
            throw new IllegalArgumentException(
                    "compiled rehearsal plan cannot be serialized", failure);
        }
    }

    private static CompiledScenarioRehearsalPlan sameOrConflict(
            CompiledScenarioRehearsalPlan existing,
            CompiledScenarioRehearsalPlan requested) {
        if (existing.fingerprint().equals(requested.fingerprint())) {
            return existing;
        }
        throw new IllegalArgumentException(
                "compiled rehearsal plan revision already exists with different content");
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
