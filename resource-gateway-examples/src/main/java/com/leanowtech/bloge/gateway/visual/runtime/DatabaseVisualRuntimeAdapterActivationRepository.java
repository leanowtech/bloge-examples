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
 * H2-backed repository for runtime adapter activation facts.
 */
public class DatabaseVisualRuntimeAdapterActivationRepository
        implements VisualRuntimeAdapterActivationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseVisualRuntimeAdapterActivationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_runtime_adapter_activations (
                activation_id VARCHAR(255) PRIMARY KEY,
                binding_id VARCHAR(255) NOT NULL,
                operator_ref VARCHAR(255) NOT NULL,
                state VARCHAR(64) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                activation_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT activation_id, activation_json
            FROM visual_runtime_adapter_activations
            """;
    private static final String INSERT = """
            INSERT INTO visual_runtime_adapter_activations
                (activation_id, binding_id, operator_ref, state, created_at, updated_at, activation_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE = """
            UPDATE visual_runtime_adapter_activations
            SET binding_id = ?,
                operator_ref = ?,
                state = ?,
                updated_at = ?,
                activation_json = ?
            WHERE activation_id = ?
            """;

    private final ConcurrentHashMap<String, VisualRuntimeAdapterActivation> cache =
            new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualRuntimeAdapterActivationRepository(JdbcTemplate jdbc,
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
            String activationId = rs.getString("activation_id");
            String json = rs.getString("activation_json");
            try {
                VisualRuntimeAdapterActivation activation =
                        objectMapper.readValue(json, VisualRuntimeAdapterActivation.class);
                cache.put(activationId, activation);
                log.info("Loaded visual runtime adapter activation from DB: {}", activationId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual runtime adapter activation '{}': {}",
                        activationId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualRuntimeAdapterActivationRepository initialized with {} activation(s)",
                cache.size());
    }

    @Override
    public Collection<VisualRuntimeAdapterActivation> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeAdapterActivation::createdAt)
                        .reversed()
                        .thenComparing(VisualRuntimeAdapterActivation::activationId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeAdapterActivation> find(String activationId) {
        return Optional.ofNullable(cache.get(activationId));
    }

    @Override
    public VisualRuntimeAdapterActivation create(VisualRuntimeAdapterActivation activation) {
        if (activation == null) {
            throw new IllegalArgumentException("Runtime adapter activation is required.");
        }
        String activationId = activation.activationId().isBlank()
                ? UUID.randomUUID().toString()
                : activation.activationId();
        if (cache.containsKey(activationId)) {
            throw new IllegalArgumentException("Runtime adapter activation already exists: " + activationId);
        }
        if (findActiveByBindingId(activation.bindingId()).isPresent()) {
            throw new IllegalArgumentException(
                    "Runtime adapter activation already exists for binding: " + activation.bindingId());
        }
        Instant now = Instant.now();
        VisualRuntimeAdapterActivation stored = activation.withIdentity(activationId, 1, now, now);
        persist(stored);
        VisualRuntimeAdapterActivation previous = cache.putIfAbsent(activationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime adapter activation already exists: " + activationId);
        }
        log.info("Created visual runtime adapter activation: {} binding={} operator={}",
                stored.activationId(), stored.bindingId(), stored.operatorRef());
        return stored;
    }

    @Override
    public VisualRuntimeAdapterActivation update(VisualRuntimeAdapterActivation activation) {
        if (activation == null || activation.activationId().isBlank()) {
            throw new IllegalArgumentException("Runtime adapter activation id is required for update.");
        }
        if (!cache.containsKey(activation.activationId())) {
            throw new IllegalArgumentException(
                    "Runtime adapter activation does not exist: " + activation.activationId());
        }
        VisualRuntimeAdapterActivation stored = activation.withIdentity(
                activation.activationId(),
                activation.revision() + 1,
                activation.createdAt(),
                Instant.now()
        );
        try {
            int updated = jdbc.update(UPDATE,
                    stored.bindingId(),
                    stored.operatorRef(),
                    stored.state(),
                    stored.updatedAt().toString(),
                    objectMapper.writeValueAsString(stored),
                    stored.activationId());
            if (updated == 0) {
                throw new IllegalArgumentException(
                        "Runtime adapter activation does not exist: " + stored.activationId());
            }
            cache.put(stored.activationId(), stored);
            log.info("Updated visual runtime adapter activation: {} binding={} operator={} state={}",
                    stored.activationId(), stored.bindingId(), stored.operatorRef(), stored.state());
            return stored;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual runtime adapter activation: "
                    + stored.activationId(), e);
        }
    }

    private void persist(VisualRuntimeAdapterActivation activation) {
        try {
            jdbc.update(INSERT,
                    activation.activationId(),
                    activation.bindingId(),
                    activation.operatorRef(),
                    activation.state(),
                    activation.createdAt().toString(),
                    activation.updatedAt().toString(),
                    objectMapper.writeValueAsString(activation));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual runtime adapter activation: "
                    + activation.activationId(), e);
        }
    }
}
