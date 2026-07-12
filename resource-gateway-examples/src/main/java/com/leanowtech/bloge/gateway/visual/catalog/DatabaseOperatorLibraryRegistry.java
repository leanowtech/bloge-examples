package com.leanowtech.bloge.gateway.visual.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeEventPublisher;
import com.leanowtech.bloge.gateway.visual.change.VisualChangeFact;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;
import com.leanowtech.bloge.gateway.visual.persistence.TransactionCommitActions;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

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
    private final VisualChangeEventPublisher changePublisher;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseOperatorLibraryRegistry(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this(jdbc, objectMapper, VisualChangeEventPublisher.unavailable());
    }

    public DatabaseOperatorLibraryRegistry(JdbcTemplate jdbc,
                                           ObjectMapper objectMapper,
                                           VisualChangeEventPublisher changePublisher) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.changePublisher = changePublisher == null
                ? VisualChangeEventPublisher.unavailable()
                : changePublisher;
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
    @Transactional
    public synchronized OperatorLibrary upsert(OperatorLibrary library,
                                               OperatorLibraryRevision.RevisionMetadata metadata) {
        ensureNoDuplicateOperatorRefs(library);
        long nextRevision = nextRevision(library.libraryId());
        String action = nextRevision == 1
                ? OperatorLibraryRevision.ACTION_CREATE
                : OperatorLibraryRevision.ACTION_REPLACE;
        OperatorLibraryRevision revision = OperatorLibraryRevision.record(library, nextRevision, action, metadata);
        persist(library, revision);
        appendEvent(revision);
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(library.libraryId(), library);
            log.info("Upserted visual operator library: {}", library.libraryId());
        });
        return library;
    }

    @Override
    @Transactional
    public synchronized OperatorLibrary restore(OperatorLibraryRevision revision,
                                                OperatorLibraryRevision.RevisionMetadata metadata) {
        OperatorLibrary library = requireRestorableLibrary(revision);
        ensureNoDuplicateOperatorRefs(library);
        OperatorLibraryRevision restored = OperatorLibraryRevision.restore(library,
                nextRevision(library.libraryId()), revision.revision(), metadata);
        persist(library, restored);
        appendEvent(restored);
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.put(library.libraryId(), library);
            log.info("Restored visual operator library: {} from revision {}", library.libraryId(),
                    revision.revision());
        });
        return library;
    }

    @Override
    @Transactional
    public synchronized void delete(String libraryId, OperatorLibraryRevision.RevisionMetadata metadata) {
        OperatorLibrary library = cache.get(libraryId);
        if (library != null) {
            OperatorLibraryRevision revision = OperatorLibraryRevision.record(library, nextRevision(libraryId),
                    OperatorLibraryRevision.ACTION_DELETE, metadata);
            persistRevision(revision);
            jdbc.update(DELETE, libraryId);
            appendEvent(revision);
        } else {
            jdbc.update(DELETE, libraryId);
        }
        TransactionCommitActions.afterCommitOrNow(() -> {
            cache.remove(libraryId);
            log.info("Deleted visual operator library: {}", libraryId);
        });
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

    private void appendEvent(OperatorLibraryRevision revision) {
        String eventType = switch (revision.action()) {
            case OperatorLibraryRevision.ACTION_CREATE -> "OPERATOR_LIBRARY_CREATED";
            case OperatorLibraryRevision.ACTION_DELETE -> "OPERATOR_LIBRARY_DELETED";
            case OperatorLibraryRevision.ACTION_RESTORE -> "OPERATOR_LIBRARY_RESTORED";
            default -> "OPERATOR_LIBRARY_UPDATED";
        };
        String fingerprint = VisualBundleFingerprint.fromMaterial(Map.of("operatorLibrary", revision.library()));
        changePublisher.publish(new VisualChangeFact(eventType, VisualChangeFact.GLOBAL_SCOPE, "shared",
                VisualChangeFact.GLOBAL_SCOPE,
                new VisualChangeFact.Aggregate("OPERATOR_LIBRARY", revision.libraryId(),
                        revision.revision(), fingerprint),
                "/api/integration/operator-libraries/" + revision.libraryId() + "?revision="
                        + revision.revision(),
                revision.revisionMetadata().changeSource()));
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
            persistRevision(OperatorLibraryRevision.record(library, 1, OperatorLibraryRevision.ACTION_CREATE,
                    OperatorLibraryRevision.RevisionMetadata.of("system",
                            "registry-backfill",
                            "Backfilled initial operator library revision for " + library.libraryId() + ".",
                            "Current library existed before revision history was enabled.")));
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
