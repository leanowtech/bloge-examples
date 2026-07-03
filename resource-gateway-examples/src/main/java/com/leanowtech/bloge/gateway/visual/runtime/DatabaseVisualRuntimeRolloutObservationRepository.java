package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed repository for runtime rollout observation facts.
 */
public class DatabaseVisualRuntimeRolloutObservationRepository
        implements VisualRuntimeRolloutObservationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseVisualRuntimeRolloutObservationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_runtime_rollout_observations (
                observation_id VARCHAR(255) PRIMARY KEY,
                activation_id VARCHAR(255) NOT NULL,
                binding_id VARCHAR(255) NOT NULL,
                operator_ref VARCHAR(255) NOT NULL,
                state VARCHAR(64) NOT NULL,
                observed_at VARCHAR(64) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                observation_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT observation_id, observation_json
            FROM visual_runtime_rollout_observations
            """;
    private static final String INSERT = """
            INSERT INTO visual_runtime_rollout_observations
                (observation_id, activation_id, binding_id, operator_ref, state,
                 observed_at, created_at, updated_at, observation_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualRuntimeRolloutObservation> cache =
            new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualRuntimeRolloutObservationRepository(JdbcTemplate jdbc,
                                                             ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Initializes table and cache.
     */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        cache.clear();
        jdbc.query(SELECT_ALL, (rs, rowNum) -> {
            String observationId = rs.getString("observation_id");
            String json = rs.getString("observation_json");
            try {
                VisualRuntimeRolloutObservation observation =
                        objectMapper.readValue(json, VisualRuntimeRolloutObservation.class);
                cache.put(observationId, observation);
                log.info("Loaded visual runtime rollout observation from DB: {}", observationId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual runtime rollout observation '{}': {}",
                        observationId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualRuntimeRolloutObservationRepository initialized with {} observation(s)",
                cache.size());
    }

    @Override
    public Collection<VisualRuntimeRolloutObservation> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeRolloutObservation::observedAt)
                        .reversed()
                        .thenComparing(VisualRuntimeRolloutObservation::observationId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeRolloutObservation> find(String observationId) {
        return Optional.ofNullable(cache.get(observationId));
    }

    @Override
    public VisualRuntimeRolloutObservation create(VisualRuntimeRolloutObservation observation) {
        if (observation == null) {
            throw new IllegalArgumentException("Runtime rollout observation is required.");
        }
        String observationId = observation.observationId().isBlank()
                ? UUID.randomUUID().toString()
                : observation.observationId();
        if (cache.containsKey(observationId)) {
            throw new IllegalArgumentException("Runtime rollout observation already exists: " + observationId);
        }
        Instant now = Instant.now();
        VisualRuntimeRolloutObservation stored = observation.withIdentity(observationId, 1, now, now);
        persist(stored);
        VisualRuntimeRolloutObservation previous = cache.putIfAbsent(observationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime rollout observation already exists: " + observationId);
        }
        log.info("Created visual runtime rollout observation: {} activation={} binding={} operator={} state={}",
                stored.observationId(), stored.activationId(), stored.bindingId(), stored.operatorRef(),
                stored.state());
        return stored;
    }

    private void persist(VisualRuntimeRolloutObservation observation) {
        try {
            jdbc.update(INSERT,
                    observation.observationId(),
                    observation.activationId(),
                    observation.bindingId(),
                    observation.operatorRef(),
                    observation.state(),
                    observation.observedAt().toString(),
                    observation.createdAt().toString(),
                    observation.updatedAt().toString(),
                    objectMapper.writeValueAsString(observation));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual runtime rollout observation: "
                    + observation.observationId(), e);
        }
    }
}
