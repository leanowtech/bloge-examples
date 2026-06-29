package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed visual operator library registry with a hot-path in-memory cache.
 */
public class DatabaseOperatorLibraryRegistry implements OperatorLibraryRegistry {

    private static final Logger log = LoggerFactory.getLogger(DatabaseOperatorLibraryRegistry.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_operator_libraries (
                library_id VARCHAR(255) PRIMARY KEY,
                library_json CLOB NOT NULL
            )
            """;

    private static final String SELECT_ALL = "SELECT library_id, library_json FROM visual_operator_libraries";
    private static final String UPSERT = "MERGE INTO visual_operator_libraries (library_id, library_json) KEY (library_id) VALUES (?, ?)";
    private static final String DELETE = "DELETE FROM visual_operator_libraries WHERE library_id = ?";

    private final ConcurrentHashMap<String, OperatorLibrary> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseOperatorLibraryRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper) {
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
            String libraryId = rs.getString("library_id");
            String json = rs.getString("library_json");
            try {
                OperatorLibrary library = objectMapper.readValue(json, OperatorLibrary.class);
                cache.put(libraryId, library);
                log.info("Loaded visual operator library from DB: {}", libraryId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual operator library '{}': {}", libraryId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseOperatorLibraryRegistry initialized with {} libraries", cache.size());
    }

    @Override
    public Collection<OperatorLibrary> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(OperatorLibrary::libraryId))
                .toList();
    }

    @Override
    public Optional<OperatorLibrary> find(String libraryId) {
        return Optional.ofNullable(cache.get(libraryId));
    }

    @Override
    public OperatorLibrary upsert(OperatorLibrary library) {
        ensureNoDuplicateOperatorRefs(library);
        persist(library);
        cache.put(library.libraryId(), library);
        log.info("Upserted visual operator library: {}", library.libraryId());
        return library;
    }

    @Override
    public void delete(String libraryId) {
        jdbc.update(DELETE, libraryId);
        cache.remove(libraryId);
        log.info("Deleted visual operator library: {}", libraryId);
    }

    private void persist(OperatorLibrary library) {
        try {
            jdbc.update(UPSERT, library.libraryId(), objectMapper.writeValueAsString(library));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual operator library: " + library.libraryId(), e);
        }
    }

    private void ensureNoDuplicateOperatorRefs(OperatorLibrary library) {
        Map<String, String> ownerByOperatorRef = new java.util.LinkedHashMap<>();
        cache.values().stream()
                .filter(existing -> !existing.libraryId().equals(library.libraryId()))
                .forEach(existing -> existing.operators().forEach(operator ->
                        ownerByOperatorRef.put(operator.operatorRef(), existing.libraryId())));
        for (OperatorDefinition operator : library.operators()) {
            String existingOwner = ownerByOperatorRef.get(operator.operatorRef());
            if (existingOwner != null) {
                throw new IllegalArgumentException("operatorRef '%s' already provided by library '%s'"
                        .formatted(operator.operatorRef(), existingOwner));
            }
        }
    }
}
