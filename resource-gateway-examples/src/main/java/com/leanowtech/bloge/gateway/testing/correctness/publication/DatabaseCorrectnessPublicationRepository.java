package com.leanowtech.bloge.gateway.testing.correctness.publication;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.EnterpriseScope;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocol.ExactAssetRef;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessProtocolFingerprint;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.AttemptStage;
import com.leanowtech.bloge.gateway.testing.correctness.domain.CorrectnessPublication.PublicationAttempt;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** PostgreSQL/H2 repository for immutable Publication manifests and a CAS publication Saga. */
public final class DatabaseCorrectnessPublicationRepository
        implements CorrectnessPublicationRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public DatabaseCorrectnessPublicationRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public Optional<StoredCorrectnessPublication> findPublication(
            EnterpriseScope scope, String publicationId) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactId = required(publicationId, "publicationId");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_publications
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND publication_id = ?
                        """,
                (result, row) -> readPublication(result, exactScope, exactId),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).findFirst();
    }

    @Override
    public Optional<StoredCorrectnessPublicationAttempt> findAttempt(
            EnterpriseScope scope, String attemptId) {
        return findAttemptBy(scope, "attempt_id", required(attemptId, "attemptId"));
    }

    @Override
    public Optional<StoredCorrectnessPublicationAttempt> findAttemptByIdempotencyFingerprint(
            EnterpriseScope scope, String idempotencyKeyFingerprint) {
        return findAttemptBy(
                scope, "idempotency_key_fingerprint",
                fingerprint(idempotencyKeyFingerprint, "idempotencyKeyFingerprint"));
    }

    @Override
    public List<StoredCorrectnessPublicationAttempt> attemptHistory(
            EnterpriseScope scope, String attemptId) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        String exactId = required(attemptId, "attemptId");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_publication_attempt_history
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND attempt_id = ?
                        ORDER BY state_version
                        """,
                (result, row) -> readAttempt(result, exactScope, exactId, false),
                scopeArgs(exactScope, exactId)).stream().flatMap(Optional::stream).toList();
    }

    @Override
    @Transactional
    public Optional<StoredCorrectnessPublicationAttempt> saveAttemptIfVersion(
            EnterpriseScope scope,
            long expectedStateVersion,
            StoredCorrectnessPublicationAttempt candidate
    ) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        requireCandidate(exactScope, expectedStateVersion, candidate);
        String json = serialize(candidate);
        PublicationAttempt attempt = candidate.attempt();
        if (expectedStateVersion == 0) {
            if (attempt.stage() != AttemptStage.PREPARING) {
                throw new IllegalArgumentException("New publication attempt must be PREPARING");
            }
            try {
                insertAttempt(candidate, json);
            } catch (DuplicateKeyException concurrentCreateOrIdempotency) {
                return Optional.empty();
            }
        } else {
            StoredCorrectnessPublicationAttempt current =
                    findAttempt(exactScope, attempt.attemptId()).orElse(null);
            if (current == null || current.attempt().stateVersion() != expectedStateVersion) {
                return Optional.empty();
            }
            requireTransition(current, candidate);
            if (updateAttempt(candidate, json, expectedStateVersion) == 0) {
                return Optional.empty();
            }
        }
        insertHistory(candidate, json);
        return Optional.of(candidate);
    }

    @Override
    @Transactional
    public Optional<CommitResult> commitIfVersion(
            EnterpriseScope scope,
            long expectedStateVersion,
            StoredCorrectnessPublicationAttempt committedAttempt,
            StoredCorrectnessPublication publication,
            CorrectnessPublicationCompleted event
    ) {
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        requireCandidate(exactScope, expectedStateVersion, committedAttempt);
        requireCommit(exactScope, committedAttempt, publication, event);
        StoredCorrectnessPublicationAttempt current = findAttempt(
                exactScope, committedAttempt.attempt().attemptId()).orElse(null);
        if (current == null || current.attempt().stateVersion() != expectedStateVersion) {
            return Optional.empty();
        }
        requireTransition(current, committedAttempt);
        String attemptJson = serialize(committedAttempt);
        if (updateAttempt(committedAttempt, attemptJson, expectedStateVersion) == 0) {
            return Optional.empty();
        }
        StoredCorrectnessPublication persisted = createOrVerifyPublication(publication);
        insertHistory(committedAttempt, attemptJson);
        insertEvent(event);
        return Optional.of(new CommitResult(committedAttempt, persisted));
    }

    private Optional<StoredCorrectnessPublicationAttempt> findAttemptBy(
            EnterpriseScope scope, String column, String value) {
        if (!"attempt_id".equals(column) && !"idempotency_key_fingerprint".equals(column)) {
            throw new IllegalArgumentException("Unsupported publication attempt lookup");
        }
        EnterpriseScope exactScope = Objects.requireNonNull(scope, "scope");
        return jdbc.query("""
                        SELECT * FROM rg_correctness_publication_attempts
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND %s = ?
                        """.formatted(column),
                (result, row) -> readAttempt(
                        result, exactScope, result.getString("attempt_id"), true),
                scopeArgs(exactScope, value)).stream().flatMap(Optional::stream).findFirst();
    }

    private StoredCorrectnessPublication createOrVerifyPublication(
            StoredCorrectnessPublication candidate) {
        CorrectnessPublication value = candidate.publication();
        String json = serialize(candidate);
        try {
            jdbc.update("""
                            INSERT INTO rg_correctness_publications (
                                tenant_id, organization_id, project_id, environment_id, region_id,
                                publication_id, publication_fingerprint,
                                definition_id, definition_revision, definition_fingerprint,
                                inventory_id, inventory_revision, inventory_fingerprint,
                                scenario_draft_set_id, scenario_draft_set_revision,
                                scenario_draft_set_fingerprint, canonical_json,
                                committed_at, committed_by
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    value.scope().tenantId(), value.scope().organizationId(),
                    value.scope().projectId(), value.scope().environment(), value.scope().region(),
                    value.publicationId(), candidate.publicationFingerprint(),
                    value.definitionRef().id(), value.definitionRef().revision(),
                    value.definitionRef().fingerprint(), value.inventoryRef().id(),
                    value.inventoryRef().revision(), value.inventoryRef().fingerprint(),
                    value.scenarioDraftSetRef().id(), value.scenarioDraftSetRef().revision(),
                    value.scenarioDraftSetRef().fingerprint(), json,
                    value.metadata().updatedAt(), value.metadata().updatedBy().id());
            return candidate;
        } catch (DuplicateKeyException idempotentOrConflict) {
            StoredCorrectnessPublication existing = findPublication(
                    value.scope(), value.publicationId()).orElse(null);
            if (candidate.equals(existing)) return existing;
            throw new IllegalStateException(
                    "Correctness Publication immutable identity conflicts with stored content");
        }
    }

    private Optional<StoredCorrectnessPublication> readPublication(
            ResultSet result,
            EnterpriseScope scope,
            String publicationId
    ) throws SQLException {
        try {
            StoredCorrectnessPublication stored = mapper.readValue(
                    result.getString("canonical_json"), StoredCorrectnessPublication.class);
            CorrectnessPublication value = stored.publication();
            String computed = CorrectnessProtocolFingerprint.fingerprint(mapper, value);
            boolean valid = value.scope().equals(scope)
                    && value.publicationId().equals(publicationId)
                    && stored.publicationFingerprint().equals(
                    result.getString("publication_fingerprint"))
                    && stored.publicationFingerprint().equals(computed)
                    && sameRef(value.definitionRef(), result, "definition")
                    && sameRef(value.inventoryRef(), result, "inventory")
                    && sameRef(value.scenarioDraftSetRef(), result, "scenario_draft_set");
            if (!valid) throw new IllegalStateException(
                    "Stored Correctness Publication integrity check failed");
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to decode Correctness Publication", failure);
        }
    }

    private Optional<StoredCorrectnessPublicationAttempt> readAttempt(
            ResultSet result,
            EnterpriseScope scope,
            String attemptId,
            boolean currentRow
    ) throws SQLException {
        try {
            StoredCorrectnessPublicationAttempt stored = mapper.readValue(
                    result.getString("canonical_json"),
                    StoredCorrectnessPublicationAttempt.class);
            PublicationAttempt value = stored.attempt();
            boolean valid = stored.scope().equals(scope)
                    && value.attemptId().equals(attemptId)
                    && value.stateVersion() == result.getLong("state_version")
                    && value.stage().name().equals(result.getString("stage"));
            if (currentRow) {
                valid = valid && value.idempotencyKeyFingerprint().equals(
                        result.getString("idempotency_key_fingerprint"));
            }
            if (!valid) throw new IllegalStateException(
                    "Stored Correctness Publication Attempt integrity check failed");
            return Optional.of(stored);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Failed to decode Correctness Publication Attempt", failure);
        }
    }

    private void insertAttempt(StoredCorrectnessPublicationAttempt stored, String json) {
        PublicationAttempt value = stored.attempt();
        EnterpriseScope scope = stored.scope();
        jdbc.update("""
                        INSERT INTO rg_correctness_publication_attempts (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            attempt_id, state_version, idempotency_key_fingerprint,
                            stage, canonical_json, updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), value.attemptId(), value.stateVersion(),
                value.idempotencyKeyFingerprint(), value.stage().name(), json,
                value.metadata().updatedAt());
    }

    private int updateAttempt(
            StoredCorrectnessPublicationAttempt stored,
            String json,
            long expectedStateVersion
    ) {
        PublicationAttempt value = stored.attempt();
        EnterpriseScope scope = stored.scope();
        return jdbc.update("""
                        UPDATE rg_correctness_publication_attempts
                        SET state_version = ?, stage = ?, canonical_json = ?, updated_at = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region_id = ? AND attempt_id = ?
                          AND state_version = ? AND idempotency_key_fingerprint = ?
                        """,
                value.stateVersion(), value.stage().name(), json, value.metadata().updatedAt(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), value.attemptId(), expectedStateVersion,
                value.idempotencyKeyFingerprint());
    }

    private void insertHistory(StoredCorrectnessPublicationAttempt stored, String json) {
        PublicationAttempt value = stored.attempt();
        EnterpriseScope scope = stored.scope();
        jdbc.update("""
                        INSERT INTO rg_correctness_publication_attempt_history (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            attempt_id, state_version, stage, canonical_json, recorded_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), value.attemptId(), value.stateVersion(),
                value.stage().name(), json, value.metadata().updatedAt());
    }

    private void insertEvent(CorrectnessPublicationCompleted event) {
        jdbc.update("""
                        INSERT INTO rg_correctness_outbox (
                            tenant_id, organization_id, project_id, environment_id, region_id,
                            event_id, aggregate_kind, aggregate_id, aggregate_revision,
                            event_type, event_json, occurred_at, published_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
                        """,
                event.scope().tenantId(), event.scope().organizationId(),
                event.scope().projectId(), event.scope().environment(), event.scope().region(),
                event.eventId(), event.publicationRef().kind(), event.publicationRef().id(),
                event.publicationRef().revision(), CorrectnessPublicationCompleted.SCHEMA_VERSION,
                serialize(event), event.occurredAt());
    }

    private static void requireCandidate(
            EnterpriseScope scope,
            long expectedStateVersion,
            StoredCorrectnessPublicationAttempt candidate
    ) {
        if (candidate == null || !scope.equals(candidate.scope()) || expectedStateVersion < 0
                || candidate.attempt().stateVersion() != expectedStateVersion + 1) {
            throw new IllegalArgumentException(
                    "Publication Attempt scope and next state version are required");
        }
    }

    private static void requireTransition(
            StoredCorrectnessPublicationAttempt current,
            StoredCorrectnessPublicationAttempt candidate
    ) {
        PublicationAttempt before = current.attempt();
        PublicationAttempt after = candidate.attempt();
        boolean sameCoordinate = before.attemptId().equals(after.attemptId())
                && before.idempotencyKeyFingerprint().equals(after.idempotencyKeyFingerprint())
                && before.coordinate().equals(after.coordinate())
                && before.metadata().createdAt().equals(after.metadata().createdAt())
                && before.metadata().createdBy().equals(after.metadata().createdBy())
                && after.verifiedAssets().containsAll(before.verifiedAssets());
        boolean allowed = switch (before.stage()) {
            case PREPARING -> after.stage() == AttemptStage.COMPILED
                    || after.stage() == AttemptStage.FAILED;
            case COMPILED -> after.stage() == AttemptStage.REGISTERING
                    || after.stage() == AttemptStage.FAILED;
            case REGISTERING -> after.stage() == AttemptStage.REGISTERING
                    || after.stage() == AttemptStage.COMMITTED
                    || after.stage() == AttemptStage.FAILED;
            case FAILED -> after.stage() == AttemptStage.PREPARING;
            case COMMITTED -> false;
        };
        if (!sameCoordinate || !allowed) {
            throw new IllegalArgumentException("Publication Attempt transition is invalid");
        }
    }

    private void requireCommit(
            EnterpriseScope scope,
            StoredCorrectnessPublicationAttempt attempt,
            StoredCorrectnessPublication storedPublication,
            CorrectnessPublicationCompleted event
    ) {
        PublicationAttempt state = attempt.attempt();
        CorrectnessPublication publication = storedPublication.publication();
        String computed = CorrectnessProtocolFingerprint.fingerprint(mapper, publication);
        if (state.stage() != AttemptStage.COMMITTED
                || attempt.compilationReport() == null
                || !attempt.compilationReport().publishable()
                || !scope.equals(publication.scope())
                || !storedPublication.publicationFingerprint().equals(computed)
                || !state.coordinate().definitionRef().equals(publication.definitionRef())
                || !state.coordinate().inventoryRef().equals(publication.inventoryRef())
                || !state.coordinate().scenarioDraftSetRef().equals(publication.scenarioDraftSetRef())
                || !state.coordinate().oracleRefs().equals(publication.oracleRefs())
                || !state.coordinate().assertionSetRefs().equals(publication.assertionSetRefs())
                || !state.coordinate().fixtureAssetRefs().equals(publication.fixtureAssetRefs())
                || !state.coordinate().target().equals(publication.target())
                || !attempt.compilationReport().compilationFingerprint()
                .equals(publication.compilationFingerprint())
                || !event.scope().equals(scope)
                || !event.publicationRef().id().equals(publication.publicationId())
                || !event.publicationRef().fingerprint()
                .equals(storedPublication.publicationFingerprint())
                || !event.compilationFingerprint().equals(publication.compilationFingerprint())) {
            throw new IllegalArgumentException("Publication commit closure is invalid");
        }
    }

    private static boolean sameRef(
            ExactAssetRef ref, ResultSet result, String prefix) throws SQLException {
        return ref.id().equals(result.getString(prefix + "_id"))
                && ref.revision() == result.getLong(prefix + "_revision")
                && ref.fingerprint().equals(result.getString(prefix + "_fingerprint"));
    }

    private String serialize(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Failed to encode correctness publication state", failure);
        }
    }

    private static Object[] scopeArgs(EnterpriseScope scope, String value) {
        return new Object[]{scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environment(), scope.region(), value};
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
        return normalized;
    }

    private static String fingerprint(String value, String field) {
        String normalized = required(value, field);
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be an exact fingerprint");
        }
        return normalized;
    }
}
