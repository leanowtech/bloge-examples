package com.leanowtech.bloge.gateway.visual.authoring.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.authoring.model.AuthoringDraft;
import com.leanowtech.bloge.gateway.visual.model.VisualBundleFingerprint;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * H2-backed authoring draft repository with immutable revision history.
 */
public class DatabaseAuthoringDraftRepository implements AuthoringDraftRepository {

    private static final int MAX_FINGERPRINT_BYTES = 16 * 1_048_576;
    private static final String CREATE_CURRENT = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_drafts (
                draft_id VARCHAR(255) PRIMARY KEY,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL
            )
            """;
    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_draft_revisions (
                draft_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (draft_id, revision)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DatabaseAuthoringDraftRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = java.util.Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_CURRENT);
        jdbc.execute(CREATE_HISTORY);
    }

    @Override
    public Collection<AuthoringDraft> all() {
        return jdbc.query("""
                        SELECT draft_id, stored_json
                        FROM visual_library_authoring_drafts
                        ORDER BY draft_id
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json"), rs.getString("draft_id")))
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<AuthoringDraft> find(String draftId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_library_authoring_drafts
                        WHERE draft_id = ?
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json"), normalized(draftId)),
                normalized(draftId))
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public List<AuthoringDraft> revisions(String draftId) {
        return jdbc.query("""
                        SELECT stored_json
                        FROM visual_library_authoring_draft_revisions
                        WHERE draft_id = ?
                        ORDER BY revision DESC
                        """,
                (rs, rowNum) -> read(rs.getString("stored_json"), normalized(draftId)),
                normalized(draftId))
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional
    public synchronized Optional<AuthoringDraft> saveIfRevision(long expectedRevision,
                                                                AuthoringDraft candidate,
                                                                String actor) {
        if (candidate == null || candidate.document() == null || expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "Authoring draft, document, and non-negative expected revision are required");
        }
        String id = normalized(candidate.draftId());
        if (id.isBlank()) {
            throw new IllegalArgumentException("Authoring draft id is required");
        }
        AuthoringDraft current = find(id).orElse(null);
        if ((current == null && expectedRevision != 0)
                || (current != null && current.revision() != expectedRevision)) {
            return Optional.empty();
        }

        long nextRevision = expectedRevision + 1;
        Instant now = Instant.now();
        Instant createdAt = current == null ? now : current.createdAt();
        String fingerprint = VisualBundleFingerprint.fromCanonicalValue(
                objectMapper,
                java.util.Map.of(
                        "draftId", id,
                        "revision", nextRevision,
                        "sourceMode", candidate.sourceMode(),
                        "document", candidate.document()
                ),
                MAX_FINGERPRINT_BYTES
        );
        AuthoringDraft stored = candidate.withStorageIdentity(
                id,
                nextRevision,
                fingerprint,
                createdAt,
                now,
                normalized(actor)
        );
        String json = serialize(stored);
        if (current == null) {
            try {
                jdbc.update("""
                                INSERT INTO visual_library_authoring_drafts
                                    (draft_id, revision, stored_json)
                                VALUES (?, ?, ?)
                                """,
                        id, nextRevision, json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else {
            int updated = jdbc.update("""
                            UPDATE visual_library_authoring_drafts
                            SET revision = ?, stored_json = ?
                            WHERE draft_id = ? AND revision = ?
                            """,
                    nextRevision, json, id, expectedRevision);
            if (updated == 0) {
                return Optional.empty();
            }
        }
        jdbc.update("""
                        INSERT INTO visual_library_authoring_draft_revisions
                            (draft_id, revision, stored_json)
                        VALUES (?, ?, ?)
                        """,
                id, nextRevision, json);
        return Optional.of(stored);
    }

    private Optional<AuthoringDraft> read(String json, String draftId) {
        try {
            return Optional.of(objectMapper.readValue(json, AuthoringDraft.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to read authoring draft: " + draftId, exception);
        }
    }

    private String serialize(AuthoringDraft draft) {
        try {
            return objectMapper.writeValueAsString(draft);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize authoring draft: " + draft.draftId(), exception);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }
}
