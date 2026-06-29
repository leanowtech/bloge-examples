package com.leanowtech.bloge.gateway.visual.publication;

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
 * H2-backed repository for immutable visual graph publication artifacts.
 */
public class DatabaseVisualGraphPublicationRepository implements VisualGraphPublicationRepository {

    private static final Logger log = LoggerFactory.getLogger(DatabaseVisualGraphPublicationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_publications (
                publication_id VARCHAR(255) PRIMARY KEY,
                draft_id VARCHAR(255) NOT NULL,
                draft_revision BIGINT NOT NULL,
                created_at VARCHAR(64) NOT NULL,
                publication_json CLOB NOT NULL
            )
            """;

    private static final String SELECT_ALL = "SELECT publication_id, publication_json FROM visual_graph_publications";
    private static final String INSERT = """
            INSERT INTO visual_graph_publications
                (publication_id, draft_id, draft_revision, created_at, publication_json)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualGraphPublication> cache = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualGraphPublicationRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
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
            String publicationId = rs.getString("publication_id");
            String json = rs.getString("publication_json");
            try {
                VisualGraphPublication publication = objectMapper.readValue(json, VisualGraphPublication.class);
                cache.put(publicationId, publication);
                log.info("Loaded visual graph publication from DB: {}", publicationId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual graph publication '{}': {}", publicationId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualGraphPublicationRepository initialized with {} publications", cache.size());
    }

    @Override
    public Collection<VisualGraphPublication> all() {
        return cache.values().stream()
                .sorted(Comparator.comparing(VisualGraphPublication::createdAt)
                        .thenComparing(VisualGraphPublication::publicationId))
                .toList();
    }

    @Override
    public Optional<VisualGraphPublication> find(String publicationId) {
        return Optional.ofNullable(cache.get(publicationId));
    }

    @Override
    public VisualGraphPublication create(VisualGraphPublication publication) {
        String publicationId = publication.publicationId().isBlank()
                ? UUID.randomUUID().toString()
                : publication.publicationId();
        VisualGraphPublication stored = publication.withIdentity(publicationId, Instant.now());
        persist(stored);
        VisualGraphPublication previous = cache.putIfAbsent(publicationId, stored);
        if (previous != null) {
            throw new IllegalArgumentException("Publication already exists: " + publicationId);
        }
        log.info("Created visual graph publication: {} from draft {}@{}",
                stored.publicationId(), stored.draftId(), stored.draftRevision());
        return stored;
    }

    private void persist(VisualGraphPublication publication) {
        try {
            jdbc.update(INSERT,
                    publication.publicationId(),
                    publication.draftId(),
                    publication.draftRevision(),
                    publication.createdAt().toString(),
                    objectMapper.writeValueAsString(publication));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph publication: "
                    + publication.publicationId(), e);
        }
    }
}
