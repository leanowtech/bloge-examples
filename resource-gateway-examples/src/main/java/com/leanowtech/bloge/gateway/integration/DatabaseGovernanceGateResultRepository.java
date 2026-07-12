package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;

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

    private final ConcurrentHashMap<String, GovernanceGateResult> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseGovernanceGateResultRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
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
    public synchronized GovernanceGateResult create(GovernanceGateResult result) {
        if (cache.containsKey(result.gateResultId())) {
            throw new IllegalArgumentException("Governance gate result already exists: " + result.gateResultId());
        }
        try {
            jdbc.update(INSERT, result.gateResultId(), result.target().draftId(), result.producedAt().toString(),
                    objectMapper.writeValueAsString(result));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize governance gate result", exception);
        }
        cache.put(result.gateResultId(), result);
        return result;
    }
}
