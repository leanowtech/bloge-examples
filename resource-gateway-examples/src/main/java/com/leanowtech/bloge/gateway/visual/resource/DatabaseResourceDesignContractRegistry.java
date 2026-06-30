package com.leanowtech.bloge.gateway.visual.resource;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed resource design contract registry with a hot-path in-memory cache.
 */
public class DatabaseResourceDesignContractRegistry implements ResourceDesignContractRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResourceDesignContractRegistry.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_resource_design_contracts (
                resource_id VARCHAR(255) PRIMARY KEY,
                contract_json CLOB NOT NULL
            )
            """;

    private static final String SELECT_ALL = "SELECT resource_id, contract_json FROM visual_resource_design_contracts";
    private static final String UPSERT = "MERGE INTO visual_resource_design_contracts (resource_id, contract_json) KEY (resource_id) VALUES (?, ?)";
    private static final String DELETE = "DELETE FROM visual_resource_design_contracts WHERE resource_id = ?";

    private final ConcurrentHashMap<String, ResourceDesignContract> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseResourceDesignContractRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Initializes the table and cache.
     */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        cache.clear();
        jdbc.query(SELECT_ALL, (rs, rowNum) -> {
            String resourceId = rs.getString("resource_id");
            String json = rs.getString("contract_json");
            try {
                ResourceDesignContract contract = objectMapper.readValue(json, ResourceDesignContract.class);
                cache.put(resourceId, contract);
                log.info("Loaded visual resource design contract from DB: {}", resourceId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual resource design contract '{}': {}", resourceId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseResourceDesignContractRegistry initialized with {} contracts", cache.size());
    }

    @Override
    public Collection<ResourceDesignContract> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(ResourceDesignContract::resourceId))
                .toList();
    }

    @Override
    public Optional<ResourceDesignContract> findByResourceId(String resourceId) {
        return Optional.ofNullable(cache.get(resourceId));
    }

    @Override
    public ResourceDesignContract upsert(ResourceDesignContract contract) {
        persist(contract);
        cache.put(contract.resourceId(), contract);
        log.info("Upserted visual resource design contract: {}", contract.resourceId());
        return contract;
    }

    @Override
    public void deleteByResourceId(String resourceId) {
        jdbc.update(DELETE, resourceId);
        cache.remove(resourceId);
        log.info("Deleted visual resource design contract: {}", resourceId);
    }

    private void persist(ResourceDesignContract contract) {
        try {
            jdbc.update(UPSERT, contract.resourceId(), objectMapper.writeValueAsString(contract));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Failed to serialize visual resource design contract: " + contract.resourceId(), e);
        }
    }
}
