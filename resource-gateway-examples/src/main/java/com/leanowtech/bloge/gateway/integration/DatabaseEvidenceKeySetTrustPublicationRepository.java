package com.leanowtech.bloge.gateway.integration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Database-backed append-only evidence trust log with a per-log transactional head lock.
 *
 * <p>The head row is created before append and locked with {@code SELECT ... FOR UPDATE}; the
 * publication and permanent revocation index are then committed in the same transaction. Unique
 * sequence and fingerprint constraints make same-sequence split views fail closed across instances.</p>
 */
public final class DatabaseEvidenceKeySetTrustPublicationRepository
        implements EvidenceKeySetTrustPublicationRepository {
    private static final String CREATE_HEADS = """
            CREATE TABLE IF NOT EXISTS evidence_key_set_trust_log_heads (
                log_id VARCHAR(255) PRIMARY KEY,
                head_sequence BIGINT NOT NULL,
                head_fingerprint VARCHAR(96) NOT NULL
            )
            """;
    private static final String CREATE_PUBLICATIONS = """
            CREATE TABLE IF NOT EXISTS evidence_key_set_trust_publications (
                log_id VARCHAR(255) NOT NULL,
                publication_sequence BIGINT NOT NULL,
                publication_fingerprint VARCHAR(96) NOT NULL UNIQUE,
                publication_json CLOB NOT NULL,
                PRIMARY KEY (log_id, publication_sequence)
            )
            """;
    private static final String CREATE_REVOCATIONS = """
            CREATE TABLE IF NOT EXISTS evidence_key_set_trust_revoked_pins (
                log_id VARCHAR(255) NOT NULL,
                snapshot_fingerprint VARCHAR(96) NOT NULL,
                revoked_sequence BIGINT NOT NULL,
                PRIMARY KEY (log_id, snapshot_fingerprint)
            )
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    /** Creates the durable log repository. */
    public DatabaseEvidenceKeySetTrustPublicationRepository(
            JdbcTemplate jdbc, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        if (jdbc == null || transactionManager == null) {
            throw new IllegalArgumentException("Evidence trust log database and transaction manager are required");
        }
        this.jdbc = jdbc;
        this.objectMapper = objectMapper == null
                ? new ObjectMapper().findAndRegisterModules() : objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Creates immutable log, head, and permanent-revocation tables. */
    @PostConstruct
    void init() {
        jdbc.execute(CREATE_HEADS);
        jdbc.execute(CREATE_PUBLICATIONS);
        jdbc.execute(CREATE_REVOCATIONS);
    }

    @Override
    public EvidenceKeySetTrustPublication append(EvidenceKeySetTrustPublication publication) {
        if (publication == null || !publication.fingerprintVerified(objectMapper)) {
            throw new EvidenceKeySetTrustChain.ChainViolation(
                    EvidenceKeySetTrustChain.Reason.MATERIAL_INVALID);
        }
        ensureHead(publication.logId());
        EvidenceKeySetTrustPublication result = transactions.execute(status -> appendLocked(publication));
        if (result == null) {
            throw new IllegalStateException("Evidence trust publication transaction returned no result");
        }
        return result;
    }

    private EvidenceKeySetTrustPublication appendLocked(EvidenceKeySetTrustPublication publication) {
        Head head = jdbc.query("""
                        SELECT head_sequence, head_fingerprint
                        FROM evidence_key_set_trust_log_heads WHERE log_id = ? FOR UPDATE
                        """, (rs, rowNum) -> new Head(rs.getLong("head_sequence"),
                        rs.getString("head_fingerprint")), publication.logId())
                .stream().findFirst().orElseThrow(() ->
                        new IllegalStateException("Evidence trust log head is unavailable"));
        EvidenceKeySetTrustPublication existing = findSequence(
                publication.logId(), publication.sequence()).orElse(null);
        if (existing != null) {
            if (existing.publicationFingerprint().equals(publication.publicationFingerprint())) {
                return existing;
            }
            throw new EvidenceKeySetTrustChain.ChainViolation(
                    EvidenceKeySetTrustChain.Reason.SEQUENCE_FORK);
        }
        EvidenceKeySetTrustPublication previous = head.sequence == 0
                ? null : findSequence(publication.logId(), head.sequence).orElseThrow(() ->
                new IllegalStateException("Evidence trust log head publication is missing"));
        Set<String> revoked = new HashSet<>(jdbc.queryForList("""
                SELECT snapshot_fingerprint FROM evidence_key_set_trust_revoked_pins WHERE log_id = ?
                """, String.class, publication.logId()));
        EvidenceKeySetTrustChain.requireNext(previous, publication, revoked);
        try {
            jdbc.update("""
                            INSERT INTO evidence_key_set_trust_publications
                                (log_id, publication_sequence, publication_fingerprint, publication_json)
                            VALUES (?, ?, ?, ?)
                            """, publication.logId(), publication.sequence(),
                    publication.publicationFingerprint(), write(publication));
            for (EvidenceKeySetTrustPublication.SnapshotPin pin : publication.pins()) {
                if (pin.state() == EvidenceKeySetTrustPublication.PinState.REVOKED
                        && !revoked.contains(pin.snapshotFingerprint())) {
                    jdbc.update("""
                                    INSERT INTO evidence_key_set_trust_revoked_pins
                                        (log_id, snapshot_fingerprint, revoked_sequence)
                                    VALUES (?, ?, ?)
                                    """, publication.logId(), pin.snapshotFingerprint(),
                            publication.sequence());
                }
            }
            int updated = jdbc.update("""
                            UPDATE evidence_key_set_trust_log_heads
                            SET head_sequence = ?, head_fingerprint = ?
                            WHERE log_id = ? AND head_sequence = ? AND head_fingerprint = ?
                            """, publication.sequence(), publication.publicationFingerprint(),
                    publication.logId(), head.sequence, head.fingerprint);
            if (updated != 1) {
                throw new EvidenceKeySetTrustChain.ChainViolation(
                        EvidenceKeySetTrustChain.Reason.SEQUENCE_FORK);
            }
            return publication;
        } catch (DuplicateKeyException conflict) {
            EvidenceKeySetTrustPublication raced = findSequence(
                    publication.logId(), publication.sequence()).orElse(null);
            if (raced != null
                    && raced.publicationFingerprint().equals(publication.publicationFingerprint())) {
                return raced;
            }
            throw new EvidenceKeySetTrustChain.ChainViolation(
                    EvidenceKeySetTrustChain.Reason.SEQUENCE_FORK);
        }
    }

    @Override
    public Optional<EvidenceKeySetTrustPublication> latest(String logId) {
        return jdbc.query("""
                        SELECT publication_json FROM evidence_key_set_trust_publications
                        WHERE log_id = ? ORDER BY publication_sequence DESC LIMIT 1
                        """, (rs, rowNum) -> read(rs.getString("publication_json")), normalized(logId))
                .stream().findFirst();
    }

    @Override
    public List<EvidenceKeySetTrustPublication> readAfter(
            String logId, long afterSequence, int limit) {
        return jdbc.query("""
                        SELECT publication_json FROM evidence_key_set_trust_publications
                        WHERE log_id = ? AND publication_sequence > ?
                        ORDER BY publication_sequence ASC LIMIT ?
                        """, (rs, rowNum) -> read(rs.getString("publication_json")), normalized(logId),
                Math.max(0, afterSequence),
                Math.max(1, Math.min(EvidenceKeySetTrustBundle.MAX_PUBLICATIONS, limit)));
    }

    @Override
    public long highWaterSequence(String logId) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(MAX(publication_sequence), 0)
                FROM evidence_key_set_trust_publications WHERE log_id = ?
                """, Long.class, normalized(logId));
        return value == null ? 0 : value;
    }

    private Optional<EvidenceKeySetTrustPublication> findSequence(String logId, long sequence) {
        return jdbc.query("""
                        SELECT publication_json FROM evidence_key_set_trust_publications
                        WHERE log_id = ? AND publication_sequence = ?
                        """, (rs, rowNum) -> read(rs.getString("publication_json")), logId, sequence)
                .stream().findFirst();
    }

    private void ensureHead(String logId) {
        try {
            jdbc.update("""
                    INSERT INTO evidence_key_set_trust_log_heads
                        (log_id, head_sequence, head_fingerprint) VALUES (?, 0, '')
                    """, logId);
        } catch (DuplicateKeyException ignored) {
            // Another process already initialized this log identity.
        }
    }

    private String write(EvidenceKeySetTrustPublication publication) {
        try {
            return objectMapper.writeValueAsString(publication);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Evidence trust publication cannot be serialized", failure);
        }
    }

    private EvidenceKeySetTrustPublication read(String json) {
        try {
            EvidenceKeySetTrustPublication publication = objectMapper.readValue(
                    json, EvidenceKeySetTrustPublication.class);
            if (!publication.fingerprintVerified(objectMapper)) {
                throw new IllegalStateException("Persisted evidence trust publication fingerprint is invalid");
            }
            return publication;
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Evidence trust publication cannot be deserialized", failure);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private record Head(long sequence, String fingerprint) {
    }
}
