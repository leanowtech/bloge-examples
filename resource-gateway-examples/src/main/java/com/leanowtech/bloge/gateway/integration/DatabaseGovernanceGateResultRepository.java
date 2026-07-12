package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;
import com.leanowtech.bloge.gateway.visual.persistence.TransactionCommitActions;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** H2-backed immutable repository for ANEKE governance feedback. */
public class DatabaseGovernanceGateResultRepository implements GovernanceGateResultRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS governance_gate_results (
                gate_result_id VARCHAR(255) PRIMARY KEY,
                draft_id VARCHAR(255) NOT NULL,
                produced_at VARCHAR(64) NOT NULL,
                result_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = "SELECT gate_result_id, result_json FROM governance_gate_results";
    private static final String INSERT = """
            INSERT INTO governance_gate_results (gate_result_id, draft_id, produced_at, result_json)
            VALUES (?, ?, ?, ?)
            """;
    private static final String SELECT_ONE = """
            SELECT result_json FROM governance_gate_results WHERE gate_result_id = ?
            """;

    private final ConcurrentHashMap<String, GovernanceGateResult> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualChangeEventPublisher changePublisher;

    public DatabaseGovernanceGateResultRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, VisualChangeEventPublisher.unavailable());
    }

    public DatabaseGovernanceGateResultRepository(JdbcTemplate jdbc,
                                                  ObjectMapper objectMapper,
                                                  VisualChangeEventPublisher changePublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable() : changePublisher;
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        cache.clear();
        jdbc.query(SELECT_ALL, (rs, rowNum) -> {
            try {
                GovernanceGateResult result = objectMapper.readValue(rs.getString("result_json"),
                        GovernanceGateResult.class);
                cache.put(result.gateResultId(), result);
            } catch (JsonProcessingException ignored) {
                // Corrupt feedback is excluded rather than presented as an authoritative gate result.
            }
            return null;
        });
    }

    @Override
    public Optional<GovernanceGateResult> find(String gateResultId) {
        return Optional.ofNullable(cache.get(gateResultId));
    }

    @Override
    public List<GovernanceGateResult> forDraft(String draftId) {
        return cache.values().stream()
                .filter(result -> result.target().draftId().equals(draftId))
                .sorted(Comparator.comparing(GovernanceGateResult::producedAt).reversed()
                        .thenComparing(GovernanceGateResult::gateResultId))
                .toList();
    }

    @Override
    @Transactional
    public GovernanceGateResult create(GovernanceGateResult result) {
        GovernanceGateResult cached = cache.get(result.gateResultId());
        if (cached != null) return sameOrConflict(cached, result);
        String json;
        try {
            json = objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize governance gate result", exception);
        }
        try {
            jdbc.update(INSERT, result.gateResultId(), result.target().draftId(), result.producedAt().toString(), json);
        } catch (DuplicateKeyException duplicate) {
            GovernanceGateResult existing = load(result.gateResultId()).orElseThrow(() -> duplicate);
            return sameOrConflict(existing, result);
        }
        changePublisher.publish(new VisualChangeFact(
                "GOVERNANCE_GATE_RESULT_RECEIVED",
                scope(result.target().tenantId()), scope(result.target().namespace()),
                scope(result.target().environment()),
                new VisualChangeFact.Aggregate("GOVERNANCE_GATE_RESULT", result.gateResultId(),
                        result.target().revision(), result.resultFingerprint()),
                "/api/integration/drafts/" + result.target().draftId() + "/gate-result", result.status()));
        TransactionCommitActions.afterCommitOrNow(() -> cache.put(result.gateResultId(), result));
        return result;
    }

    private Optional<GovernanceGateResult> load(String gateResultId) {
        return jdbc.query(SELECT_ONE, (rs, rowNum) -> {
            try {
                return objectMapper.readValue(rs.getString("result_json"), GovernanceGateResult.class);
            } catch (JsonProcessingException exception) {
                throw new IllegalStateException("Unable to deserialize governance gate result", exception);
            }
        }, gateResultId).stream().findFirst();
    }

    private static GovernanceGateResult sameOrConflict(GovernanceGateResult existing,
                                                       GovernanceGateResult requested) {
        if (existing.resultFingerprint().equals(requested.resultFingerprint())) return existing;
        throw new IllegalArgumentException("Governance gate result already exists with different content: "
                + requested.gateResultId());
    }

    private static String scope(String value) {
        return value == null || value.isBlank() ? VisualChangeFact.GLOBAL_SCOPE : value;
    }
}
