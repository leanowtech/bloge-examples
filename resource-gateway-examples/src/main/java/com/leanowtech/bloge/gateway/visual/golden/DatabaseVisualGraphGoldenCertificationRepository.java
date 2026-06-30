package com.leanowtech.bloge.gateway.visual.golden;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * H2-backed repository for latest visual graph golden certifications.
 */
public class DatabaseVisualGraphGoldenCertificationRepository
        implements VisualGraphGoldenCertificationRepository {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseVisualGraphGoldenCertificationRepository.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_graph_golden_certifications (
                publication_id VARCHAR(255) PRIMARY KEY,
                certified_at VARCHAR(64) NOT NULL,
                certification_json CLOB NOT NULL
            )
            """;
    private static final String SELECT_ALL =
            "SELECT publication_id, certification_json FROM visual_graph_golden_certifications";
    private static final String DELETE =
            "DELETE FROM visual_graph_golden_certifications WHERE publication_id = ?";
    private static final String INSERT = """
            INSERT INTO visual_graph_golden_certifications
                (publication_id, certified_at, certification_json)
            VALUES (?, ?, ?)
            """;

    private final ConcurrentHashMap<String, VisualGraphGoldenCertification> cache =
            new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * @param jdbc JDBC template
     * @param objectMapper JSON mapper
     */
    public DatabaseVisualGraphGoldenCertificationRepository(JdbcTemplate jdbc,
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
            String publicationId = rs.getString("publication_id");
            String json = rs.getString("certification_json");
            try {
                VisualGraphGoldenCertification certification =
                        objectMapper.readValue(json, VisualGraphGoldenCertification.class);
                cache.put(publicationId, certification);
                log.info("Loaded visual graph golden certification from DB: {}", publicationId);
            } catch (JsonProcessingException e) {
                log.warn("Skipping corrupt visual graph golden certification '{}': {}",
                        publicationId, e.getMessage());
            }
            return null;
        });
        log.info("DatabaseVisualGraphGoldenCertificationRepository initialized with {} certifications",
                cache.size());
    }

    @Override
    public Optional<VisualGraphGoldenCertification> find(String publicationId) {
        return Optional.ofNullable(cache.get(publicationId));
    }

    @Override
    public VisualGraphGoldenCertification save(VisualGraphGoldenCertification certification) {
        persist(certification);
        cache.put(certification.publicationId(), certification);
        log.info("Saved visual graph golden certification: {} certified={}",
                certification.publicationId(), certification.certified());
        return certification;
    }

    private void persist(VisualGraphGoldenCertification certification) {
        try {
            jdbc.update(DELETE, certification.publicationId());
            jdbc.update(INSERT,
                    certification.publicationId(),
                    certification.certifiedAt().toString(),
                    objectMapper.writeValueAsString(certification));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize visual graph golden certification: "
                    + certification.publicationId(), e);
        }
    }
}
