package com.leanowtech.bloge.gateway.integration.mirror;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Database-backed immutable Shadow authority key-set log with a durable per-stream floor.
 *
 * <p>Each append locks one complete scope/kind/issuer/key-set head, validates exact succession and
 * irreversible key lifecycle, inserts canonical JSON, and advances the floor in one transaction.
 * A genesis uniqueness race retries the whole transaction because PostgreSQL aborts the current
 * transaction after a duplicate-key error. Reads cross-check every indexed coordinate and
 * fingerprint against strict JSON before returning public key material.</p>
 */
public final class DatabaseReadOnlyShadowAuthorityKeySetRepository
        implements ReadOnlyShadowAuthorityKeySetRepository {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_authority_key_set_heads (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                publication_kind VARCHAR(64) NOT NULL,
                issuer VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                floor_generation BIGINT NOT NULL,
                floor_publication_fingerprint VARCHAR(71) NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    publication_kind, issuer, key_set_id
                )
            )
            """;
    private static final String CREATE_PUBLICATIONS = """
            CREATE TABLE IF NOT EXISTS mirror_shadow_authority_key_set_publications (
                tenant_id VARCHAR(255) NOT NULL,
                organization_id VARCHAR(255) NOT NULL,
                project_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                region VARCHAR(96) NOT NULL,
                publication_kind VARCHAR(64) NOT NULL,
                issuer VARCHAR(512) NOT NULL,
                key_set_id VARCHAR(512) NOT NULL,
                generation BIGINT NOT NULL,
                publication_fingerprint VARCHAR(71) NOT NULL UNIQUE,
                material_fingerprint VARCHAR(71) NOT NULL,
                schema_version VARCHAR(128) NOT NULL,
                publication_json CLOB NOT NULL,
                PRIMARY KEY (
                    tenant_id, organization_id, project_id, environment_id, region,
                    publication_kind, issuer, key_set_id, generation
                )
            )
            """;
    private static final String SELECT_HEAD = """
            SELECT floor_generation, floor_publication_fingerprint
            FROM mirror_shadow_authority_key_set_heads
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND publication_kind = ?
              AND issuer = ? AND key_set_id = ?
            """;
    private static final String SELECT_PUBLICATION = """
            SELECT generation, publication_fingerprint, material_fingerprint,
                   schema_version, publication_json
            FROM mirror_shadow_authority_key_set_publications
            WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
              AND environment_id = ? AND region = ? AND publication_kind = ?
              AND issuer = ? AND key_set_id = ? AND generation = ?
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ReadOnlyShadowAuthorityKeySetIntegrity integrity;
    private final TransactionTemplate transactions;

    /**
     * Creates the durable key-set repository.
     *
     * @param jdbc transaction-aware JDBC boundary
     * @param mapper canonical strict protocol mapper
     * @param integrity key-set content-address verifier
     * @param transactionManager shared transaction manager
     */
    public DatabaseReadOnlyShadowAuthorityKeySetRepository(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            ReadOnlyShadowAuthorityKeySetIntegrity integrity,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.mapper = Objects.requireNonNull(mapper, "mapper").copy()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.integrity = Objects.requireNonNull(integrity, "integrity");
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Creates additive immutable-publication and mutable-floor tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_PUBLICATIONS);
    }

    @Override
    public ReadOnlyShadowAuthorityKeySetPublication append(
            ReadOnlyShadowAuthorityKeySetPublication publication) {
        if (!integrity.canonicalFingerprintVerified(publication)) {
            throw violation(Reason.CANONICAL_INVALID);
        }
        StreamIdentity stream = StreamIdentity.from(publication);
        ReadOnlyShadowAuthorityKeySetPublication stored = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                stored = transactions.execute(status -> {
                    ensureHead(stream);
                    return appendLocked(stream, publication);
                });
                break;
            } catch (HeadInitializationRace raced) {
                if (attempt == 1) {
                    throw violation(Reason.CONCURRENT_INITIALIZATION);
                }
            }
        }
        if (stored == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return stored;
    }

    private ReadOnlyShadowAuthorityKeySetPublication appendLocked(
            StreamIdentity stream,
            ReadOnlyShadowAuthorityKeySetPublication publication) {
        Head head = selectHead(stream, true).orElseThrow(
                () -> violation(Reason.STORED_STATE_CORRUPT));
        long generation = publication.material().generation();
        Optional<ReadOnlyShadowAuthorityKeySetPublication> existing =
                findGeneration(stream, generation);
        if (generation < head.generation()) {
            throw violation(Reason.GENERATION_ROLLBACK);
        }
        if (generation == head.generation()) {
            if (existing.isPresent()
                    && head.publicationFingerprint().equals(
                    publication.publicationFingerprint())
                    && existing.get().publicationFingerprint().equals(
                    publication.publicationFingerprint())) {
                return existing.get();
            }
            throw violation(Reason.GENERATION_FORK);
        }
        if (existing.isPresent()) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        ReadOnlyShadowAuthorityKeySetPublication previous = null;
        if (head.generation() > 0) {
            previous = findGeneration(stream, head.generation()).orElseThrow(
                    () -> violation(Reason.STORED_STATE_CORRUPT));
            if (!previous.publicationFingerprint().equals(head.publicationFingerprint())) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
        requireSuccessor(head, previous, publication);
        insert(stream, publication);
        advanceHead(stream, head, publication);
        return publication;
    }

    @Override
    public Optional<ReadOnlyShadowAuthorityKeySetPublication> latest(StreamIdentity stream) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        Optional<Head> head = selectHead(exact, false);
        if (head.isEmpty() || head.get().generation() == 0) {
            return Optional.empty();
        }
        ReadOnlyShadowAuthorityKeySetPublication publication = findGeneration(
                exact, head.get().generation()).orElseThrow(
                () -> violation(Reason.STORED_STATE_CORRUPT));
        if (!publication.publicationFingerprint().equals(
                head.get().publicationFingerprint())) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return Optional.of(publication);
    }

    @Override
    public Optional<ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor> floor(
            StreamIdentity stream) {
        return selectHead(Objects.requireNonNull(stream, "stream"), false)
                .flatMap(head -> head.generation() == 0
                        ? Optional.empty()
                        : Optional.of(new ReadOnlyShadowAuthorityKeySetIntegrity.TrustedFloor(
                        stream.keySetId(), head.generation(),
                        head.publicationFingerprint())));
    }

    @Override
    public ReadOnlyShadowAuthorityKeySetPage page(
            StreamIdentity stream,
            long afterGeneration,
            String afterPublicationFingerprint,
            int limit,
            Instant generatedAt) {
        StreamIdentity exact = Objects.requireNonNull(stream, "stream");
        String checkpoint = ReadOnlyShadowAuthoritySeal.normalized(
                afterPublicationFingerprint);
        if (afterGeneration < 0
                || afterGeneration == 0 && !checkpoint.isBlank()
                || afterGeneration > 0 && !checkpoint.matches("sha256:[a-f0-9]{64}")) {
            throw violation(Reason.CHECKPOINT_INVALID);
        }
        int boundedLimit = Math.max(1, Math.min(
                ReadOnlyShadowAuthorityKeySetPage.MAXIMUM_PUBLICATIONS, limit));
        ReadOnlyShadowAuthorityKeySetPage page = transactions.execute(status -> {
            Head head = selectHead(exact, true).orElseThrow(
                    () -> violation(Reason.CHECKPOINT_INVALID));
            if (afterGeneration > head.generation()) {
                throw violation(Reason.CHECKPOINT_INVALID);
            }
            if (afterGeneration > 0) {
                ReadOnlyShadowAuthorityKeySetPublication checkpointPublication =
                        findGeneration(exact, afterGeneration).orElseThrow(
                                () -> violation(Reason.CHECKPOINT_INVALID));
                if (!checkpointPublication.publicationFingerprint().equals(checkpoint)) {
                    throw violation(Reason.CHECKPOINT_INVALID);
                }
            }
            List<ReadOnlyShadowAuthorityKeySetPublication> values =
                    readRange(exact, afterGeneration, head.generation(), boundedLimit);
            long through = values.isEmpty()
                    ? afterGeneration : values.getLast().material().generation();
            ReadOnlyShadowAuthorityKeySetPublication highWater = head.generation() == 0
                    ? null : findGeneration(exact, head.generation()).orElseThrow(
                    () -> violation(Reason.STORED_STATE_CORRUPT));
            return new ReadOnlyShadowAuthorityKeySetPage(
                    "", generatedAt, exact.scope(), exact.publicationKind(), exact.issuer(),
                    exact.keySetId(), afterGeneration, checkpoint, through, head.generation(),
                    head.publicationFingerprint(), highWater,
                    through < head.generation(), values);
        });
        if (page == null) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return page;
    }

    @Override
    public boolean available() {
        try {
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mirror_shadow_authority_key_set_heads", Long.class);
            jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mirror_shadow_authority_key_set_publications",
                    Long.class);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void requireSuccessor(
            Head head,
            ReadOnlyShadowAuthorityKeySetPublication previous,
            ReadOnlyShadowAuthorityKeySetPublication next) {
        if (head.generation() == 0) {
            if (next.material().generation() != 1
                    || !next.material().previousPublicationFingerprint().isBlank()) {
                throw violation(Reason.BOOTSTRAP_GENERATION_INVALID);
            }
            return;
        }
        if (next.material().generation() > head.generation() + 1) {
            throw violation(Reason.GENERATION_GAP);
        }
        if (!next.material().previousPublicationFingerprint().equals(
                head.publicationFingerprint())) {
            throw violation(Reason.PREDECESSOR_MISMATCH);
        }
        if (previous == null
                || !StreamIdentity.from(previous).equals(StreamIdentity.from(next))
                || next.material().issuedAt().isBefore(previous.material().issuedAt())) {
            throw violation(Reason.IDENTITY_MISMATCH);
        }
        requireLegalKeyEvolution(previous.material().keys(), next.material().keys());
    }

    private static void requireLegalKeyEvolution(
            List<ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey> previous,
            List<ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey> next) {
        Map<String, ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey> nextById =
                new HashMap<>();
        next.forEach(key -> nextById.put(key.keyId(), key));
        for (ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey before : previous) {
            ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey after =
                    nextById.get(before.keyId());
            if (after == null
                    || !before.algorithm().equals(after.algorithm())
                    || !before.encodedPublicKey().equals(after.encodedPublicKey())
                    || !before.notBefore().equals(after.notBefore())
                    || !before.notAfter().equals(after.notAfter())
                    || !legalTransition(before, after)) {
                throw violation(Reason.KEY_LIFECYCLE_INVALID);
            }
        }
    }

    private static boolean legalTransition(
            ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey before,
            ReadOnlyShadowAuthorityKeySetPublication.AuthorityKey after) {
        return switch (before.state()) {
            case ACTIVE -> after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.ACTIVE
                    && after.retiredAt() == null
                    || after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.RETIRED
                    && after.retiredAt() != null
                    || after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED
                    && after.retiredAt() == null;
            case RETIRED -> after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.RETIRED
                    && before.retiredAt().equals(after.retiredAt())
                    || after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED
                    && after.retiredAt() == null;
            case REVOKED -> after.state() == ReadOnlyShadowAuthorityIntegrity.KeyState.REVOKED
                    && after.retiredAt() == null;
        };
    }

    private void ensureHead(StreamIdentity stream) {
        if (selectHead(stream, false).isPresent()) {
            return;
        }
        CapabilitySnapshot.Scope scope = stream.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_shadow_authority_key_set_heads (
                                tenant_id, organization_id, project_id, environment_id, region,
                                publication_kind, issuer, key_set_id,
                                floor_generation, floor_publication_fingerprint
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, '')
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), stream.publicationKind().name(),
                    stream.issuer(), stream.keySetId());
        } catch (DuplicateKeyException raced) {
            throw new HeadInitializationRace();
        }
    }

    private Optional<Head> selectHead(StreamIdentity stream, boolean lock) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<Head> rows = jdbc.query(SELECT_HEAD + (lock ? " FOR UPDATE" : ""),
                (rs, rowNumber) -> new Head(
                        rs.getLong("floor_generation"),
                        rs.getString("floor_publication_fingerprint")),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.publicationKind().name(),
                stream.issuer(), stream.keySetId());
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private Optional<ReadOnlyShadowAuthorityKeySetPublication> findGeneration(
            StreamIdentity stream, long generation) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<ReadOnlyShadowAuthorityKeySetPublication> rows = jdbc.query(
                SELECT_PUBLICATION,
                (rs, rowNumber) -> deserialize(rs, stream),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.publicationKind().name(),
                stream.issuer(), stream.keySetId(), generation);
        if (rows.size() > 1) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
        return rows.stream().findFirst();
    }

    private List<ReadOnlyShadowAuthorityKeySetPublication> readRange(
            StreamIdentity stream,
            long afterGeneration,
            long throughGeneration,
            int limit) {
        CapabilitySnapshot.Scope scope = stream.scope();
        List<ReadOnlyShadowAuthorityKeySetPublication> rows = jdbc.query("""
                        SELECT generation, publication_fingerprint, material_fingerprint,
                               schema_version, publication_json
                        FROM mirror_shadow_authority_key_set_publications
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND publication_kind = ?
                          AND issuer = ? AND key_set_id = ?
                          AND generation > ? AND generation <= ?
                        ORDER BY generation ASC
                        LIMIT ?
                        """,
                (rs, rowNumber) -> deserialize(rs, stream),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.publicationKind().name(),
                stream.issuer(), stream.keySetId(), afterGeneration, throughGeneration, limit);
        long expected = afterGeneration + 1;
        for (ReadOnlyShadowAuthorityKeySetPublication publication : rows) {
            if (publication.material().generation() != expected++) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
        return List.copyOf(rows);
    }

    private ReadOnlyShadowAuthorityKeySetPublication deserialize(
            ResultSet rs, StreamIdentity expected) throws SQLException {
        try {
            ReadOnlyShadowAuthorityKeySetPublication publication = mapper.readValue(
                    rs.getString("publication_json"),
                    ReadOnlyShadowAuthorityKeySetPublication.class);
            if (!integrity.canonicalFingerprintVerified(publication)
                    || !StreamIdentity.from(publication).equals(expected)
                    || publication.material().generation() != rs.getLong("generation")
                    || !publication.publicationFingerprint().equals(
                    rs.getString("publication_fingerprint"))
                    || !publication.materialFingerprint().equals(
                    rs.getString("material_fingerprint"))
                    || !publication.schemaVersion().equals(rs.getString("schema_version"))) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
            return publication;
        } catch (JsonProcessingException | IllegalArgumentException malformed) {
            throw violation(Reason.STORED_STATE_CORRUPT);
        }
    }

    private void insert(
            StreamIdentity stream, ReadOnlyShadowAuthorityKeySetPublication publication) {
        CapabilitySnapshot.Scope scope = stream.scope();
        try {
            jdbc.update("""
                            INSERT INTO mirror_shadow_authority_key_set_publications (
                                tenant_id, organization_id, project_id, environment_id, region,
                                publication_kind, issuer, key_set_id, generation,
                                publication_fingerprint, material_fingerprint, schema_version,
                                publication_json
                            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                    scope.tenantId(), scope.organizationId(), scope.projectId(),
                    scope.environmentId(), scope.region(), stream.publicationKind().name(),
                    stream.issuer(), stream.keySetId(), publication.material().generation(),
                    publication.publicationFingerprint(), publication.materialFingerprint(),
                    publication.schemaVersion(), serialize(publication));
        } catch (DuplicateKeyException collision) {
            throw violation(Reason.CONTENT_ADDRESS_CONFLICT);
        }
    }

    private void advanceHead(
            StreamIdentity stream,
            Head head,
            ReadOnlyShadowAuthorityKeySetPublication publication) {
        CapabilitySnapshot.Scope scope = stream.scope();
        int advanced = jdbc.update("""
                        UPDATE mirror_shadow_authority_key_set_heads
                        SET floor_generation = ?, floor_publication_fingerprint = ?
                        WHERE tenant_id = ? AND organization_id = ? AND project_id = ?
                          AND environment_id = ? AND region = ? AND publication_kind = ?
                          AND issuer = ? AND key_set_id = ?
                          AND floor_generation = ? AND floor_publication_fingerprint = ?
                        """,
                publication.material().generation(), publication.publicationFingerprint(),
                scope.tenantId(), scope.organizationId(), scope.projectId(),
                scope.environmentId(), scope.region(), stream.publicationKind().name(),
                stream.issuer(), stream.keySetId(), head.generation(),
                head.publicationFingerprint());
        if (advanced != 1) {
            throw violation(Reason.GENERATION_FORK);
        }
    }

    private String serialize(ReadOnlyShadowAuthorityKeySetPublication publication) {
        try {
            return mapper.writeValueAsString(publication);
        } catch (JsonProcessingException invalid) {
            throw violation(Reason.CANONICAL_INVALID);
        }
    }

    private static ReadOnlyShadowAuthorityKeySetRepository.Violation violation(Reason reason) {
        return new ReadOnlyShadowAuthorityKeySetRepository.Violation(reason);
    }

    private record Head(long generation, String publicationFingerprint) {
        private Head {
            publicationFingerprint =
                    ReadOnlyShadowAuthoritySeal.normalized(publicationFingerprint);
            if (generation < 0 || generation == 0 && !publicationFingerprint.isBlank()
                    || generation > 0 && !publicationFingerprint.matches("sha256:[a-f0-9]{64}")) {
                throw violation(Reason.STORED_STATE_CORRUPT);
            }
        }
    }

    private static final class HeadInitializationRace extends RuntimeException {
    }
}
