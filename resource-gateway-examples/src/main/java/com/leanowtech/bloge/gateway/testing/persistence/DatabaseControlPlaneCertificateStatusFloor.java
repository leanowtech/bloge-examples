package com.leanowtech.bloge.gateway.testing.persistence;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusFloor;
import com.leanowtech.bloge.gateway.testing.api.ControlPlaneCertificateStatusPublication;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Database-clock certificate-status cursor and complete-snapshot authority.
 *
 * <p>A stable deployment lock serializes all writers. Signature verification is repeated inside
 * the transaction at database time; the head, complete target rows, and append-only publication
 * identity journal are then updated atomically. Canonical whole-record fingerprints detect direct
 * database mutation. Existing revoked certificate identities may never return to GOOD or UNKNOWN
 * under the same target generation and TLS-settings fingerprint.</p>
 */
public final class DatabaseControlPlaneCertificateStatusFloor
        implements ControlPlaneCertificateStatusFloor {

    private static final Duration MAXIMUM_DATABASE_CLOCK_SKEW = Duration.ofMinutes(5);
    private static final String HEAD_SCHEMA =
            "bloge.controlPlaneCertificateStatusFloorHead.v1";
    private static final String TARGET_SCHEMA =
            "bloge.controlPlaneCertificateStatusFloorTarget.v1";
    private static final String JOURNAL_SCHEMA =
            "bloge.controlPlaneCertificateStatusFloorJournal.v1";
    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/#-]{0,254}");
    private static final Pattern FINGERPRINT = Pattern.compile("sha256:[a-f0-9]{64}");

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ControlPlaneCertificateStatusTrustStore trustStore;
    private final String deploymentScopeId;
    private final long baselineSequence;
    private final String baselinePublicationFingerprint;
    private final List<String> expectedTargets;
    private final TransactionTemplate mutations;
    private final TransactionTemplate reads;

    /**
     * Creates one durable status authority bound to a deployment-pinned source baseline.
     *
     * @param jdbc testing-control-plane JDBC facade
     * @param objectMapper canonical protocol mapper
     * @param trustStore local non-blocking signature authority
     * @param deploymentScopeId exact governed deployment scope
     * @param baselineSequence deployment-pinned source cursor baseline
     * @param baselinePublicationFingerprint deployment-pinned predecessor fingerprint
     * @param expectedTargets exact complete governed target inventory
     * @param transactionManager manager for the same isolated datasource
     */
    public DatabaseControlPlaneCertificateStatusFloor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            ControlPlaneCertificateStatusTrustStore trustStore,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            List<ExpectedTarget> expectedTargets,
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
            throw invalid("Certificate status floor configuration is invalid");
        }
        List<String> targets = (expectedTargets == null ? List.<ExpectedTarget>of()
                : expectedTargets).stream()
                .map(ExpectedTarget::targetId)
                .sorted()
                .toList();
        if (targets.isEmpty() || targets.size() > 128
                || new HashSet<>(targets).size() != targets.size()) {
            throw invalid("Certificate status target inventory is invalid");
        }
        this.expectedTargets = List.copyOf(targets);
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

    /** Creates additive head, complete-target, publication-journal, and lock tables. */
    @PostConstruct
    public void init() {
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_scope_locks (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_heads (
                    deployment_scope_id VARCHAR(255) PRIMARY KEY,
                    baseline_sequence BIGINT NOT NULL,
                    baseline_publication_fingerprint VARCHAR(71) NOT NULL,
                    sequence BIGINT NOT NULL,
                    publication_id VARCHAR(255) NOT NULL,
                    publication_fingerprint VARCHAR(71) NOT NULL,
                    issued_at TIMESTAMP WITH TIME ZONE,
                    expires_at TIMESTAMP WITH TIME ZONE,
                    observed_at TIMESTAMP WITH TIME ZONE,
                    target_count INTEGER NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_targets (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    target_id VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    publication_fingerprint VARCHAR(71) NOT NULL,
                    generation BIGINT NOT NULL,
                    settings_fingerprint VARCHAR(71) NOT NULL,
                    target_json CLOB NOT NULL,
                    target_fingerprint VARCHAR(71) NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, target_id)
                )
                """);
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS rg_cp_cert_status_journal (
                    deployment_scope_id VARCHAR(255) NOT NULL,
                    sequence BIGINT NOT NULL,
                    publication_id VARCHAR(255) NOT NULL,
                    publication_fingerprint VARCHAR(71) NOT NULL,
                    previous_publication_fingerprint VARCHAR(71) NOT NULL,
                    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
                    record_fingerprint VARCHAR(71) NOT NULL,
                    PRIMARY KEY (deployment_scope_id, sequence),
                    UNIQUE (deployment_scope_id, publication_id)
                )
                """);
        mutations.executeWithoutResult(status -> {
            lock();
            HeadRow current = head();
            if (current == null) {
                persistInitialHead();
            } else {
                validateHead(current);
                targets(current);
            }
        });
    }

    /** {@inheritDoc} */
    @Override
    public Acceptance accept(ControlPlaneCertificateStatusPublication publication) {
        ControlPlaneCertificateStatusPublication required = Objects.requireNonNull(
                publication, "publication");
        Acceptance result = mutations.execute(status -> {
            lock();
            HeadRow current = validateHead(requireHead());
            List<TargetRow> currentTargets = targets(current);
            validateJournalHead(current);
            Instant now = databaseNow();
            ControlPlaneCertificateStatusTrustStore.Verification verification =
                    trustStore.verify(required,
                            new ControlPlaneCertificateStatusTrustStore.ExpectedBinding(
                                    deploymentScopeId), now);
            if (!verification.verified()
                    || verification.sequence() != required.material().sequence()
                    || !verification.publicationId().equals(
                    required.material().publicationId())
                    || !verification.publicationFingerprint().equals(
                    required.materialFingerprint())) {
                throw invalid("Certificate status publication is not authorized");
            }
            validateDatabaseTime(required.material(), now);
            validateInventory(required.material().targets());

            if (required.material().sequence() == current.sequence()
                    && required.materialFingerprint().equals(
                    current.publicationFingerprint())) {
                return new Acceptance(AcceptanceStatus.REPLAYED,
                        snapshot(current, currentTargets));
            }
            if (current.sequence() == Long.MAX_VALUE
                    || required.material().sequence() != current.sequence() + 1
                    || !required.material().previousPublicationFingerprint().equals(
                    expectedPredecessor(current))) {
                throw invalid("Certificate status publication cursor conflicts");
            }
            rejectPublicationIdentityReuse(required);
            preventStatusResurrection(currentTargets, required.material().targets());
            persistJournal(required, now);
            replaceTargets(required, now);
            persistHead(required, now);
            HeadRow stored = validateHead(requireHead());
            return new Acceptance(AcceptanceStatus.APPLIED,
                    snapshot(stored, targets(stored)));
        });
        return Objects.requireNonNull(result, "certificate status acceptance result");
    }

    /** {@inheritDoc} */
    @Override
    public Snapshot snapshot() {
        Snapshot result = reads.execute(status -> {
            HeadRow current = validateHead(requireHead());
            validateJournalHead(current);
            return snapshot(current, targets(current));
        });
        return Objects.requireNonNull(result, "certificate status snapshot");
    }

    /** {@inheritDoc} */
    @Override
    public boolean durable() {
        return true;
    }

    private void validateDatabaseTime(
            ControlPlaneCertificateStatusPublication.Material material, Instant now) {
        if (material.issuedAt().isAfter(now.plus(MAXIMUM_DATABASE_CLOCK_SKEW))
                || !now.isBefore(material.expiresAt())) {
            throw invalid("Certificate status publication is not current at database time");
        }
    }

    private String expectedPredecessor(HeadRow current) {
        return current.publicationId().isBlank() && current.sequence() == 0
                ? "" : current.publicationFingerprint();
    }

    private void validateInventory(
            List<ControlPlaneCertificateStatusPublication.TargetStatus> supplied) {
        List<String> actual = supplied.stream()
                .map(ControlPlaneCertificateStatusPublication.TargetStatus::targetId)
                .toList();
        if (!expectedTargets.equals(actual)) {
            throw invalid("Certificate status publication target inventory conflicts");
        }
    }

    private void preventStatusResurrection(
            List<TargetRow> current,
            List<ControlPlaneCertificateStatusPublication.TargetStatus> candidates) {
        for (int index = 0; index < current.size(); index++) {
            TargetRow existing = current.get(index);
            ControlPlaneCertificateStatusPublication.TargetStatus candidate =
                    candidates.get(index);
            if (candidate.generation() < existing.generation()
                    || candidate.generation() == existing.generation()
                    && !existing.settingsFingerprint().equals(candidate.settingsFingerprint())) {
                throw invalid("Certificate status target generation is not monotonic");
            }
            if (existing.generation() != candidate.generation()) {
                continue;
            }
            ControlPlaneCertificateStatusPublication.TargetStatus previous = existing.target();
            for (int role = 0; role < 2; role++) {
                var oldEvidence = previous.certificates().get(role);
                var newEvidence = candidate.certificates().get(role);
                if (!oldEvidence.certificateFingerprint().equals(
                        newEvidence.certificateFingerprint())
                        || !oldEvidence.issuerSpkiFingerprint().equals(
                        newEvidence.issuerSpkiFingerprint())
                        || oldEvidence.status()
                        == ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED
                        && (newEvidence.status()
                        != ControlPlaneCertificateStatusPublication.CertificateStatus.REVOKED
                        || !newEvidence.effectiveAt().equals(oldEvidence.effectiveAt()))) {
                    throw invalid("Certificate status identity or revocation is not monotonic");
                }
            }
        }
    }

    private void rejectPublicationIdentityReuse(
            ControlPlaneCertificateStatusPublication publication) {
        List<JournalRow> rows = jdbc.query("""
                SELECT deployment_scope_id, sequence, publication_id,
                       publication_fingerprint, previous_publication_fingerprint,
                       observed_at, record_fingerprint
                FROM rg_cp_cert_status_journal
                WHERE deployment_scope_id = ? AND publication_id = ?
                """, this::journalRow, deploymentScopeId,
                publication.material().publicationId());
        if (rows.size() > 1 || rows.stream().anyMatch(row -> !row.valid(objectMapper))) {
            throw new IllegalStateException("Certificate status journal is corrupt");
        }
        if (!rows.isEmpty()) {
            throw invalid("Certificate status publication identity was already used");
        }
    }

    private void validateJournalHead(HeadRow head) {
        if (head.publicationId().isBlank()) {
            return;
        }
        List<JournalRow> rows = jdbc.query("""
                SELECT deployment_scope_id, sequence, publication_id,
                       publication_fingerprint, previous_publication_fingerprint,
                       observed_at, record_fingerprint
                FROM rg_cp_cert_status_journal
                WHERE deployment_scope_id = ? AND sequence = ?
                """, this::journalRow, deploymentScopeId, head.sequence());
        if (rows.size() != 1 || !rows.getFirst().valid(objectMapper)
                || !rows.getFirst().publicationId().equals(head.publicationId())
                || !rows.getFirst().publicationFingerprint().equals(
                head.publicationFingerprint())
                || !rows.getFirst().observedAt().equals(head.observedAt())) {
            throw new IllegalStateException("Certificate status journal head is corrupt");
        }
    }

    private void replaceTargets(
            ControlPlaneCertificateStatusPublication publication, Instant observedAt) {
        jdbc.update("DELETE FROM rg_cp_cert_status_targets WHERE deployment_scope_id = ?",
                deploymentScopeId);
        for (ControlPlaneCertificateStatusPublication.TargetStatus target :
                publication.material().targets()) {
            String targetJson = json(target);
            String targetFingerprint = ProtocolFingerprint.of(objectMapper, target);
            TargetRecordMaterial material = new TargetRecordMaterial(TARGET_SCHEMA,
                    deploymentScopeId, target.targetId(), publication.material().sequence(),
                    publication.materialFingerprint(), target.generation(),
                    target.settingsFingerprint(), targetFingerprint, observedAt);
            jdbc.update("""
                    INSERT INTO rg_cp_cert_status_targets (
                        deployment_scope_id, target_id, sequence, publication_fingerprint,
                        generation, settings_fingerprint, target_json, target_fingerprint,
                        record_fingerprint)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, deploymentScopeId, target.targetId(), publication.material().sequence(),
                    publication.materialFingerprint(), target.generation(),
                    target.settingsFingerprint(), targetJson, targetFingerprint,
                    ProtocolFingerprint.of(objectMapper, material));
        }
    }

    private void persistJournal(
            ControlPlaneCertificateStatusPublication publication, Instant observedAt) {
        JournalMaterial material = new JournalMaterial(JOURNAL_SCHEMA, deploymentScopeId,
                publication.material().sequence(), publication.material().publicationId(),
                publication.materialFingerprint(),
                publication.material().previousPublicationFingerprint(), observedAt);
        jdbc.update("""
                INSERT INTO rg_cp_cert_status_journal (
                    deployment_scope_id, sequence, publication_id, publication_fingerprint,
                    previous_publication_fingerprint, observed_at, record_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, deploymentScopeId, publication.material().sequence(),
                publication.material().publicationId(), publication.materialFingerprint(),
                publication.material().previousPublicationFingerprint(),
                Timestamp.from(observedAt), ProtocolFingerprint.of(objectMapper, material));
    }

    private void persistInitialHead() {
        HeadRecordMaterial material = new HeadRecordMaterial(HEAD_SCHEMA, deploymentScopeId,
                baselineSequence, baselinePublicationFingerprint, baselineSequence, "",
                baselinePublicationFingerprint, null, null, null, 0);
        jdbc.update("""
                INSERT INTO rg_cp_cert_status_heads (
                    deployment_scope_id, baseline_sequence,
                    baseline_publication_fingerprint, sequence, publication_id,
                    publication_fingerprint, issued_at, expires_at, observed_at,
                    target_count, record_fingerprint)
                VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, ?)
                """, deploymentScopeId, baselineSequence, baselinePublicationFingerprint,
                baselineSequence, "", baselinePublicationFingerprint,
                ProtocolFingerprint.of(objectMapper, material));
    }

    private void persistHead(
            ControlPlaneCertificateStatusPublication publication, Instant observedAt) {
        var material = publication.material();
        HeadRecordMaterial record = new HeadRecordMaterial(HEAD_SCHEMA, deploymentScopeId,
                baselineSequence, baselinePublicationFingerprint, material.sequence(),
                material.publicationId(), publication.materialFingerprint(), material.issuedAt(),
                material.expiresAt(), observedAt, material.targets().size());
        int updated = jdbc.update("""
                UPDATE rg_cp_cert_status_heads
                SET sequence = ?, publication_id = ?, publication_fingerprint = ?,
                    issued_at = ?, expires_at = ?, observed_at = ?, target_count = ?,
                    record_fingerprint = ?
                WHERE deployment_scope_id = ?
                """, material.sequence(), material.publicationId(),
                publication.materialFingerprint(), Timestamp.from(material.issuedAt()),
                Timestamp.from(material.expiresAt()), Timestamp.from(observedAt),
                material.targets().size(), ProtocolFingerprint.of(objectMapper, record),
                deploymentScopeId);
        if (updated != 1) {
            throw new IllegalStateException("Certificate status head is unavailable");
        }
    }

    private List<TargetRow> targets(HeadRow head) {
        List<TargetRow> rows = jdbc.query("""
                SELECT deployment_scope_id, target_id, sequence, publication_fingerprint,
                       generation, settings_fingerprint, target_json, target_fingerprint,
                       record_fingerprint
                FROM rg_cp_cert_status_targets
                WHERE deployment_scope_id = ?
                ORDER BY target_id
                """, this::targetRow, deploymentScopeId);
        if (rows.size() != head.targetCount()) {
            throw new IllegalStateException("Certificate status target inventory is incomplete");
        }
        List<TargetRow> verified = new ArrayList<>(rows.size());
        for (TargetRow row : rows) {
            if (!row.valid(objectMapper, deploymentScopeId, head.sequence(),
                    head.publicationFingerprint(), head.observedAt())) {
                throw new IllegalStateException("Certificate status target row is corrupt");
            }
            verified.add(row);
        }
        if (head.targetCount() > 0
                && !expectedTargets.equals(verified.stream().map(TargetRow::targetId).toList())) {
            throw new IllegalStateException("Certificate status target inventory drifted");
        }
        return List.copyOf(verified);
    }

    private HeadRow validateHead(HeadRow row) {
        if (!row.valid(objectMapper, deploymentScopeId, baselineSequence,
                baselinePublicationFingerprint)) {
            throw new IllegalStateException("Certificate status head is corrupt or drifted");
        }
        return row;
    }

    private HeadRow requireHead() {
        HeadRow current = head();
        if (current == null) {
            throw new IllegalStateException("Certificate status floor is not initialized");
        }
        return current;
    }

    private HeadRow head() {
        List<HeadRow> rows = jdbc.query("""
                SELECT deployment_scope_id, baseline_sequence,
                       baseline_publication_fingerprint, sequence, publication_id,
                       publication_fingerprint, issued_at, expires_at, observed_at,
                       target_count, record_fingerprint
                FROM rg_cp_cert_status_heads WHERE deployment_scope_id = ?
                """, this::headRow, deploymentScopeId);
        if (rows.size() > 1) {
            throw new IllegalStateException("Duplicate certificate status head");
        }
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private Snapshot snapshot(HeadRow head, List<TargetRow> targets) {
        return new Snapshot(Snapshot.SCHEMA_VERSION, deploymentScopeId,
                baselineSequence, baselinePublicationFingerprint, head.sequence(),
                head.publicationId(), head.publicationFingerprint(), head.issuedAt(),
                head.expiresAt(), head.observedAt(),
                targets.stream().map(TargetRow::target).toList());
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
            throw new IllegalStateException("Certificate status database time is unavailable");
        }
        return timestamp.toInstant();
    }

    private HeadRow headRow(ResultSet result, int rowNumber) throws SQLException {
        return new HeadRow(result.getString("deployment_scope_id"),
                result.getLong("baseline_sequence"),
                result.getString("baseline_publication_fingerprint"),
                result.getLong("sequence"), result.getString("publication_id"),
                result.getString("publication_fingerprint"),
                instant(result, "issued_at"), instant(result, "expires_at"),
                instant(result, "observed_at"), result.getInt("target_count"),
                result.getString("record_fingerprint"));
    }

    private TargetRow targetRow(ResultSet result, int rowNumber) throws SQLException {
        return new TargetRow(result.getString("deployment_scope_id"),
                result.getString("target_id"), result.getLong("sequence"),
                result.getString("publication_fingerprint"), result.getLong("generation"),
                result.getString("settings_fingerprint"), result.getString("target_json"),
                result.getString("target_fingerprint"),
                result.getString("record_fingerprint"));
    }

    private JournalRow journalRow(ResultSet result, int rowNumber) throws SQLException {
        return new JournalRow(result.getString("deployment_scope_id"),
                result.getLong("sequence"), result.getString("publication_id"),
                result.getString("publication_fingerprint"),
                result.getString("previous_publication_fingerprint"),
                Objects.requireNonNull(instant(result, "observed_at"), "observed_at"),
                result.getString("record_fingerprint"));
    }

    private String json(ControlPlaneCertificateStatusPublication.TargetStatus target) {
        try {
            return objectMapper.writeValueAsString(target);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Certificate status target cannot be encoded", failure);
        }
    }

    private ControlPlaneCertificateStatusPublication.TargetStatus target(String json) {
        try {
            return objectMapper.readValue(json,
                    ControlPlaneCertificateStatusPublication.TargetStatus.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Certificate status target is corrupt", failure);
        }
    }

    private static Instant instant(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record HeadRecordMaterial(
            String schemaVersion,
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long sequence,
            String publicationId,
            String publicationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            int targetCount) {
    }

    private record HeadRow(
            String deploymentScopeId,
            long baselineSequence,
            String baselinePublicationFingerprint,
            long sequence,
            String publicationId,
            String publicationFingerprint,
            Instant issuedAt,
            Instant expiresAt,
            Instant observedAt,
            int targetCount,
            String recordFingerprint) {

        private HeadRecordMaterial material() {
            return new HeadRecordMaterial(HEAD_SCHEMA, deploymentScopeId, baselineSequence,
                    baselinePublicationFingerprint, sequence, publicationId,
                    publicationFingerprint, issuedAt, expiresAt, observedAt, targetCount);
        }

        private boolean valid(
                ObjectMapper objectMapper,
                String expectedScope,
                long expectedBaselineSequence,
                String expectedBaselineFingerprint) {
            boolean initial = publicationId.isBlank();
            boolean live = IDENTIFIER.matcher(publicationId).matches()
                    && sequence > baselineSequence && issuedAt != null && expiresAt != null
                    && observedAt != null && targetCount > 0 && targetCount <= 128
                    && expiresAt.isAfter(issuedAt);
            return expectedScope.equals(deploymentScopeId)
                    && baselineSequence == expectedBaselineSequence
                    && expectedBaselineFingerprint.equals(baselinePublicationFingerprint)
                    && sequence >= baselineSequence
                    && FINGERPRINT.matcher(publicationFingerprint).matches()
                    && (initial && sequence == baselineSequence
                    && publicationFingerprint.equals(baselinePublicationFingerprint)
                    && issuedAt == null && expiresAt == null && observedAt == null
                    && targetCount == 0 || live)
                    && ProtocolFingerprint.of(objectMapper, material())
                    .equals(recordFingerprint);
        }
    }

    private record TargetRecordMaterial(
            String schemaVersion,
            String deploymentScopeId,
            String targetId,
            long sequence,
            String publicationFingerprint,
            long generation,
            String settingsFingerprint,
            String targetFingerprint,
            Instant observedAt) {
    }

    private final class TargetRow {
        private final String deploymentScopeId;
        private final String targetId;
        private final long sequence;
        private final String publicationFingerprint;
        private final long generation;
        private final String settingsFingerprint;
        private final String targetJson;
        private final String targetFingerprint;
        private final String recordFingerprint;

        private TargetRow(
                String deploymentScopeId,
                String targetId,
                long sequence,
                String publicationFingerprint,
                long generation,
                String settingsFingerprint,
                String targetJson,
                String targetFingerprint,
                String recordFingerprint) {
            this.deploymentScopeId = deploymentScopeId;
            this.targetId = targetId;
            this.sequence = sequence;
            this.publicationFingerprint = publicationFingerprint;
            this.generation = generation;
            this.settingsFingerprint = settingsFingerprint;
            this.targetJson = targetJson;
            this.targetFingerprint = targetFingerprint;
            this.recordFingerprint = recordFingerprint;
        }

        private String targetId() {
            return targetId;
        }

        private long generation() {
            return generation;
        }

        private String settingsFingerprint() {
            return settingsFingerprint;
        }

        private ControlPlaneCertificateStatusPublication.TargetStatus target() {
            return DatabaseControlPlaneCertificateStatusFloor.this.target(targetJson);
        }

        private boolean valid(
                ObjectMapper mapper,
                String expectedScope,
                long expectedSequence,
                String expectedPublicationFingerprint,
                Instant observedAt) {
            ControlPlaneCertificateStatusPublication.TargetStatus decoded = target();
            String decodedFingerprint = ProtocolFingerprint.of(mapper, decoded);
            TargetRecordMaterial material = new TargetRecordMaterial(TARGET_SCHEMA,
                    deploymentScopeId, targetId, sequence, publicationFingerprint, generation,
                    settingsFingerprint, targetFingerprint, observedAt);
            return expectedScope.equals(deploymentScopeId)
                    && expectedSequence == sequence
                    && expectedPublicationFingerprint.equals(publicationFingerprint)
                    && targetId.equals(decoded.targetId()) && generation == decoded.generation()
                    && settingsFingerprint.equals(decoded.settingsFingerprint())
                    && targetFingerprint.equals(decodedFingerprint)
                    && ProtocolFingerprint.of(mapper, material).equals(recordFingerprint);
        }
    }

    private record JournalMaterial(
            String schemaVersion,
            String deploymentScopeId,
            long sequence,
            String publicationId,
            String publicationFingerprint,
            String previousPublicationFingerprint,
            Instant observedAt) {
    }

    private record JournalRow(
            String deploymentScopeId,
            long sequence,
            String publicationId,
            String publicationFingerprint,
            String previousPublicationFingerprint,
            Instant observedAt,
            String recordFingerprint) {

        private boolean valid(ObjectMapper objectMapper) {
            return IDENTIFIER.matcher(deploymentScopeId).matches()
                    && sequence > 0 && IDENTIFIER.matcher(publicationId).matches()
                    && FINGERPRINT.matcher(publicationFingerprint).matches()
                    && (sequence == 1 && previousPublicationFingerprint.isBlank()
                    || sequence > 1
                    && FINGERPRINT.matcher(previousPublicationFingerprint).matches())
                    && ProtocolFingerprint.of(objectMapper, new JournalMaterial(JOURNAL_SCHEMA,
                    deploymentScopeId, sequence, publicationId, publicationFingerprint,
                    previousPublicationFingerprint, observedAt)).equals(recordFingerprint);
        }
    }

    private static String normalized(String value) {
        return value == null ? "" : value.trim();
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
