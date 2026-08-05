package com.leanowtech.bloge.gateway.authoring.scenario;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.contract.ContractDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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
    private static final String CREATE_CASE_INDEX = """
            CREATE TABLE IF NOT EXISTS visual_scenario_draft_set_cases (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                scenario_draft_set_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                case_order INT NOT NULL,
                scenario_id VARCHAR(512) NOT NULL,
                scenario_name VARCHAR(1024) NOT NULL,
                case_type VARCHAR(32) NOT NULL,
                tags_search VARCHAR(16384) NOT NULL,
                case_fingerprint VARCHAR(80) NOT NULL,
                scenario_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id, scenario_id
                ),
                UNIQUE (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id, case_order
                )
            )
            """;
    private static final String CREATE_CASE_INDEX_HEAD = """
            CREATE TABLE IF NOT EXISTS visual_scenario_draft_set_case_index_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(128) NOT NULL,
                region_id VARCHAR(128) NOT NULL,
                scenario_draft_set_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                draft_fingerprint VARCHAR(80) NOT NULL,
                classification VARCHAR(32) NOT NULL,
                case_count INT NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id
                )
            )
            """;
    private static final String CREATE_CASE_ORDER_INDEX = """
            CREATE INDEX IF NOT EXISTS visual_scenario_cases_order_idx
            ON visual_scenario_draft_set_cases (
                tenant_id, organization_id, project_id, environment_id, region_id,
                scenario_draft_set_id, revision, case_order
            )
            """;
    private static final String CREATE_CASE_NAME_INDEX = """
            CREATE INDEX IF NOT EXISTS visual_scenario_cases_name_idx
            ON visual_scenario_draft_set_cases (
                tenant_id, organization_id, project_id, environment_id, region_id,
                scenario_draft_set_id, revision, scenario_name, scenario_id
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
    public void init() {
        jdbc.execute(CREATE_CURRENT);
        jdbc.execute(CREATE_HISTORY);
        jdbc.execute(CREATE_CONTRACT_BASELINES);
        jdbc.execute(CREATE_CASE_INDEX);
        jdbc.execute(CREATE_CASE_INDEX_HEAD);
        jdbc.execute(CREATE_CASE_ORDER_INDEX);
        jdbc.execute(CREATE_CASE_NAME_INDEX);
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
        replaceCaseIndex(stored);
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

    @Override
    @Transactional
    public synchronized Optional<ScenarioTableHead> findTableHead(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId) {
        Optional<ScenarioTableHead> indexed = readTableHead(scope, scenarioDraftSetId);
        if (indexed.isPresent()) {
            return indexed;
        }
        Optional<StoredScenarioDraftSet> legacy = find(scope, scenarioDraftSetId);
        legacy.ifPresent(this::replaceCaseIndex);
        return legacy.map(ScenarioTableHead::from);
    }

    @Override
    @Transactional
    public synchronized Optional<ScenarioTablePage> queryPage(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            ScenarioTablePageQuery query,
            String queryFingerprint) {
        ScenarioTableHead observed = findTableHead(scope, scenarioDraftSetId).orElse(null);
        if (observed == null
                || observed.revision() != query.expectedRevision()
                || !observed.draftFingerprint().equals(query.expectedDraftFingerprint())) {
            return Optional.empty();
        }
        ensureCaseIndex(scope, scenarioDraftSetId, observed);
        int offset = decodeCursor(query.cursor(), queryFingerprint);
        StringBuilder predicate = new StringBuilder("""
                FROM visual_scenario_draft_set_cases
                WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                  AND environment_id = ? AND region_id = ?
                  AND scenario_draft_set_id = ? AND revision = ?
                """);
        List<Object> arguments = new ArrayList<>();
        addScopeArguments(arguments, scope, scenarioDraftSetId);
        arguments.add(query.expectedRevision());
        if (!query.query().isBlank()) {
            predicate.append(" AND (LOWER(scenario_id) LIKE ? ESCAPE '\\'"
                    + " OR LOWER(scenario_name) LIKE ? ESCAPE '\\'"
                    + " OR tags_search LIKE ? ESCAPE '\\')");
            String pattern = "%" + escapeLike(query.query().toLowerCase(Locale.ROOT)) + "%";
            arguments.add(pattern);
            arguments.add(pattern);
            arguments.add(pattern);
        }
        if (!query.caseTypes().isEmpty()) {
            predicate.append(" AND case_type IN (")
                    .append(String.join(",", java.util.Collections.nCopies(
                            query.caseTypes().size(), "?")))
                    .append(')');
            query.caseTypes().forEach(type -> arguments.add(type.name()));
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) " + predicate,
                Long.class,
                arguments.toArray());
        String order = switch (query.sortField()) {
            case CANONICAL -> "case_order";
            case NAME -> "LOWER(scenario_name), scenario_id";
            case TYPE -> "case_type, scenario_id";
        };
        String direction = query.sortDirection() == ScenarioTablePageQuery.SortDirection.ASC
                ? " ASC" : " DESC";
        String orderBy = " ORDER BY " + order.replace(", ", direction + ", ") + direction;
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(query.limit());
        pageArguments.add(offset);
        List<ScenarioTablePage.Row> rows = jdbc.query(
                "SELECT case_order, case_fingerprint, scenario_json " + predicate
                        + orderBy + " LIMIT ? OFFSET ?",
                (rs, rowNumber) -> readScenarioRow(
                        rs.getInt("case_order"),
                        rs.getString("case_fingerprint"),
                        rs.getString("scenario_json")),
                pageArguments.toArray());
        long matching = total == null ? 0 : total;
        int nextOffset = offset + rows.size();
        String nextCursor = nextOffset < matching
                ? encodeCursor(queryFingerprint, nextOffset) : "";
        if (!readTableHead(scope, scenarioDraftSetId).filter(observed::equals).isPresent()) {
            return Optional.empty();
        }
        return Optional.of(new ScenarioTablePage(
                "", observed.scenarioDraftSetId(), observed.revision(), observed.draftFingerprint(),
                queryFingerprint, matching, rows, nextCursor));
    }

    private Optional<ScenarioTableHead> readTableHead(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId) {
        return jdbc.query("""
                        SELECT revision, draft_fingerprint, classification, case_count
                        FROM visual_scenario_draft_set_case_index_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?
                        """,
                (rs, rowNumber) -> new ScenarioTableHead(
                        normalized(scenarioDraftSetId), rs.getLong("revision"),
                        rs.getString("draft_fingerprint"), rs.getString("classification"),
                        rs.getInt("case_count")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), normalized(scenarioDraftSetId))
                .stream().findFirst();
    }

    private void ensureCaseIndex(
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId,
            ScenarioTableHead head) {
        Integer count = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM visual_scenario_draft_set_cases
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ? AND revision = ?
                        """, Integer.class,
                scope.tenantId(), scope.organizationId(), scope.projectId(), scope.environment(),
                scope.region(), normalized(scenarioDraftSetId), head.revision());
        if (count == null || count != head.caseCount()) {
            StoredScenarioDraftSet canonical = find(scope, scenarioDraftSetId)
                    .filter(stored -> ScenarioTableHead.from(stored).equals(head))
                    .orElseThrow(() -> new IllegalStateException(
                            "Scenario Matrix index cannot be repaired from the observed canonical head"));
            replaceCaseIndex(canonical);
        }
    }

    private void replaceCaseIndex(StoredScenarioDraftSet stored) {
        ScenarioDraftSet draftSet = stored.draftSet();
        ScenarioDraftSet.EnterpriseScope scope = draftSet.scope();
        jdbc.update("""
                        DELETE FROM visual_scenario_draft_set_cases
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), draftSet.scenarioDraftSetId());
        List<Object[]> rows = new ArrayList<>(draftSet.scenarios().size());
        for (int index = 0; index < draftSet.scenarios().size(); index++) {
            ScenarioDraftSet.ScenarioDraft scenario = draftSet.scenarios().get(index);
            String caseFingerprint = VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, scenario, MAX_FINGERPRINT_BYTES);
            rows.add(new Object[] {
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environment(), scope.region(), draftSet.scenarioDraftSetId(),
                    draftSet.revision(), index, scenario.scenarioId(), scenario.name(),
                    scenario.caseType().name(), String.join("\u001f", scenario.tags())
                            .toLowerCase(Locale.ROOT), caseFingerprint,
                    serialize(scenario, scenario.scenarioId())
            });
        }
        jdbc.batchUpdate("""
                INSERT INTO visual_scenario_draft_set_cases (
                    tenant_id, organization_id, project_id, environment_id, region_id,
                    scenario_draft_set_id, revision, case_order, scenario_id, scenario_name,
                    case_type, tags_search, case_fingerprint, scenario_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
        jdbc.update("""
                        MERGE INTO visual_scenario_draft_set_case_index_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            scenario_draft_set_id, revision, draft_fingerprint,
                            classification, case_count
                        ) KEY (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            scenario_draft_set_id
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), draftSet.scenarioDraftSetId(),
                stored.revision(), stored.fingerprint(),
                draftSet.metadata().classification(), draftSet.scenarios().size());
    }

    private ScenarioTablePage.Row readScenarioRow(
            int canonicalIndex,
            String fingerprint,
            String json) {
        try {
            ScenarioDraftSet.ScenarioDraft scenario =
                    objectMapper.readValue(json, ScenarioDraftSet.ScenarioDraft.class);
            String expected = VisualBundleFingerprint.fromCanonicalValue(
                    objectMapper, scenario, MAX_FINGERPRINT_BYTES);
            if (!expected.equals(fingerprint)) {
                throw new IllegalStateException("Scenario Matrix row fingerprint verification failed");
            }
            return new ScenarioTablePage.Row(canonicalIndex, fingerprint, scenario);
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Scenario Matrix row could not be decoded", invalid);
        }
    }

    private int decodeCursor(String cursor, String queryFingerprint) {
        if (cursor == null || cursor.isBlank()) {
            return 0;
        }
        try {
            byte[] json = Base64.getUrlDecoder().decode(cursor);
            CursorEnvelope envelope = objectMapper.readValue(json, CursorEnvelope.class);
            if (!CursorEnvelope.SCHEMA_VERSION.equals(envelope.schemaVersion())
                    || !queryFingerprint.equals(envelope.queryFingerprint())
                    || envelope.offset() < 0 || envelope.offset() > 10_000) {
                throw new IllegalArgumentException("Scenario Matrix cursor is stale or invalid");
            }
            return envelope.offset();
        } catch (IOException | IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Scenario Matrix cursor is stale or invalid", invalid);
        }
    }

    private String encodeCursor(String queryFingerprint, int offset) {
        try {
            byte[] json = objectMapper.writeValueAsBytes(
                    new CursorEnvelope(CursorEnvelope.SCHEMA_VERSION, queryFingerprint, offset));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (JsonProcessingException impossible) {
            throw new IllegalStateException("Scenario Matrix cursor could not be encoded", impossible);
        }
    }

    private static void addScopeArguments(
            List<Object> target,
            ScenarioDraftSet.EnterpriseScope scope,
            String scenarioDraftSetId) {
        target.add(scope.tenantId());
        target.add(scope.organizationId());
        target.add(scope.projectId());
        target.add(scope.environment());
        target.add(scope.region());
        target.add(normalized(scenarioDraftSetId));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record CursorEnvelope(String schemaVersion, String queryFingerprint, int offset) {
        private static final String SCHEMA_VERSION = "bloge.scenarioTableCursor.v1";
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
