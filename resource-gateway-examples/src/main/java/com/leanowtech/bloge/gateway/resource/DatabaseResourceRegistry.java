package com.leanowtech.bloge.gateway.resource;

import com.leanowtech.bloge.gateway.exception.ResourceDescriptorException;
import com.leanowtech.bloge.gateway.exception.ResourceNotFoundException;
import com.leanowtech.bloge.gateway.expression.BlgeExpressionEvaluator;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.annotation.PostConstruct;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link WritableResourceRegistry} backed by an in-memory {@link ConcurrentHashMap}
 * hot-path cache <em>and</em> a persistent H2 table for durability across restarts.
 *
 * <h3>Design trade-off (example-oriented)</h3>
 * <p>This implementation is deliberately simplified for the resource-gateway example:
 * <ul>
 *   <li>The hot-path cache is the authoritative read source — all {@link #resolve} calls
 *       hit the map and never touch JDBC.</li>
 *   <li>Writes (register / update / deregister) update <em>both</em> the cache and the
 *       H2 table transactionally, so a restart reloads the last-known state.</li>
 *   <li>Descriptor serialization uses Jackson JSON; the schema column stores the full
 *       {@link ResourceDescriptor} record as a JSON blob.</li>
 * </ul>
 *
 * <p>In a production system you would replace the embedded H2 table with a proper
 * configuration store (etcd, Consul, a relational DB with change-data-capture) and
 * add optimistic-locking or versioning on updates.
 */
public class DatabaseResourceRegistry implements WritableResourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseResourceRegistry.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS resource_descriptors (
                resource_id VARCHAR(255) PRIMARY KEY,
                descriptor_json CLOB NOT NULL
            )
            """;

    private static final String SELECT_ALL = "SELECT resource_id, descriptor_json FROM resource_descriptors";
    private static final String UPSERT = "MERGE INTO resource_descriptors (resource_id, descriptor_json) KEY (resource_id) VALUES (?, ?)";
    private static final String DELETE = "DELETE FROM resource_descriptors WHERE resource_id = ?";

    private final ConcurrentHashMap<String, ResourceDescriptor> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final BlgeExpressionEvaluator evaluator;

    /**
     * Creates the registry with JDBC persistence and expression validation.
     *
     * @param jdbc         Spring JDBC template wired to the embedded H2 datasource
     * @param objectMapper Jackson mapper for descriptor serialization
     * @param evaluator    bloge expression evaluator for compile-time validation of expressions
     */
    public DatabaseResourceRegistry(JdbcTemplate jdbc,
                                    ObjectMapper objectMapper,
                                    BlgeExpressionEvaluator evaluator) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.evaluator = evaluator;
    }

    /**
     * Initializes the database table and loads all persisted descriptors into the
     * in-memory cache. Called automatically by Spring after construction.
     */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
        jdbc.query(SELECT_ALL, (rs, _) -> {
            String id = rs.getString("resource_id");
            String json = rs.getString("descriptor_json");
            try {
                ResourceDescriptor descriptor = objectMapper.readValue(json, ResourceDescriptor.class);
                cache.put(id, descriptor);
                log.info("Loaded resource descriptor from DB: {}", id);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt descriptor row for '{}': {}", id, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseResourceRegistry initialized with {} descriptors", cache.size());
    }

    /** {@inheritDoc} */
    @Override
    public ResourceDescriptor resolve(String resourceId) {
        ResourceDescriptor descriptor = cache.get(resourceId);
        if (descriptor == null) {
            throw new ResourceNotFoundException(resourceId);
        }
        return descriptor;
    }

    /** {@inheritDoc} */
    @Override
    public boolean contains(String resourceId) {
        return cache.containsKey(resourceId);
    }

    /** {@inheritDoc} */
    @Override
    public Collection<ResourceDescriptor> all() {
        return Collections.unmodifiableCollection(cache.values());
    }

    /**
     * {@inheritDoc}
     *
     * <p>Validates all bloge expressions in the descriptor's parameter mapping and
     * response protocol before persisting. If any expression fails to compile, a
     * {@link ResourceDescriptorException} is thrown and no state is changed.
     *
     * @throws IllegalArgumentException      if a descriptor with the same ID already exists
     * @throws ResourceDescriptorException   if any bloge expression fails to compile
     */
    @Override
    public void register(ResourceDescriptor descriptor) {
        if (cache.containsKey(descriptor.resourceId())) {
            throw new IllegalArgumentException(
                    "Descriptor already registered: " + descriptor.resourceId());
        }
        validateExpressions(descriptor);
        persist(descriptor);
        cache.put(descriptor.resourceId(), descriptor);
        log.info("Registered resource descriptor: {}", descriptor.resourceId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException     if no descriptor with the given ID exists
     * @throws ResourceDescriptorException   if any bloge expression fails to compile
     */
    @Override
    public void update(ResourceDescriptor descriptor) {
        if (!cache.containsKey(descriptor.resourceId())) {
            throw new ResourceNotFoundException(descriptor.resourceId());
        }
        validateExpressions(descriptor);
        persist(descriptor);
        cache.put(descriptor.resourceId(), descriptor);
        log.info("Updated resource descriptor: {}", descriptor.resourceId());
    }

    /**
     * {@inheritDoc}
     *
     * @throws ResourceNotFoundException if no descriptor with the given ID exists
     */
    @Override
    public void deregister(String resourceId) {
        if (!cache.containsKey(resourceId)) {
            throw new ResourceNotFoundException(resourceId);
        }
        jdbc.update(DELETE, resourceId);
        cache.remove(resourceId);
        log.info("Deregistered resource descriptor: {}", resourceId);
    }

    // ── Internal helpers ────────────────────────────────────────────────

    private void persist(ResourceDescriptor descriptor) {
        try {
            String json = objectMapper.writeValueAsString(descriptor);
            jdbc.update(UPSERT, descriptor.resourceId(), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize descriptor: " + descriptor.resourceId(), e);
        }
    }

    /**
     * Pre-compiles all bloge expressions in the descriptor so that configuration errors
     * surface at write time rather than at runtime.
     */
    private void validateExpressions(ResourceDescriptor descriptor) {
        ParameterMapping mapping = descriptor.parameterMapping();
        for (var entry : mapping.pathExpressions().entrySet()) {
            evaluator.precompile(entry.getValue());
        }
        for (var entry : mapping.queryExpressions().entrySet()) {
            evaluator.precompile(entry.getValue());
        }
        for (var entry : mapping.headerExpressions().entrySet()) {
            evaluator.precompile(entry.getValue());
        }
        for (var entry : mapping.cookieExpressions().entrySet()) {
            evaluator.precompile(entry.getValue());
        }
        if (mapping.bodyExpression() != null && !mapping.bodyExpression().isBlank()) {
            evaluator.precompile(mapping.bodyExpression());
        }
        if (descriptor.responseProtocol() instanceof ResponseProtocol.BlgeExpression expr) {
            evaluator.precompile(expr.successExpr());
            if (expr.messageExpr() != null && !expr.messageExpr().isBlank()) {
                evaluator.precompile(expr.messageExpr());
            }
            if (expr.payloadExpr() != null && !expr.payloadExpr().isBlank()) {
                evaluator.precompile(expr.payloadExpr());
            }
        }
    }
}
