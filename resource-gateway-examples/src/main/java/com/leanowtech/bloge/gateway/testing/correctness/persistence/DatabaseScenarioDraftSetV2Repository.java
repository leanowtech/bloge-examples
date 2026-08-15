package com.leanowtech.bloge.gateway.testing.correctness.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.AuditMetadata;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactObligationRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactTargetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalKind;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.PrincipalRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ControlledDependencyV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.FixtureVariantRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioDraftV2;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ScenarioLifecycle;
import com.leanowtech.bloge.gateway.testing.correctness.domain.ScenarioDraftSetV2.ValueSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** PostgreSQL/H2 Scenario v2 authority with bounded Matrix and obligation projections. */
public final class DatabaseScenarioDraftSetV2Repository
        implements ScenarioDraftSetV2Repository {

    private static final String HEAD_TABLE = "rg_scenario_draft_set_v2_heads";
    private static final String REVISION_TABLE = "rg_scenario_draft_set_v2_revisions";
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    public DatabaseScenarioDraftSetV2Repository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this(jdbc, mapper, Clock.systemUTC());
    }

    public DatabaseScenarioDraftSetV2Repository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            Clock clock
    ) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<StoredScenarioDraftSetV2> findHead(
            EnterpriseScope scope,
            String scenarioDraftSetId
    ) {
        return queryOne(HEAD_TABLE, exactScope(scope), exactId(scenarioDraftSetId), 0);
    }

    @Override
    public Optional<StoredScenarioDraftSetV2> findRevision(
            EnterpriseScope scope,
            String scenarioDraftSetId,
            long revision
    ) {
        if (revision < 1) return Optional.empty();
        return queryOne(
                REVISION_TABLE, exactScope(scope), exactId(scenarioDraftSetId), revision);
    }

    @Override
    public List<StoredScenarioDraftSetV2> revisions(
            EnterpriseScope scope,
            String scenarioDraftSetId
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        String exactId = exactId(scenarioDraftSetId);
        return jdbc.query("""
                        SELECT * FROM rg_scenario_draft_set_v2_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?
                        ORDER BY revision DESC
                        """,
                (result, row) -> readAndVerify(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredScenarioDraftSetV2> saveIfRevision(
            long expectedRevision,
            ScenarioDraftSetV2 candidate,
            PrincipalRef actor
    ) {
        if (candidate == null || actor == null || expectedRevision < 0
                || candidate.revision() != expectedRevision) {
            throw new IllegalArgumentException(
                    "Scenario v2, actor, and matching expected revision are required");
        }
        EnterpriseScope scope = exactScope(candidate.scope());
        String id = exactId(candidate.scenarioDraftSetId());
        StoredScenarioDraftSetV2 current = findHead(scope, id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null
                        && current.scenarioDraftSet().revision() != expectedRevision)) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        AuditMetadata metadata = current == null
                ? new AuditMetadata(now, now, actor, actor)
                : new AuditMetadata(
                        current.scenarioDraftSet().metadata().createdAt(), now,
                        current.scenarioDraftSet().metadata().createdBy(), actor);
        ScenarioDraftSetV2 persisted = candidate.persistedAs(expectedRevision + 1, metadata);
        StoredScenarioDraftSetV2 stored = StoredScenarioDraftSetV2.verified(mapper, persisted);
        String json = serialize(stored);

        if (current == null) {
            try {
                insertHead(stored, json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else if (updateHead(stored, json, expectedRevision) == 0) {
            return Optional.empty();
        }
        insertRevision(stored, json);
        insertCaseIndexes(stored);
        insertOutbox(stored);
        return Optional.of(stored);
    }

    @Override
    public ScenarioCasePage pageByTarget(
            EnterpriseScope scope,
            ExactTargetRef target,
            String cursor,
            int limit
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        ExactTargetRef exactTarget = Objects.requireNonNull(target, "target");
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Scenario Case page limit must be 1..100");
        }
        MatrixCursor after = decodeCursor(cursor);
        long total = jdbc.queryForObject("""
                        SELECT COUNT(*)
                        FROM rg_scenario_case_v2_index i
                        JOIN rg_scenario_draft_set_v2_heads h
                          ON h.tenant_id = i.tenant_id
                         AND h.organization_id = i.organization_id
                         AND h.project_id = i.project_id
                         AND h.environment_id = i.environment_id
                         AND h.region_id = i.region_id
                         AND h.scenario_draft_set_id = i.scenario_draft_set_id
                         AND h.revision = i.scenario_draft_set_revision
                        WHERE h.tenant_id = ? AND h.organization_id = ? AND h.project_id = ?
                          AND h.environment_id = ? AND h.region_id = ?
                          AND h.target_kind = ? AND h.target_id = ?
                          AND h.target_revision = ? AND h.target_fingerprint = ?
                        """, Long.class,
                targetArgs(exactScope, exactTarget));
        List<ExactAssetRef> setRefs = jdbc.query("""
                        SELECT scenario_draft_set_id, revision, fingerprint
                        FROM rg_scenario_draft_set_v2_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND target_kind = ? AND target_id = ?
                          AND target_revision = ? AND target_fingerprint = ?
                        ORDER BY scenario_draft_set_id
                        """, (result, row) -> new ExactAssetRef(
                        "SCENARIO_DRAFT_SET", result.getString("scenario_draft_set_id"),
                        result.getLong("revision"), result.getString("fingerprint")),
                targetArgs(exactScope, exactTarget));
        Object[] args = append(
                targetArgs(exactScope, exactTarget),
                after.scenarioDraftSetId(), after.scenarioDraftSetId(), after.caseId(),
                limit + 1);
        List<ScenarioCaseSummary> queried = jdbc.query("""
                        SELECT i.*, h.fingerprint AS scenario_draft_set_fingerprint
                        FROM rg_scenario_case_v2_index i
                        JOIN rg_scenario_draft_set_v2_heads h
                          ON h.tenant_id = i.tenant_id
                         AND h.organization_id = i.organization_id
                         AND h.project_id = i.project_id
                         AND h.environment_id = i.environment_id
                         AND h.region_id = i.region_id
                         AND h.scenario_draft_set_id = i.scenario_draft_set_id
                         AND h.revision = i.scenario_draft_set_revision
                        WHERE h.tenant_id = ? AND h.organization_id = ? AND h.project_id = ?
                          AND h.environment_id = ? AND h.region_id = ?
                          AND h.target_kind = ? AND h.target_id = ?
                          AND h.target_revision = ? AND h.target_fingerprint = ?
                          AND (i.scenario_draft_set_id > ?
                            OR (i.scenario_draft_set_id = ? AND i.case_id > ?))
                        ORDER BY i.scenario_draft_set_id, i.case_id
                        LIMIT ?
                        """, (result, row) -> readSummary(result), args);
        boolean more = queried.size() > limit;
        List<ScenarioCaseSummary> rows = more
                ? List.copyOf(queried.subList(0, limit)) : List.copyOf(queried);
        String next = more ? encodeCursor(
                rows.getLast().scenarioDraftSetRef().id(), rows.getLast().caseId()) : "";
        return new ScenarioCasePage(total, rows, next, setRefs);
    }

    @Override
    public Set<String> fulfilledObligationIds(
            EnterpriseScope scope,
            ExactTargetRef target,
            ExactAssetRef inventoryRef
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        ExactTargetRef exactTarget = Objects.requireNonNull(target, "target");
        ExactAssetRef exactInventory = Objects.requireNonNull(inventoryRef, "inventoryRef");
        if (!"INVENTORY".equals(exactInventory.kind())) {
            throw new IllegalArgumentException("An exact INVENTORY ref is required");
        }
        List<String> ids = jdbc.queryForList("""
                        SELECT DISTINCT r.obligation_id
                        FROM rg_scenario_case_obligation_ref_index r
                        JOIN rg_scenario_draft_set_v2_heads h
                          ON h.tenant_id = r.tenant_id
                         AND h.organization_id = r.organization_id
                         AND h.project_id = r.project_id
                         AND h.environment_id = r.environment_id
                         AND h.region_id = r.region_id
                         AND h.scenario_draft_set_id = r.scenario_draft_set_id
                         AND h.revision = r.scenario_draft_set_revision
                        WHERE h.tenant_id = ? AND h.organization_id = ? AND h.project_id = ?
                          AND h.environment_id = ? AND h.region_id = ?
                          AND h.target_kind = ? AND h.target_id = ?
                          AND h.target_revision = ? AND h.target_fingerprint = ?
                          AND r.inventory_id = ? AND r.inventory_revision = ?
                          AND r.inventory_fingerprint = ? AND r.case_lifecycle = 'CANONICAL'
                        ORDER BY r.obligation_id
                        """, String.class,
                append(targetArgs(exactScope, exactTarget), exactInventory.id(),
                        exactInventory.revision(), exactInventory.fingerprint()));
        return Set.copyOf(new LinkedHashSet<>(ids));
    }

    @Override
    public List<FixtureReferenceUsage> fixtureUsagesByTarget(
            EnterpriseScope scope,
            ExactTargetRef target
    ) {
        EnterpriseScope exactScope = exactScope(scope);
        ExactTargetRef exactTarget = Objects.requireNonNull(target, "target");
        List<StoredScenarioDraftSetV2> heads = jdbc.query("""
                        SELECT * FROM rg_scenario_draft_set_v2_heads
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND target_kind = ? AND target_id = ?
                          AND target_revision = ? AND target_fingerprint = ?
                        ORDER BY scenario_draft_set_id
                        """,
                (result, row) -> readAndVerify(
                        result, exactScope, result.getString("scenario_draft_set_id")),
                targetArgs(exactScope, exactTarget)).stream()
                .flatMap(Optional::stream)
                .toList();
        return heads.stream()
                .flatMap(stored -> fixtureUsages(stored).stream())
                .distinct()
                .sorted(Comparator
                        .comparing((FixtureReferenceUsage usage) ->
                                usage.fixtureAssetRef().id())
                        .thenComparingLong(usage -> usage.fixtureAssetRef().revision())
                        .thenComparing(usage -> usage.fixtureAssetRef().fingerprint())
                        .thenComparing(usage -> usage.scenarioDraftSetRef().id()))
                .toList();
    }

    private static List<FixtureReferenceUsage> fixtureUsages(
            StoredScenarioDraftSetV2 stored
    ) {
        ScenarioDraftSetV2 draftSet = stored.scenarioDraftSet();
        ExactAssetRef consumer = new ExactAssetRef(
                "SCENARIO_DRAFT_SET", draftSet.scenarioDraftSetId(), draftSet.revision(),
                stored.scenarioDraftSetFingerprint());
        Set<ExactAssetRef> refs = new LinkedHashSet<>();
        draftSet.scenarios().stream()
                .filter(scenario -> scenario.lifecycle() == ScenarioLifecycle.CANONICAL)
                .forEach(scenario -> {
                    scenario.sourceRefs().stream()
                            .filter(DatabaseScenarioDraftSetV2Repository::isFixtureRef)
                            .forEach(refs::add);
                    addFixtureRef(refs, scenario.given().input());
                    for (ControlledDependencyV2 dependency : scenario.dependencies()) {
                        addFixtureRef(refs, dependency.behavior().value());
                    }
                });
        return refs.stream()
                .map(ref -> new FixtureReferenceUsage(consumer, ref))
                .toList();
    }

    private static void addFixtureRef(Set<ExactAssetRef> refs, ValueSource value) {
        if (value instanceof FixtureVariantRef fixture) {
            refs.add(fixture.fixtureAssetRef());
        }
    }

    private static boolean isFixtureRef(ExactAssetRef ref) {
        return ref != null && "FIXTURE_ASSET".equals(ref.kind());
    }

    private Optional<StoredScenarioDraftSetV2> queryOne(
            String table,
            EnterpriseScope scope,
            String id,
            long revision
    ) {
        String revisionClause = revision > 0 ? " AND revision = ?" : "";
        Object[] args = revision > 0
                ? append(scopeArgs(scope, id), revision) : scopeArgs(scope, id);
        return jdbc.query("""
                        SELECT * FROM %s
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ?%s
                        """.formatted(table, revisionClause),
                (result, row) -> readAndVerify(result, scope, id), args)
                .stream().flatMap(Optional::stream).findFirst();
    }

    private Optional<StoredScenarioDraftSetV2> readAndVerify(
            ResultSet result,
            EnterpriseScope scope,
            String id
    ) throws SQLException {
        try {
            StoredScenarioDraftSetV2 stored = mapper.readValue(
                    result.getString("canonical_json"), StoredScenarioDraftSetV2.class);
            ScenarioDraftSetV2 value = stored.scenarioDraftSet();
            boolean valid = value.scope().equals(scope)
                    && value.scenarioDraftSetId().equals(id)
                    && value.revision() == result.getLong("revision")
                    && stored.scenarioDraftSetFingerprint().equals(
                            result.getString("fingerprint"))
                    && stored.scenarioDraftSetFingerprint().equals(
                            CorrectnessProtocolFingerprint.fingerprint(mapper, value))
                    && value.target().kind().name().equals(result.getString("target_kind"))
                    && value.target().id().equals(result.getString("target_id"))
                    && value.target().revision() == result.getLong("target_revision")
                    && value.target().fingerprint().equals(
                            result.getString("target_fingerprint"))
                    && value.contractRef().kind().equals(result.getString("contract_kind"))
                    && value.contractRef().id().equals(result.getString("contract_id"))
                    && value.contractRef().revision() == result.getLong("contract_revision")
                    && value.contractRef().fingerprint().equals(
                            result.getString("contract_fingerprint"))
                    && caseIndexesMatch(stored);
            if (!valid) {
                throw new IllegalStateException("Stored Scenario v2 integrity check failed");
            }
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Scenario v2 revision", failure);
        }
    }

    private boolean caseIndexesMatch(StoredScenarioDraftSetV2 stored) {
        ScenarioDraftSetV2 value = stored.scenarioDraftSet();
        List<IndexedCase> actual = jdbc.query("""
                        SELECT * FROM rg_scenario_case_v2_index
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ? AND scenario_draft_set_revision = ?
                        ORDER BY case_id
                        """, (result, row) -> readIndexedCase(result),
                value.scope().tenantId(), value.scope().organizationId(),
                value.scope().projectId(), value.scope().environment(), value.scope().region(),
                value.scenarioDraftSetId(), value.revision());
        List<IndexedCase> expected = value.scenarios().stream()
                .map(this::indexed).sorted(java.util.Comparator.comparing(IndexedCase::caseId))
                .toList();
        return actual.equals(expected);
    }

    private IndexedCase readIndexedCase(ResultSet result) throws SQLException {
        try {
            ScenarioDraftV2 scenario = mapper.readValue(
                    result.getString("canonical_json"), ScenarioDraftV2.class);
            IndexedCase indexed = indexed(scenario);
            boolean columnsMatch = indexed.caseId().equals(result.getString("case_id"))
                    && indexed.caseFingerprint().equals(result.getString("case_fingerprint"))
                    && indexed.lifecycle().equals(result.getString("lifecycle"))
                    && indexed.risk().equals(result.getString("risk"))
                    && indexed.ownerId().equals(result.getString("owner_id"))
                    && indexed.ownerKind().equals(result.getString("owner_kind"))
                    && indexed.caseType().equals(result.getString("case_type"))
                    && indexed.name().equals(result.getString("case_name"))
                    && indexed.businessIntent().equals(result.getString("business_intent"))
                    && indexed.obligationCount() == result.getInt("obligation_count")
                    && indexed.oracleCount() == result.getInt("oracle_count")
                    && indexed.assertionSetCount() == result.getInt("assertion_set_count")
                    && indexed.dependencyCount() == result.getInt("dependency_count")
                    && indexed.reviewStatus().equals(result.getString("review_status"))
                    && indexed.tags().equals(decodeTags(result.getString("tags_json")));
            if (!columnsMatch) {
                throw new IllegalStateException("Stored Scenario Case index integrity check failed");
            }
            return indexed;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Scenario Case index", failure);
        }
    }

    private ScenarioCaseSummary readSummary(ResultSet result) throws SQLException {
        return new ScenarioCaseSummary(
                new ExactAssetRef(
                        "SCENARIO_DRAFT_SET", result.getString("scenario_draft_set_id"),
                        result.getLong("scenario_draft_set_revision"),
                        result.getString("scenario_draft_set_fingerprint")),
                result.getString("case_id"), result.getString("case_fingerprint"),
                result.getString("case_name"), result.getString("business_intent"),
                result.getString("case_type"),
                com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol
                        .RiskLevel.valueOf(result.getString("risk")),
                new PrincipalRef(
                        result.getString("owner_id"),
                        PrincipalKind.valueOf(result.getString("owner_kind")), ""),
                result.getString("lifecycle"), result.getInt("obligation_count"),
                result.getInt("oracle_count"), result.getInt("assertion_set_count"),
                result.getInt("dependency_count"), result.getString("review_status"),
                decodeTags(result.getString("tags_json")));
    }

    private void insertHead(StoredScenarioDraftSetV2 stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_scenario_draft_set_v2_heads (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            scenario_draft_set_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, contract_kind, contract_id,
                            contract_revision, contract_fingerprint, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private int updateHead(
            StoredScenarioDraftSetV2 stored,
            String json,
            long expectedRevision
    ) {
        ScenarioDraftSetV2 value = stored.scenarioDraftSet();
        return jdbc.update("""
                        UPDATE rg_scenario_draft_set_v2_heads
                        SET revision = ?, fingerprint = ?, target_kind = ?, target_id = ?,
                            target_revision = ?, target_fingerprint = ?, contract_kind = ?,
                            contract_id = ?, contract_revision = ?, contract_fingerprint = ?,
                            canonical_json = ?, updated_at = ?, updated_by = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ?
                          AND scenario_draft_set_id = ? AND revision = ?
                        """,
                value.revision(), stored.scenarioDraftSetFingerprint(),
                value.target().kind().name(), value.target().id(), value.target().revision(),
                value.target().fingerprint(), value.contractRef().kind(),
                value.contractRef().id(), value.contractRef().revision(),
                value.contractRef().fingerprint(), json, value.metadata().updatedAt(),
                value.metadata().updatedBy().id(), value.scope().tenantId(),
                value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(),
                value.scenarioDraftSetId(), expectedRevision);
    }

    private void insertRevision(StoredScenarioDraftSetV2 stored, String json) {
        jdbc.update("""
                        INSERT INTO rg_scenario_draft_set_v2_revisions (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            scenario_draft_set_id, revision, fingerprint, target_kind, target_id,
                            target_revision, target_fingerprint, contract_kind, contract_id,
                            contract_revision, contract_fingerprint, canonical_json,
                            created_at, updated_at, updated_by
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, rowArgs(stored, json));
    }

    private void insertCaseIndexes(StoredScenarioDraftSetV2 stored) {
        ScenarioDraftSetV2 set = stored.scenarioDraftSet();
        for (ScenarioDraftV2 scenario : set.scenarios()) {
            IndexedCase index = indexed(scenario);
            jdbc.update("""
                            INSERT INTO rg_scenario_case_v2_index (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                scenario_draft_set_id, scenario_draft_set_revision, case_id,
                                case_fingerprint, lifecycle, risk, owner_id, owner_kind, case_type,
                                case_name, business_intent, obligation_count, oracle_count,
                                assertion_set_count, dependency_count, review_status, tags_json,
                                canonical_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                                      ?, ?, ?, ?)
                            """,
                    set.scope().tenantId(), set.scope().organizationId(), set.scope().projectId(),
                    set.scope().environment(), set.scope().region(), set.scenarioDraftSetId(),
                    set.revision(), index.caseId(), index.caseFingerprint(), index.lifecycle(),
                    index.risk(), index.ownerId(), index.ownerKind(), index.caseType(), index.name(),
                    index.businessIntent(), index.obligationCount(), index.oracleCount(),
                    index.assertionSetCount(), index.dependencyCount(), index.reviewStatus(),
                    encode(index.tags()), encode(scenario));
            for (ExactObligationRef obligation : scenario.obligationRefs()) {
                ExactAssetRef inventory = obligation.inventoryRef();
                jdbc.update("""
                                INSERT INTO rg_scenario_case_obligation_ref_index (
                                    tenant_id, organization_id, project_id, environment_id,
                                    region_id, scenario_draft_set_id,
                                    scenario_draft_set_revision, case_id, case_lifecycle,
                                    inventory_id, inventory_revision, inventory_fingerprint,
                                    obligation_id, obligation_fingerprint
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        set.scope().tenantId(), set.scope().organizationId(),
                        set.scope().projectId(), set.scope().environment(), set.scope().region(),
                        set.scenarioDraftSetId(), set.revision(), scenario.scenarioId(),
                        scenario.lifecycle().name(), inventory.id(), inventory.revision(),
                        inventory.fingerprint(), obligation.obligationId(),
                        obligation.obligationFingerprint());
            }
        }
    }

    private void insertOutbox(StoredScenarioDraftSetV2 stored) {
        ScenarioDraftSetV2 value = stored.scenarioDraftSet();
        String eventId = UUID.randomUUID().toString();
        ScenarioDraftSetV2Changed event = new ScenarioDraftSetV2Changed(
                "", eventId, value.scope(),
                new ExactAssetRef(
                        "SCENARIO_DRAFT_SET", value.scenarioDraftSetId(), value.revision(),
                        stored.scenarioDraftSetFingerprint()),
                value.target(), value.contractRef(), value.scenarios().size(),
                count(value, ScenarioLifecycle.EXPLORATORY),
                count(value, ScenarioLifecycle.REVIEW_READY),
                count(value, ScenarioLifecycle.CANONICAL),
                count(value, ScenarioLifecycle.RETIRED),
                value.metadata().updatedBy().id(), value.metadata().updatedAt());
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), eventId, "SCENARIO_DRAFT_SET",
                value.scenarioDraftSetId(), value.revision(), ScenarioDraftSetV2Changed.SCHEMA_VERSION,
                encode(event), value.metadata().updatedAt());
    }

    private IndexedCase indexed(ScenarioDraftV2 value) {
        return new IndexedCase(
                value.scenarioId(), CorrectnessProtocolFingerprint.scenarioFingerprint(mapper, value),
                value.lifecycle().name(), value.risk().name(), value.owner().id(),
                value.owner().kind().name(), value.caseType().name(), value.name(),
                value.businessIntent(), value.obligationRefs().size(), value.oracleRefs().size(),
                value.assertionSetRefs().size(), value.dependencies().size(),
                value.review().status().name(), value.tags());
    }

    private Object[] rowArgs(StoredScenarioDraftSetV2 stored, String json) {
        ScenarioDraftSetV2 value = stored.scenarioDraftSet();
        return new Object[]{
                value.scope().tenantId(), value.scope().organizationId(), value.scope().projectId(),
                value.scope().environment(), value.scope().region(), value.scenarioDraftSetId(),
                value.revision(), stored.scenarioDraftSetFingerprint(),
                value.target().kind().name(), value.target().id(), value.target().revision(),
                value.target().fingerprint(), value.contractRef().kind(),
                value.contractRef().id(), value.contractRef().revision(),
                value.contractRef().fingerprint(), json, value.metadata().createdAt(),
                value.metadata().updatedAt(), value.metadata().updatedBy().id()
        };
    }

    private List<String> decodeTags(String json) {
        try {
            return mapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to decode Scenario Case tags", failure);
        }
    }

    private String serialize(StoredScenarioDraftSetV2 stored) {
        return encode(stored);
    }

    private String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode Scenario v2 value", failure);
        }
    }

    private static int count(ScenarioDraftSetV2 value, ScenarioLifecycle lifecycle) {
        return (int) value.scenarios().stream()
                .filter(scenario -> scenario.lifecycle() == lifecycle).count();
    }

    private static String encodeCursor(String scenarioDraftSetId, String caseId) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return "v2."
                + encoder.encodeToString(scenarioDraftSetId.getBytes(StandardCharsets.UTF_8))
                + "." + encoder.encodeToString(caseId.getBytes(StandardCharsets.UTF_8));
    }

    private static MatrixCursor decodeCursor(String cursor) {
        String normalized = cursor == null ? "" : cursor.trim();
        if (normalized.isEmpty()) return new MatrixCursor("", "");
        if (!normalized.startsWith("v2.") || normalized.length() > 1536) {
            throw new IllegalArgumentException("Invalid Scenario Case cursor");
        }
        try {
            String[] parts = normalized.split("\\.", -1);
            if (parts.length != 3) {
                throw new IllegalArgumentException("Invalid Scenario Case cursor");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            String setId = new String(decoder.decode(parts[1]), StandardCharsets.UTF_8);
            String caseId = new String(decoder.decode(parts[2]), StandardCharsets.UTF_8);
            if (setId.isBlank() || setId.length() > 512
                    || caseId.isBlank() || caseId.length() > 512) {
                throw new IllegalArgumentException("Invalid Scenario Case cursor");
            }
            return new MatrixCursor(setId, caseId);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid Scenario Case cursor", failure);
        }
    }

    private static EnterpriseScope exactScope(EnterpriseScope scope) {
        return Objects.requireNonNull(scope, "scope");
    }

    private static String exactId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("scenarioDraftSetId is required");
        }
        return normalized;
    }

    private static Object[] scopeArgs(EnterpriseScope scope, String id) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), id};
    }

    private static Object[] targetArgs(EnterpriseScope scope, ExactTargetRef target) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), target.kind().name(), target.id(),
                target.revision(), target.fingerprint()};
    }

    private static Object[] append(Object[] source, Object... values) {
        Object[] result = Arrays.copyOf(source, source.length + values.length);
        System.arraycopy(values, 0, result, source.length, values.length);
        return result;
    }

    private record MatrixCursor(String scenarioDraftSetId, String caseId) {}

    private record IndexedCase(
            String caseId,
            String caseFingerprint,
            String lifecycle,
            String risk,
            String ownerId,
            String ownerKind,
            String caseType,
            String name,
            String businessIntent,
            int obligationCount,
            int oracleCount,
            int assertionSetCount,
            int dependencyCount,
            String reviewStatus,
            List<String> tags
    ) {}
}
