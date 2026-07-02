package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    private static final String CREATE_REVISION_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_operator_library_revisions (
                library_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                action VARCHAR(32) NOT NULL,
                revision_json CLOB NOT NULL,
                PRIMARY KEY (library_id, revision)
            )
            """;

    private static final String SELECT_ALL = "SELECT library_id, library_json FROM visual_operator_libraries";
    private static final String SELECT_REVISIONS = """
            SELECT revision_json
            FROM visual_operator_library_revisions
            WHERE library_id = ?
            ORDER BY revision DESC
            """;
    private static final String SELECT_REVISION = """
            SELECT revision_json
            FROM visual_operator_library_revisions
            WHERE library_id = ? AND revision = ?
            """;
    private static final String SELECT_MAX_REVISION = """
            SELECT COALESCE(MAX(revision), 0)
            FROM visual_operator_library_revisions
            WHERE library_id = ?
            """;
    private static final String UPSERT = "MERGE INTO visual_operator_libraries (library_id, library_json) KEY (library_id) VALUES (?, ?)";
    private static final String UPSERT_REVISION = """
            MERGE INTO visual_operator_library_revisions (library_id, revision, action, revision_json)
            KEY (library_id, revision)
            VALUES (?, ?, ?, ?)
            """;
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
        jdbc.execute(CREATE_REVISION_TABLE);
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
        backfillMissingCurrentLibraryRevisions();
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
    public List<OperatorLibraryRevision> revisions(String libraryId) {
        return jdbc.query(SELECT_REVISIONS,
                        (rs, rowNum) -> readRevision(rs.getString("revision_json"), libraryId),
                        libraryId).stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<OperatorLibraryRevision> findRevision(String libraryId, long revision) {
        return jdbc.query(SELECT_REVISION,
                        (rs, rowNum) -> readRevision(rs.getString("revision_json"), libraryId),
                        libraryId, revision).stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public synchronized OperatorLibrary upsert(OperatorLibrary library) {
        ensureNoDuplicateOperatorRefs(library);
        String action = cache.containsKey(library.libraryId())
                ? OperatorLibraryRevision.ACTION_REPLACE
                : OperatorLibraryRevision.ACTION_CREATE;
        persist(library, OperatorLibraryRevision.record(library, nextRevision(library.libraryId()), action));
        cache.put(library.libraryId(), library);
        log.info("Upserted visual operator library: {}", library.libraryId());
        return library;
    }

    @Override
    public synchronized OperatorLibrary restore(OperatorLibraryRevision revision) {
        OperatorLibrary library = requireRestorableLibrary(revision);
        ensureNoDuplicateOperatorRefs(library);
        persist(library, OperatorLibraryRevision.restore(library, nextRevision(library.libraryId()),
                revision.revision()));
        cache.put(library.libraryId(), library);
        log.info("Restored visual operator library: {} from revision {}", library.libraryId(), revision.revision());
        return library;
    }

    @Override
    public synchronized void delete(String libraryId) {
        OperatorLibrary library = cache.get(libraryId);
        if (library != null) {
            persistRevision(OperatorLibraryRevision.record(library, nextRevision(libraryId),
                    OperatorLibraryRevision.ACTION_DELETE));
        }
        jdbc.update(DELETE, libraryId);
        cache.remove(libraryId);
        log.info("Deleted visual operator library: {}", libraryId);
    }

    private void persist(OperatorLibrary library, OperatorLibraryRevision revision) {
        try {
            jdbc.update(UPSERT, library.libraryId(), objectMapper.writeValueAsString(library));
            persistRevision(revision);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual operator library: " + library.libraryId(), e);
        }
    }

    private void persistRevision(OperatorLibraryRevision revision) {
        try {
            jdbc.update(UPSERT_REVISION, revision.libraryId(), revision.revision(), revision.action(),
                    objectMapper.writeValueAsString(revision));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual operator library revision: "
                    + revision.libraryId() + "@" + revision.revision(), e);
        }
    }

    private long nextRevision(String libraryId) {
        return currentMaxRevision(libraryId) + 1;
    }

    private long currentMaxRevision(String libraryId) {
        Long revision = jdbc.queryForObject(SELECT_MAX_REVISION, Long.class, libraryId);
        return revision == null ? 0L : revision;
    }

    private void backfillMissingCurrentLibraryRevisions() {
        for (OperatorLibrary library : cache.values()) {
            if (currentMaxRevision(library.libraryId()) > 0) {
                continue;
            }
            persistRevision(OperatorLibraryRevision.record(library, 1, OperatorLibraryRevision.ACTION_CREATE));
            log.info("Backfilled initial visual operator library revision: {}", library.libraryId());
        }
    }

    private Optional<OperatorLibraryRevision> readRevision(String json, String libraryId) {
        try {
            return Optional.of(objectMapper.readValue(json, OperatorLibraryRevision.class));
        } catch (JsonProcessingException e) {
            log.warn("Skipping corrupt visual operator library revision '{}': {}", libraryId, e.getMessage());
            return Optional.empty();
        }
    }

    private void ensureNoDuplicateOperatorRefs(OperatorLibrary library) {
        Map<String, String> ownerByOperatorRef = new java.util.LinkedHashMap<>();
        cache.values().stream()
                .filter(existing -> !existing.libraryId().equals(library.libraryId()))
                .forEach(existing -> existing.operators().stream()
                        .filter(Objects::nonNull)
                        .forEach(operator -> ownerByOperatorRef.put(operator.operatorRef(), existing.libraryId())));
        for (OperatorDefinition operator : library.operators()) {
            if (operator == null) {
                continue;
            }
            String existingOwner = ownerByOperatorRef.get(operator.operatorRef());
            if (existingOwner != null) {
                throw new IllegalArgumentException("operatorRef '%s' already provided by library '%s'"
                        .formatted(operator.operatorRef(), existingOwner));
            }
        }
    }

    private static OperatorLibrary requireRestorableLibrary(OperatorLibraryRevision revision) {
        if (revision == null || revision.library() == null) {
            throw new IllegalArgumentException("Operator library revision cannot be restored because it has no library snapshot");
        }
        return revision.library();
    }
}
