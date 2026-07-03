package com.leanowtech.bloge.gateway.visual.asset;

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
 * H2-backed repository for runtime implementation binding proposals.
 */
public class DatabaseVisualRuntimeBindingImplementationRepository
        implements VisualRuntimeBindingImplementationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseVisualRuntimeBindingImplementationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_runtime_binding_implementations (
                binding_id VARCHAR(255) PRIMARY KEY,
                operator_ref VARCHAR(255) NOT NULL,
                state VARCHAR(64) NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL,
                binding_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL = """
            SELECT binding_id, binding_json
            FROM visual_runtime_binding_implementations
            """;
    private static final String INSERT = """
            INSERT INTO visual_runtime_binding_implementations
                (binding_id, operator_ref, state, created_at, updated_at, binding_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualRuntimeBindingImplementationBinding> cache =
            new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualRuntimeBindingImplementationRepository(JdbcTemplate jdbc,
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
            String bindingId = rs.getString("binding_id");
            String json = rs.getString("binding_json");
            try {
                VisualRuntimeBindingImplementationBinding binding =
                        objectMapper.readValue(json, VisualRuntimeBindingImplementationBinding.class);
                cache.put(bindingId, binding);
                log.info("Loaded visual runtime binding implementation from DB: {}", bindingId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual runtime binding implementation '{}': {}",
                        bindingId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualRuntimeBindingImplementationRepository initialized with {} binding(s)",
                cache.size());
    }

    @Override
    public Collection<VisualRuntimeBindingImplementationBinding> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualRuntimeBindingImplementationBinding::createdAt)
                        .reversed()
                        .thenComparing(VisualRuntimeBindingImplementationBinding::bindingId))
                .toList();
    }

    @Override
    public Optional<VisualRuntimeBindingImplementationBinding> find(String bindingId) {
        return Optional.ofNullable(cache.get(bindingId));
    }

    @Override
    public VisualRuntimeBindingImplementationBinding create(VisualRuntimeBindingImplementationBinding binding) {
        String bindingId = binding.bindingId().isBlank() ? UUID.randomUUID().toString() : binding.bindingId();
        if (cache.containsKey(bindingId)) {
            throw new IllegalArgumentException("Runtime binding implementation already exists: " + bindingId);
        }
        Instant now = Instant.now();
        VisualRuntimeBindingImplementationBinding stored = binding.withIdentity(bindingId, 1, now, now);
        persist(stored);
        VisualRuntimeBindingImplementationBinding previous = cache.putIfAbsent(bindingId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Runtime binding implementation already exists: " + bindingId);
        }
        log.info("Created visual runtime binding implementation: {} operator={} state={}",
                stored.bindingId(), stored.operatorRef(), stored.state());
        return stored;
    }

    private void persist(VisualRuntimeBindingImplementationBinding binding) {
        try {
            jdbc.update(INSERT,
                    binding.bindingId(),
                    binding.operatorRef(),
                    binding.state(),
                    binding.createdAt().toString(),
                    binding.updatedAt().toString(),
                    objectMapper.writeValueAsString(binding));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual runtime binding implementation: "
                    + binding.bindingId(), e);
        }
    }
}
