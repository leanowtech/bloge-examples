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
 * H2-backed, enterprise-scoped authoring draft repository with immutable revision history.
 */
public class DatabaseAuthoringDraftRepository implements AuthoringDraftRepository {

    private static final int MAX_FINGERPRINT_BYTES = 16 * 1_048_576;
    private static final String CREATE_CURRENT = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_scoped_drafts (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                draft_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region, draft_id
                )
            )
            """;
    private static final String CREATE_HISTORY = """
            CREATE TABLE IF NOT EXISTS visual_library_authoring_scoped_draft_revisions (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(64) NOT NULL,
                draft_id VARCHAR(255) NOT NULL,
                revision BIGINT NOT NULL,
                stored_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    draft_id, revision
                )
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
    public Collection<AuthoringDraft> all(AuthoringScope scope) {
        AuthoringScope requiredScope = java.util.Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT draft_id, revision, stored_json
                        FROM visual_library_authoring_scoped_drafts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ?
                        ORDER BY draft_id
                        """,
                (rs, rowNum) -> read(
                        rs.getString("stored_json"),
                        rs.getString("draft_id"),
                        rs.getLong("revision")),
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region())
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public Optional<AuthoringDraft> find(AuthoringScope scope, String draftId) {
        AuthoringScope requiredScope = java.util.Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT revision, stored_json
                        FROM visual_library_authoring_scoped_drafts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND draft_id = ?
                        """,
                (rs, rowNum) -> read(
                        rs.getString("stored_json"),
                        normalized(draftId),
                        rs.getLong("revision")),
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                normalized(draftId))
                .stream()
                .flatMap(Optional::stream)
                .findFirst();
    }

    @Override
    public List<AuthoringDraft> revisions(AuthoringScope scope, String draftId) {
        AuthoringScope requiredScope = java.util.Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT revision, stored_json
                        FROM visual_library_authoring_scoped_draft_revisions
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND draft_id = ?
                        ORDER BY revision DESC
                        """,
                (rs, rowNum) -> read(
                        rs.getString("stored_json"),
                        normalized(draftId),
                        rs.getLong("revision")),
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                normalized(draftId))
                .stream()
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    @Transactional
    public synchronized Optional<AuthoringDraft> saveIfRevision(AuthoringScope scope,
                                                                long expectedRevision,
                                                                AuthoringDraft candidate,
                                                                String actor) {
        AuthoringScope requiredScope = java.util.Objects.requireNonNull(scope, "scope");
        if (candidate == null || candidate.document() == null || expectedRevision < 0) {
            throw new IllegalArgumentException(
                    "Authoring draft, document, and non-negative expected revision are required");
        }
        String id = normalized(candidate.draftId());
        if (id.isBlank()) {
            throw new IllegalArgumentException("Authoring draft id is required");
        }
        AuthoringDraft current = find(requiredScope, id).orElse(null);
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
                        "document", candidate.document(),
                        "evidence", candidate.evidence(),
                        "confirmations", candidate.confirmations()
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
                                INSERT INTO visual_library_authoring_scoped_drafts (
                                    tenant_id, organization_id, project_id, environment_id, region,
                                    draft_id, revision, stored_json
                                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                                """,
                        requiredScope.tenantId(),
                        requiredScope.organizationId(),
                        requiredScope.projectId(),
                        requiredScope.environmentId(),
                        requiredScope.region(),
                        id,
                        nextRevision,
                        json);
            } catch (DuplicateKeyException concurrentCreate) {
                return Optional.empty();
            }
        } else {
            int updated = jdbc.update("""
                            UPDATE visual_library_authoring_scoped_drafts
                            SET revision = ?, stored_json = ?
                            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                              AND environment_id = ? AND region = ?
                              AND draft_id = ? AND revision = ?
                            """,
                    nextRevision,
                    json,
                    requiredScope.tenantId(),
                    requiredScope.organizationId(),
                    requiredScope.projectId(),
                    requiredScope.environmentId(),
                    requiredScope.region(),
                    id,
                    expectedRevision);
            if (updated == 0) {
                return Optional.empty();
            }
        }
        jdbc.update("""
                        INSERT INTO visual_library_authoring_scoped_draft_revisions (
                            tenant_id, organization_id, project_id, environment_id, region,
                            draft_id, revision, stored_json
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                requiredScope.tenantId(),
                requiredScope.organizationId(),
                requiredScope.projectId(),
                requiredScope.environmentId(),
                requiredScope.region(),
                id,
                nextRevision,
                json);
        return Optional.of(stored);
    }

    private Optional<AuthoringDraft> read(String json, String draftId, long revision) {
        try {
            AuthoringDraft draft = objectMapper.readValue(json, AuthoringDraft.class);
            if (!draft.draftId().equals(draftId) || draft.revision() != revision) {
                throw new IllegalStateException(
                        "Authoring draft projection does not match stored payload: " + draftId);
            }
            return Optional.of(draft);
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
