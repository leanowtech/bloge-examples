package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * H2-backed Scenario draft-set repository with exact scope isolation and retained history.
 */
public class DatabaseScenarioDraftSetRepository implements ScenarioDraftSetRepository {

    private static final int MAX_FINGERPRINT_BYTES = 16 * 1_048_576;
    private static final String CREATE_CURRENT = """
            CREATE TABLE IF NOT EXISTS visual_scenario_draft_sets (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                scenario_draft_set_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id
                )
            )
            """;
    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS visual_scenario_draft_set_revisions (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                scenario_draft_set_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id, revision
                )
            )
            """;
    private static final String CREATE_CONTRACT_BASELINES = """
            CREATE TABLE IF NOT EXISTS visual_scenario_contract_baselines (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                scenario_draft_set_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                baseline_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id, revision
                )
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc application database access
     * @param objectMapper protocol serializer
     */
    public DatabaseScenarioDraftSetRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** Creates persistence tables without modifying existing graph-draft storage. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_CURRENT);
        jdbc.execute(CREATE_HISTORY);
        jdbc.execute(CREATE_CONTRACT_BASELINES);
    }

    @Override
    public Optional<StoredScenarioDraftSet> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_scenario_draft_sets
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(scenarioDraftSetId))
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public List<StoredScenarioDraftSet> revisions(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_scenario_draft_set_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?
                        ORDER BY revision DESC
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(scenarioDraftSetId))
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<StoredScenarioDraftSet> findRevision(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            long revision) {
        if (revision <= 0) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_scenario_draft_set_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ? AND revision = ?
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(scenarioDraftSetId), revision)
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public Optional<StoredScenarioDraftSet> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSet candidate,
            String actor) {
        return saveIfRevision(expectedRevision, candidate, null, actor);
    }

    @Override
    @Transactional
    public synchronized Optional<StoredScenarioDraftSet> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSet candidate,
            ContractDraft contractBaseline,
            String actor) {
        if (candidate == null || expectedRevision < 0) {
            throw new IllegalArgumentException("Scenario draft set and non-negative expected revision are required");
        }
        if (contractBaseline != null
                && !contractBaseline.fingerprint(objectMapper).equals(candidate.contractFingerprint())) {
            throw new IllegalArgumentException(
                    "Scenario Contract fingerprint does not match the supplied authoritative baseline");
        }
        ScenarioDraftSet.EnterpriseScope scope = candidate.scope();
        String id = normalized(candidate.scenarioDraftSetId());
        Instant now = Instant.now();
        StoredScenarioDraftSet current = find(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.revision() != expectedRevision)) {
            return Optional.empty();
        }
        long nextRevision = expectedRevision + 1;
        Instant createdAt = current == null ? now : current.draftSet().metadata().createdAt();
        String owner = current == null
                ? firstNonBlank(candidate.metadata().owner(), actor)
                : current.draftSet().metadata().owner();
        ScenarioDraftSet storedDraft = candidate.withStorageIdentity(
                id, nextRevision, createdAt, now, owner);
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper, storedDraft, MAX_FINGERPRINT_BYTES);
        StoredScenarioDraftSet stored = new StoredScenarioDraftSet(
                "", id, nextRevision, fingerprint, storedDraft, now, actor);
        String json = serialize(stored);

        if (current == null) {
            try {
                jdbc.update("""
                                INSERT INTO visual_scenario_draft_sets (
                                    tenant_id, organization_id, project_id, environment_id, region_id,
                                    scenario_draft_set_id, revision, stored_json
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        scope.tenantId(), scope.organizationId(), scope.projectId(),
                        scope.environment(), scope.region(), id, nextRevision, json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else {
            int updated = jdbc.update("""
                            UPDATE visual_scenario_draft_sets
                            SET revision = ?, stored_json = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region_id = ?
                              AND scenario_draft_set_id = ? AND revision = ?
                            """,
                    nextRevision, json,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), id, expectedRevision);
            if (updated == 0) {
                return Optional.empty();
            }
        }
        jdbc.update("""
                        INSERT INTO visual_scenario_draft_set_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            scenario_draft_set_id, revision, stored_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), id, nextRevision, json);
        if (contractBaseline != null) {
            ScenarioContractBaseline baseline = new ScenarioContractBaseline(
                    "",
                    id,
                    nextRevision,
                    candidate.contractFingerprint(),
                    contractBaseline,
                    now,
                    actor);
            jdbc.update("""
                            INSERT INTO visual_scenario_contract_baselines (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                scenario_draft_set_id, revision, baseline_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), id, nextRevision,
                    serialize(baseline, id));
        }
        return Optional.of(stored);
    }

    @Override
    public Optional<ScenarioContractBaseline> findContractBaseline(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            long revision) {
        if (revision <= 0) {
            return Optional.empty();
        }
        return jdbc.query("""
                        SELECT baseline_json
                        FROM visual_scenario_contract_baselines
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ? AND revision = ?
                        """,
                (rs, rowNum) -> readBaseline(rs.getString("baseline_json")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(scenarioDraftSetId), revision)
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    private Optional<StoredScenarioDraftSet> read(String json) {
        try {
            StoredScenarioDraftSet stored = objectMapper.readValue(json, StoredScenarioDraftSet.class);
            String expected = VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, stored.draftSet(), MAX_FINGERPRINT_BYTES);
            return expected.equals(stored.fingerprint()) ? Optional.of(stored) : Optional.empty();
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private String serialize(StoredScenarioDraftSet stored) {
        return serialize(stored, stored.scenarioDraftSetId());
    }

    private Optional<ScenarioContractBaseline> readBaseline(String json) {
        try {
            ScenarioContractBaseline baseline =
                    objectMapper.readValue(json, ScenarioContractBaseline.class);
            if (baseline.contract() == null
                    || !baseline.contractFingerprint().equals(
                    baseline.contract().fingerprint(objectMapper))) {
                return Optional.empty();
            }
            return Optional.of(baseline);
        } catch (JsonProcessingException | IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private String serialize(Object value, String id) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to serialize Scenario authoring asset '" + id + "'.", failure);
        }
    }

    private static String firstNonBlank(String preferred, String fallback) {
        String preferredValue = normalized(preferred);
        return preferredValue.isBlank() ? normalized(fallback) : preferredValue;
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
