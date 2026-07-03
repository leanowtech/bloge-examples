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
 * H2-backed repository for executable lowering integration facts.
 */
public class DatabaseVisualExecutableLoweringIntegrationRepository
        implements VisualExecutableLoweringIntegrationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseVisualExecutableLoweringIntegrationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_executable_lowering_integrations (
                integration_id VARCHAR(255) PRIMARY KEY,
                activation_id VARCHAR(255) NOT NULL,
                operator_ref VARCHAR(255) NOT NULL,
                state VARCHAR(64) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                integration_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT integration_id, integration_json
            FROM visual_executable_lowering_integrations
            """;
    private static final String INSERT = """
            INSERT INTO visual_executable_lowering_integrations
                (integration_id, activation_id, operator_ref, state, created_at, updated_at, integration_json)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE = """
            UPDATE visual_executable_lowering_integrations
            SET activation_id = ?,
                operator_ref = ?,
                state = ?,
                updated_at = ?,
                integration_json = ?
            WHERE integration_id = ?
            """;

    private final ConcurrentHashMap<String, VisualExecutableLoweringIntegration> cache =
            new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualExecutableLoweringIntegrationRepository(JdbcTemplate jdbc,
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
            String integrationId = rs.getString("integration_id");
            String json = rs.getString("integration_json");
            try {
                VisualExecutableLoweringIntegration integration =
                        objectMapper.readValue(json, VisualExecutableLoweringIntegration.class);
                cache.put(integrationId, integration);
                log.info("Loaded visual executable lowering integration from DB: {}", integrationId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual executable lowering integration '{}': {}",
                        integrationId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualExecutableLoweringIntegrationRepository initialized with {} integration(s)",
                cache.size());
    }

    @Override
    public Collection<VisualExecutableLoweringIntegration> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualExecutableLoweringIntegration::createdAt)
                        .reversed()
                        .thenComparing(VisualExecutableLoweringIntegration::integrationId))
                .toList();
    }

    @Override
    public Optional<VisualExecutableLoweringIntegration> find(String integrationId) {
        return Optional.ofNullable(cache.get(integrationId));
    }

    @Override
    public VisualExecutableLoweringIntegration create(VisualExecutableLoweringIntegration integration) {
        if (integration == null) {
            throw new IllegalArgumentException("Executable lowering integration is required.");
        }
        String integrationId = integration.integrationId().isBlank()
                ? UUID.randomUUID().toString()
                : integration.integrationId();
        if (cache.containsKey(integrationId)) {
            throw new IllegalArgumentException("Executable lowering integration already exists: " + integrationId);
        }
        if (findActiveByActivationId(integration.activationId()).isPresent()) {
            throw new IllegalArgumentException(
                    "Executable lowering integration already exists for activation: "
                            + integration.activationId());
        }
        Instant now = Instant.now();
        VisualExecutableLoweringIntegration stored = integration.withIdentity(integrationId, 1, now, now);
        persist(stored);
        VisualExecutableLoweringIntegration previous = cache.putIfAbsent(integrationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Executable lowering integration already exists: " + integrationId);
        }
        log.info("Created visual executable lowering integration: {} activation={} operator={}",
                stored.integrationId(), stored.activationId(), stored.operatorRef());
        return stored;
    }

    @Override
    public VisualExecutableLoweringIntegration update(VisualExecutableLoweringIntegration integration) {
        if (integration == null || integration.integrationId().isBlank()) {
            throw new IllegalArgumentException("Executable lowering integration id is required for update.");
        }
        if (!cache.containsKey(integration.integrationId())) {
            throw new IllegalArgumentException(
                    "Executable lowering integration does not exist: " + integration.integrationId());
        }
        VisualExecutableLoweringIntegration stored = integration.withIdentity(
                integration.integrationId(),
                integration.revision() + 1,
                integration.createdAt(),
                Instant.now()
        );
        try {
            int updated = jdbc.update(UPDATE,
                    stored.activationId(),
                    stored.operatorRef(),
                    stored.state(),
                    stored.updatedAt().toString(),
                    objectMapper.writeValueAsString(stored),
                    stored.integrationId());
            if (updated == 0) {
                throw new IllegalArgumentException(
                        "Executable lowering integration does not exist: " + stored.integrationId());
            }
            cache.put(stored.integrationId(), stored);
            log.info("Updated visual executable lowering integration: {} activation={} operator={} state={}",
                    stored.integrationId(), stored.activationId(), stored.operatorRef(), stored.state());
            return stored;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual executable lowering integration: "
                    + stored.integrationId(), e);
        }
    }

    private void persist(VisualExecutableLoweringIntegration integration) {
        try {
            jdbc.update(INSERT,
                    integration.integrationId(),
                    integration.activationId(),
                    integration.operatorRef(),
                    integration.state(),
                    integration.createdAt().toString(),
                    integration.updatedAt().toString(),
                    objectMapper.writeValueAsString(integration));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual executable lowering integration: "
                    + integration.integrationId(), e);
        }
    }
}
