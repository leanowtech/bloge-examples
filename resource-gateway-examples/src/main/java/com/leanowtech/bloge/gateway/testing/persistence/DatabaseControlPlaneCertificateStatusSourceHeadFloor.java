package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusSourceHead;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusSourceHeadFloor;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusTrustStore;
import com.leanowtech.bloge.gateway.testing.evidence.ProtocolFingerprint;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Database-clock monotonic floor for signed certificate-status source heads.
 *
 * <p>All writers serialize on the same deployment-scope lock used by the publication floor.
 * Signature and binding verification are repeated inside the transaction using database time.
 * Whole-row fingerprints and an append-only attestation identity journal detect direct mutation,
 * rollback, same-sequence forks, attestation identity reuse, and stale same-head renewal.</p>
 */
public final class DatabaseControlPlaneCertificateStatusSourceHeadFloor
        implements ControlPlaneCertificateStatusSourceHeadFloor {

    private static final Duration MAXIMUM_DATABASE_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final Duration MAXIMUM_ATTESTATION_LIFETIME = Duration.ofHours(24);
    private static final String HEAD_SCHEMA =
            "bloge.controlPlaneCertificateStatusSourceHeadFloorHead.v1";
    private static final String JOURNAL_SCHEMA =
            "bloge.controlPlaneCertificateStatusSourceHeadFloorJournal.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ControlPlaneCertificateStatusTrustStore trustStore;
    private final String deploymentScopeId;
    private final long baselineSequence;
    private final String baselinePublicationFingerprint;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one durable source-head floor bound to a deployment-pinned baseline.
     *
     * @param jdbc testing-control-plane JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param trustStore local public-key-only source-head verifier
     * @param deploymentScopeId exact governed deployment scope
     * @param baselineSequence deployment-pinned source baseline sequence
     * @param baselinePublicationFingerprint deployment-pinned baseline fingerprint
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseControlPlaneCertificateStatusSourceHeadFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ControlPlaneCertificateStatusTrustStore trustStore,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.trustStore = Objects.requireNonNull(trustStore, "trustStore");
        this.deploymentScopeId = normalized(deploymentScopeId);
        this.baselineSequence = baselineSequence;
        this.baselinePublicationFingerprint = normalized(baselinePublicationFingerprint);
        if (!IDENTIFIER.matcher(this.deploymentScopeId).matches()
                || baselineSequence < 0
                || !FINGERPRINT.matcher(this.baselinePublicationFingerprint).matches()) {
            throw invalid("Certificate status source-head floor configuration is invalid");
        }
        PlatformTransactionManager manager = Objects.requireNonNull(
                transactionManager, "transactionManager");
        mutations = new TransactionTemplate(manager);
        mutations.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        mutations.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        reads = new TransactionTemplate(manager);
        reads.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        reads.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        reads.setReadOnly(true);
    }

    /** Creates additive source-head, attestation journal, and shared scope-lock tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_scope_locks (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_source_heads (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY,
                    baseline_sequence BIGINT NOT NULL,
                    baseline_publication_fingerprint VARCHAR(71) NOT NULL,
                    head_sequence BIGINT NOT NULL,
                    head_publication_fingerprint VARCHAR(71) NOT NULL,
                    attestation_id VARCHAR(255) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    issued_at TIMESTAMP WITH TIME ZONE,
                    expires_at TIMESTAMP WITH TIME ZONE,
                    observed_at TIMESTAMP WITH TIME ZONE,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_source_head_journal (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    attestation_id VARCHAR(255) NOT NULL,
                    attestation_fingerprint VARCHAR(71) NOT NULL,
                    head_sequence BIGINT NOT NULL,
                    head_publication_fingerprint VARCHAR(71) NOT NULL,
                    issued_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, attestation_id)
                )
                """);
        mutations.executeWithoutResult(status -> {
            lock();
            HeadRow current = head();
            if (current == null) {
                persistInitialHead();
            } else {
                validateHead(current);
                validateJournalHead(current);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(ControlPlaneCertificateStatusSourceHead sourceHead) {
        ControlPlaneCertificateStatusSourceHead required = Objects.requireNonNull(
                sourceHead, "sourceHead");
        Acceptance result = mutations.execute(status -> {
            lock();
            HeadRow current = validateHead(requireHead());
            validateJournalHead(current);
            Instant now = databaseNow();
            ControlPlaneCertificateStatusTrustStore.SourceHeadVerification verification =
                    trustStore.verifySourceHead(required,
                            new ControlPlaneCertificateStatusTrustStore.ExpectedBinding(
                                    deploymentScopeId), now);
            var material = required.material();
            if (!verification.verified()
                    || !verification.attestationId().equals(material.attestationId())
                    || !verification.attestationFingerprint().equals(
                    required.materialFingerprint())
                    || verification.headSequence() != material.headSequence()
                    || !verification.headPublicationFingerprint().equals(
                    material.headPublicationFingerprint())) {
                throw invalid("Certificate status source head is not authorized");
            }
            validateDatabaseTime(material, now);
            validateBaseline(material);

            JournalRow priorIdentity = journal(material.attestationId());
            if (priorIdentity != null) {
                if (!priorIdentity.valid(objectMapper)) {
                    throw new IllegalStateException(
                            "Certificate status source-head journal is corrupt");
                }
                if (current.attestationId().equals(material.attestationId())
                        && current.attestationFingerprint().equals(
                        required.materialFingerprint())
                        && priorIdentity.matches(required)) {
                    return new Acceptance(AcceptanceStatus.REPLAYED, snapshot(current));
                }
                throw invalid(
                        "Certificate status source-head attestation identity was already used");
            }

            if (material.headSequence() < current.headSequence()) {
                throw invalid("Certificate status source head rolled back");
            }
            AcceptanceStatus outcome = current.initialized()
                    ? AcceptanceStatus.ADVANCED : AcceptanceStatus.INITIALIZED;
            if (material.headSequence() == current.headSequence()) {
                if (!material.headPublicationFingerprint().equals(
                        current.headPublicationFingerprint())) {
                    throw invalid("Certificate status source head forked");
                }
                if (current.initialized()
                        && !material.issuedAt().isAfter(current.issuedAt())) {
                    throw invalid("Certificate status source-head renewal is not newer");
                } else if (current.initialized()) {
                    outcome = AcceptanceStatus.RENEWED;
                }
            }

            persistJournal(required, now);
            persistHead(required, now);
            HeadRow stored = validateHead(requireHead());
            validateJournalHead(stored);
            return new Acceptance(outcome, snapshot(stored));
        });
        return Objects.requireNonNull(result, "certificate status source-head acceptance");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> {
            HeadRow current = validateHead(requireHead());
            validateJournalHead(current);
            return snapshot(current);
        });
        return Objects.requireNonNull(result, "certificate status source-head snapshot");
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void validateDatabaseTime(
            ControlPlaneCertificateStatusSourceHead.Material material, Instant now) {
        if (material.issuedAt().isAfter(now.plus(MAXIMUM_DATABASE_CLOCK_SKEW))
                || !now.isBefore(material.expiresAt())
                || Duration.between(material.issuedAt(), material.expiresAt())
                .compareTo(MAXIMUM_ATTESTATION_LIFETIME) > 0) {
            throw invalid("Certificate status source head is not current at database time");
        }
    }

    private void validateBaseline(ControlPlaneCertificateStatusSourceHead.Material material) {
        if (material.headSequence() < baselineSequence
                || material.headSequence() == baselineSequence
                && !material.headPublicationFingerprint().equals(
                baselinePublicationFingerprint)) {
            throw invalid("Certificate status source head conflicts with the baseline");
        }
    }

    private void validateJournalHead(HeadRow current) {
        if (!current.initialized()) {
            return;
        }
        JournalRow journal = journal(current.attestationId());
        if (journal == null || !journal.valid(objectMapper)
                || !journal.attestationFingerprint().equals(
                current.attestationFingerprint())
                || journal.headSequence() != current.headSequence()
                || !journal.headPublicationFingerprint().equals(
                current.headPublicationFingerprint())
                || !journal.issuedAt().equals(current.issuedAt())
                || !journal.expiresAt().equals(current.expiresAt())
                || !journal.observedAt().equals(current.observedAt())) {
            throw new IllegalStateException(
                    "Certificate status source-head journal head is corrupt");
        }
    }

    private void persistInitialHead() {
        HeadMaterial material = new HeadMaterial(HEAD_SCHEMA, deploymentScopeId,
                baselineSequence, baselinePublicationFingerprint, baselineSequence,
                baselinePublicationFingerprint, "", "", null, null, null);
        jdbc.update("""
                INSERT INTO rg_cp_cert_status_source_heads (
                    deployment_scope_id, baseline_sequence,
                    baseline_publication_fingerprint, head_sequence,
                    head_publication_fingerprint, attestation_id,
                    attestation_fingerprint, issued_at, expires_at, observed_at,
                    record_fingerprint)
                VALUES (?, ?, ?, ?, ?, '', '', NULL, NULL, NULL, ?)
                """, deploymentScopeId, baselineSequence, baselinePublicationFingerprint,
                baselineSequence, baselinePublicationFingerprint,
                ProtocolFingerprint.of(objectMapper, material));
    }

    private void persistJournal(
            ControlPlaneCertificateStatusSourceHead sourceHead, Instant observedAt) {
        var material = sourceHead.material();
        JournalMaterial record = new JournalMaterial(JOURNAL_SCHEMA, deploymentScopeId,
                material.attestationId(), sourceHead.materialFingerprint(),
                material.headSequence(), material.headPublicationFingerprint(),
                material.issuedAt(), material.expiresAt(), observedAt);
        jdbc.update("""
                INSERT INTO rg_cp_cert_status_source_head_journal (
                    deployment_scope_id, attestation_id, attestation_fingerprint,
                    head_sequence, head_publication_fingerprint, issued_at,
                    expires_at, observed_at, record_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, deploymentScopeId, material.attestationId(),
                sourceHead.materialFingerprint(), material.headSequence(),
                material.headPublicationFingerprint(), Timestamp.from(material.issuedAt()),
                Timestamp.from(material.expiresAt()), Timestamp.from(observedAt),
                ProtocolFingerprint.of(objectMapper, record));
    }

    private void persistHead(
            ControlPlaneCertificateStatusSourceHead sourceHead, Instant observedAt) {
        var material = sourceHead.material();
        HeadMaterial record = new HeadMaterial(HEAD_SCHEMA, deploymentScopeId,
                baselineSequence, baselinePublicationFingerprint, material.headSequence(),
                material.headPublicationFingerprint(), material.attestationId(),
                sourceHead.materialFingerprint(), material.issuedAt(), material.expiresAt(),
                observedAt);
        int updated = jdbc.update("""
                UPDATE rg_cp_cert_status_source_heads
                SET head_sequence = ?, head_publication_fingerprint = ?,
                    attestation_id = ?, attestation_fingerprint = ?, issued_at = ?,
                    expires_at = ?, observed_at = ?, record_fingerprint = ?
                WHERE deployment_scope_id = ?
                """, material.headSequence(), material.headPublicationFingerprint(),
                material.attestationId(), sourceHead.materialFingerprint(),
                Timestamp.from(material.issuedAt()), Timestamp.from(material.expiresAt()),
                Timestamp.from(observedAt), ProtocolFingerprint.of(objectMapper, record),
                deploymentScopeId);
        if (updated != 1) {
            throw new IllegalStateException("Certificate status source head is unavailable");
        }
    }

    private HeadRow validateHead(HeadRow row) {
        if (!row.valid(objectMapper, deploymentScopeId, baselineSequence,
                baselinePublicationFingerprint)) {
            throw new IllegalStateException(
                    "Certificate status source head is corrupt or drifted");
        }
        return row;
    }

    private HeadRow requireHead() {
        HeadRow current = head();
        if (current == null) {
            throw new IllegalStateException(
                    "Certificate status source-head floor is not initialized");
        }
        return current;
    }

    private HeadRow head() {
        List<HeadRow> rows = jdbc.query("""
                SELECT deployment_scope_id, baseline_sequence,
                       baseline_publication_fingerprint, head_sequence,
                       head_publication_fingerprint, attestation_id,
                       attestation_fingerprint, issued_at, expires_at, observed_at,
                       record_fingerprint
                FROM rg_cp_cert_status_source_heads WHERE deployment_scope_id = ?
                """, this::headRow, deploymentScopeId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate certificate status source head");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private JournalRow journal(String attestationId) {
        List<JournalRow> rows = jdbc.query("""
                SELECT deployment_scope_id, attestation_id, attestation_fingerprint,
                       head_sequence, head_publication_fingerprint, issued_at,
                       expires_at, observed_at, record_fingerprint
                FROM rg_cp_cert_status_source_head_journal
                WHERE deployment_scope_id = ? AND attestation_id = ?
                """, this::journalRow, deploymentScopeId, attestationId);
        if (rows.size() > 1) {
            throw new IllegalStateException(
                    "Duplicate certificate status source-head journal row");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Snapshot snapshot(HeadRow head) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, deploymentScopeId, baselineSequence,
                baselinePublicationFingerprint, head.headSequence(),
                head.headPublicationFingerprint(), head.attestationId(),
                head.attestationFingerprint(), head.issuedAt(), head.expiresAt(),
                head.observedAt());
    }

    private void lock() {
        jdbc.update("""
                MERGE INTO rg_cp_cert_status_scope_locks (deployment_scope_id)
                KEY (deployment_scope_id) VALUES (?)
                """, deploymentScopeId);
        jdbc.queryForObject("""
                SELECT deployment_scope_id FROM rg_cp_cert_status_scope_locks
                WHERE deployment_scope_id = ? FOR UPDATE
                """, String.class, deploymentScopeId);
    }

    private Instant databaseNow() {
        Timestamp timestamp = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class);
        if (timestamp == null) {
            throw new IllegalStateException(
                    "Certificate status source-head database time is unavailable");
        }
        return timestamp.toInstant();
    }

    private HeadRow headRow(ResultSet result, int rowNumber) throws SQLException {
        return new HeadRow(result.getString("deployment_scope_id"),
                result.getLong("baseline_sequence"),
                result.getString("baseline_publication_fingerprint"),
                result.getLong("head_sequence"),
                result.getString("head_publication_fingerprint"),
                result.getString("attestation_id"),
                result.getString("attestation_fingerprint"),
                instant(result, "issued_at"), instant(result, "expires_at"),
                instant(result, "observed_at"), result.getString("record_fingerprint"));
    }

    private JournalRow journalRow(ResultSet result, int rowNumber) throws SQLException {
        return new JournalRow(result.getString("deployment_scope_id"),
                result.getString("attestation_id"),
                result.getString("attestation_fingerprint"),
                result.getLong("head_sequence"),
                result.getString("head_publication_fingerprint"),
                Objects.requireNonNull(instant(result, "issued_at"), "issued_at"),
                Objects.requireNonNull(instant(result, "expires_at"), "expires_at"),
                Objects.requireNonNull(instant(result, "observed_at"), "observed_at"),
                result.getString("record_fingerprint"));
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record HeadMaterial(
            String schemaVersion,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long headSequence,
            String headPublicationFingerprint,
            String attestationId,
            String attestationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt) {
    }

    private record HeadRow(
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long headSequence,
            String headPublicationFingerprint,
            String attestationId,
            String attestationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            String recordFingerprint) {

        private HeadMaterial material() {
            return new HeadMaterial(HEAD_SCHEMA, deploymentScopeId, baselineSequence,
                    baselinePublicationFingerprint, headSequence, headPublicationFingerprint,
                    attestationId, attestationFingerprint, issuedAt, expiresAt, observedAt);
        }

        private boolean initialized() {
            return !attestationId.isBlank();
        }

        private boolean valid(
                ObjectMapper mapper,
                String expectedScope,
                long expectedBaselineSequence,
                String expectedBaselineFingerprint) {
            boolean initial = !initialized();
            boolean live = IDENTIFIER.matcher(attestationId).matches()
                    && FINGERPRINT.matcher(attestationFingerprint).matches()
                    && issuedAt != null && expiresAt != null && observedAt != null
                    && expiresAt.isAfter(issuedAt) && expiresAt.isAfter(observedAt);
            return expectedScope.equals(deploymentScopeId)
                    && baselineSequence == expectedBaselineSequence
                    && expectedBaselineFingerprint.equals(baselinePublicationFingerprint)
                    && headSequence >= baselineSequence
                    && FINGERPRINT.matcher(headPublicationFingerprint).matches()
                    && (headSequence != baselineSequence
                    || headPublicationFingerprint.equals(baselinePublicationFingerprint))
                    && (initial && attestationFingerprint.isBlank()
                    && headSequence == baselineSequence
                    && headPublicationFingerprint.equals(baselinePublicationFingerprint)
                    && issuedAt == null && expiresAt == null && observedAt == null || live)
                    && ProtocolFingerprint.of(mapper, material()).equals(recordFingerprint);
        }
    }

    private record JournalMaterial(
            String schemaVersion,
            String deploymentScopeId,
            String attestationId,
            String attestationFingerprint,
            long headSequence,
            String headPublicationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt) {
    }

    private record JournalRow(
            String deploymentScopeId,
            String attestationId,
            String attestationFingerprint,
            long headSequence,
            String headPublicationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            String recordFingerprint) {

        private boolean matches(ControlPlaneCertificateStatusSourceHead sourceHead) {
            var material = sourceHead.material();
            return attestationFingerprint.equals(sourceHead.materialFingerprint())
                    && headSequence == material.headSequence()
                    && headPublicationFingerprint.equals(
                    material.headPublicationFingerprint())
                    && issuedAt.equals(material.issuedAt())
                    && expiresAt.equals(material.expiresAt());
        }

        private boolean valid(ObjectMapper mapper) {
            return IDENTIFIER.matcher(deploymentScopeId).matches()
                    && IDENTIFIER.matcher(attestationId).matches()
                    && FINGERPRINT.matcher(attestationFingerprint).matches()
                    && headSequence >= 0
                    && FINGERPRINT.matcher(headPublicationFingerprint).matches()
                    && expiresAt.isAfter(issuedAt) && expiresAt.isAfter(observedAt)
                    && ProtocolFingerprint.of(mapper, new JournalMaterial(JOURNAL_SCHEMA,
                    deploymentScopeId, attestationId, attestationFingerprint, headSequence,
                    headPublicationFingerprint, issuedAt, expiresAt, observedAt))
                    .equals(recordFingerprint);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
