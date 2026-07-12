package com.leanowtech.bloge.gateway.visual.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.leanowtech.bloge.gateway.visual.draft.GraphDraft;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Closes the crash gap between durable run control and signed run evidence.
 *
 * <p>A sanitized lineage reservation is committed before execution. Normal completion and automatic recovery then
 * serialize on that reservation row, so exactly one immutable run record and outbox event can be created.</p>
 */
@Service
public class VisualRunEvidenceRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(VisualRunEvidenceRecoveryService.class);
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS visual_run_recovery_reservations (
                request_id VARCHAR(128) PRIMARY KEY,
                reservation_id VARCHAR(128) NOT NULL UNIQUE,
                run_id VARCHAR(255) NOT NULL UNIQUE,
                tenant_id VARCHAR(255) NOT NULL,
                environment_id VARCHAR(255) NOT NULL,
                reserved_at VARCHAR(64) NOT NULL,
                material_fingerprint VARCHAR(128) NOT NULL,
                reservation_json CLOB NOT NULL,
                state VARCHAR(32) NOT NULL,
                completion_kind VARCHAR(64) NOT NULL,
                evidence_run_id VARCHAR(255) NOT NULL,
                recovery_attempts INT NOT NULL,
                completed_at VARCHAR(64),
                last_error VARCHAR(1024) NOT NULL,
                updated_at VARCHAR(64) NOT NULL
            )
            """;
    private static final String CREATE_STATE_INDEX = """
            CREATE INDEX IF NOT EXISTS idx_visual_run_recovery_state_time
            ON visual_run_recovery_reservations (state, reserved_at)
            """;
    private static final String INSERT = """
            INSERT INTO visual_run_recovery_reservations (
                request_id, reservation_id, run_id, tenant_id, environment_id, reserved_at,
                material_fingerprint, reservation_json, state, completion_kind, evidence_run_id,
                recovery_attempts, completed_at, last_error, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 'NONE', '', 0, NULL, '', ?)
            """;
    private static final String SELECT_FOR_UPDATE = """
            SELECT reservation_json, state, completion_kind, evidence_run_id, recovery_attempts
            FROM visual_run_recovery_reservations WHERE request_id = ? FOR UPDATE
            """;
    private static final String COMPLETE = """
            UPDATE visual_run_recovery_reservations
            SET state = ?, completion_kind = ?, evidence_run_id = ?, recovery_attempts = ?,
                completed_at = ?, last_error = '', updated_at = ?
            WHERE request_id = ? AND state = 'PENDING'
            """;
    private static final String RECORD_FAILURE = """
            UPDATE visual_run_recovery_reservations
            SET recovery_attempts = recovery_attempts + 1, last_error = ?, updated_at = ?
            WHERE request_id = ? AND state = 'PENDING'
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final VisualRunControlRecoveryPort controlRecovery;
    private final VisualGraphRunRepository runRepository;
    private final TransactionTemplate transactions;
    private final Duration evidenceCommitGrace;
    private final Duration missingControlGrace;

    @Autowired
    public VisualRunEvidenceRecoveryService(JdbcTemplate jdbc,
                                            ObjectMapper objectMapper,
                                            VisualRunControlRecoveryPort controlRecovery,
                                            VisualGraphRunRepository runRepository,
                                            PlatformTransactionManager transactionManager,
                                            @Value("${resource-gateway.run-recovery.evidence-commit-grace-ms:5000}")
                                            long evidenceCommitGraceMs,
                                            @Value("${resource-gateway.run-recovery.missing-control-grace-ms:30000}")
                                            long missingControlGraceMs) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.controlRecovery = controlRecovery;
        this.runRepository = runRepository;
        this.transactions = new TransactionTemplate(transactionManager);
        this.evidenceCommitGrace = Duration.ofMillis(Math.max(0, evidenceCommitGraceMs));
        this.missingControlGrace = Duration.ofMillis(Math.max(0, missingControlGraceMs));
    }

    private VisualRunEvidenceRecoveryService(VisualGraphRunRepository runRepository) {
        this.jdbc = null;
        this.objectMapper = null;
        this.controlRecovery = null;
        this.runRepository = runRepository;
        this.transactions = null;
        this.evidenceCommitGrace = Duration.ZERO;
        this.missingControlGrace = Duration.ZERO;
    }

    /** Pass-through adapter for direct unit-test constructors that do not configure durable recovery. */
    public static VisualRunEvidenceRecoveryService passThrough(VisualGraphRunRepository runRepository) {
        return new VisualRunEvidenceRecoveryService(runRepository);
    }

    @PostConstruct
    void init() {
        if (jdbc == null) {
            return;
        }
        jdbc.execute(CREATE_TABLE);
        jdbc.execute(CREATE_STATE_INDEX);
    }

    /** Commits a sanitized lineage seed before a managed run can acquire runtime resources. */
    public Optional<VisualRunRecoveryReservation> reserve(String sourceKind,
                                                          GraphDraft draft,
                                                          String publicationId,
                                                          String sourceArtifactKind,
                                                          Map<String, Object> context,
                                                          String outputNode,
                                                          VisualRunIntent intent) {
        if (jdbc == null || intent == null || !intent.managed()) {
            return Optional.empty();
        }
        VisualRunRecoveryReservation proposed = VisualRunRecoveryReservation.create(intent.requestId(), sourceKind,
                draft, publicationId, sourceArtifactKind, context, outputNode, Instant.now());
        VisualRunRecoveryReservation stored;
        try {
            stored = transactions.execute(status -> {
                StoredReservation existing = load(intent.requestId(), true).orElse(null);
                if (existing != null) {
                    return requireSameMaterial(intent.requestId(), proposed, existing);
                }
                insert(proposed);
                return proposed;
            });
        } catch (DuplicateKeyException concurrentReservation) {
            StoredReservation winner = load(intent.requestId(), false)
                    .orElseThrow(() -> concurrentReservation);
            stored = requireSameMaterial(intent.requestId(), proposed, winner);
        }
        return Optional.ofNullable(stored);
    }

    /** Creates normal evidence or returns the already recovered record when recovery won the row lock. */
    public VisualGraphRunRecord complete(VisualGraphRunRecord record) {
        return complete(record, record == null ? "" : record.runControl().requestId());
    }

    /** Completes a reservation even when validation stopped before the runtime could create a control row. */
    public VisualGraphRunRecord complete(VisualGraphRunRecord record, String requestedRunId) {
        if (record == null) {
            throw new IllegalArgumentException("A visual graph run record is required");
        }
        String requestId = requestedRunId == null ? "" : requestedRunId.trim();
        if (!record.runControl().requestId().isBlank()
                && !requestId.isBlank()
                && !record.runControl().requestId().equals(requestId)) {
            throw new IllegalArgumentException("Run record control requestId does not match its recovery reservation");
        }
        if (requestId.isBlank()) {
            requestId = record.runControl().requestId();
        }
        if (jdbc == null || requestId.isBlank()) {
            return runRepository.create(record);
        }
        String normalizedRequestId = requestId;
        VisualGraphRunRecord stored = transactions.execute(status -> {
            StoredReservation reservation = load(normalizedRequestId, true).orElse(null);
            if (reservation == null) {
                log.warn("Managed run completed without a recovery reservation: {}", normalizedRequestId);
                return runRepository.create(record);
            }
            if (!"PENDING".equals(reservation.state())) {
                return runRepository.find(reservation.reservation().runId())
                        .orElseThrow(() -> new IllegalStateException(
                                "Completed recovery reservation has no run record: " + normalizedRequestId));
            }
            VisualGraphRunRecord created = runRepository.create(
                    record.withIdentity(reservation.reservation().runId(), record.createdAt()));
            markCompleted(normalizedRequestId, "COMPLETED", "NORMAL_COMPLETION", created.runId(),
                    reservation.attempts(), Instant.now());
            return created;
        });
        if (stored == null) {
            throw new IllegalStateException(
                    "Managed run completion transaction returned no record: " + normalizedRequestId);
        }
        return stored;
    }

    /** Runs one bounded, idempotent recovery scan and returns auditable counters. */
    public RecoverySweepResult sweepNow(int limit) {
        if (jdbc == null) {
            return new RecoverySweepResult(0, 0, 0, List.of());
        }
        Instant now = Instant.now();
        int bounded = Math.max(1, Math.min(500, limit));
        String missingCutoff = now.minus(missingControlGrace).toString();
        String completedCutoff = now.minus(evidenceCommitGrace).toString();
        List<String> candidates = controlRecovery.recoveryCandidates(
                Instant.parse(missingCutoff), Instant.parse(completedCutoff), now, bounded);
        int recovered = 0;
        int skipped = 0;
        List<String> failures = new ArrayList<>();
        for (String requestId : candidates) {
            try {
                Boolean created = transactions.execute(status -> recoverOne(requestId, now));
                if (Boolean.TRUE.equals(created)) {
                    recovered++;
                } else {
                    skipped++;
                }
            } catch (RuntimeException exception) {
                failures.add(requestId + ": " + safeMessage(exception));
                recordFailure(requestId, exception, now);
            }
        }
        return new RecoverySweepResult(candidates.size(), recovered, skipped, failures);
    }

    @Scheduled(fixedDelayString = "${resource-gateway.run-recovery.fixed-delay-ms:5000}",
            initialDelayString = "${resource-gateway.run-recovery.initial-delay-ms:5000}")
    public void scheduledSweep() {
        RecoverySweepResult result = sweepNow(100);
        if (result.recovered() > 0 || !result.failures().isEmpty()) {
            log.info("Visual run evidence recovery sweep scanned={} recovered={} skipped={} failures={}",
                    result.scanned(), result.recovered(), result.skipped(), result.failures().size());
        }
    }

    private boolean recoverOne(String requestId, Instant now) {
        StoredReservation stored = load(requestId, true).orElse(null);
        if (stored == null || !"PENDING".equals(stored.state())) {
            return false;
        }
        VisualRunControlRecoveryPort.State controlState = controlRecovery.find(requestId, now).orElse(null);
        String mode;
        String trigger;
        VisualRunControlView control;
        long revision;
        if (controlState == null) {
            if (stored.reservation().reservedAt().isAfter(now.minus(missingControlGrace))) {
                return false;
            }
            mode = VisualRunRecoveryMetadata.MODE_CONTROL_MISSING;
            trigger = "RUN_CONTROL_MISSING_AFTER_RESERVATION";
            revision = 0;
            control = new VisualRunControlView("", requestId, "", "TERMINATION_UNCONFIRMED", trigger, 0,
                    null, null, null, now, false, true);
        } else {
            VisualRunControlView view = controlState.control();
            if ("ABANDONED".equals(controlState.recoveryDisposition())) {
                mode = VisualRunRecoveryMetadata.MODE_OWNER_ABANDONED;
                trigger = view.reasonCode();
            } else if ("COMPLETED".equals(controlState.recoveryDisposition())
                    && view.terminalAt() != null
                    && !view.terminalAt().isAfter(now.minus(evidenceCommitGrace))) {
                mode = VisualRunRecoveryMetadata.MODE_TERMINAL_EVIDENCE_GAP;
                trigger = "TERMINAL_CONTROL_WITHOUT_EVIDENCE";
            } else {
                return false;
            }
            revision = view.revision();
            control = view;
        }
        int attempt = stored.attempts() + 1;
        VisualRunRecoveryMetadata recovery = new VisualRunRecoveryMetadata("", mode,
                stored.reservation().reservationId(), stored.reservation().materialFingerprint(), now,
                revision, attempt, trigger);
        VisualGraphRunRecord recovered = VisualGraphRunRecord.recovered(stored.reservation(), control, recovery);
        VisualGraphRunRecord created = runRepository.create(recovered);
        markCompleted(requestId, "RECOVERED", mode, created.runId(), attempt, now);
        return true;
    }

    private void insert(VisualRunRecoveryReservation reservation) {
        GraphDraft draft = reservation.draft();
        try {
            jdbc.update(INSERT, reservation.requestId(), reservation.reservationId(), reservation.runId(),
                    draft == null ? "" : draft.tenantId(), draft == null ? "" : draft.environment(),
                    reservation.reservedAt().toString(), reservation.materialFingerprint(),
                    objectMapper.writeValueAsString(reservation), reservation.reservedAt().toString());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to serialize visual run recovery reservation", exception);
        }
    }

    private static VisualRunRecoveryReservation requireSameMaterial(String requestId,
                                                                     VisualRunRecoveryReservation proposed,
                                                                     StoredReservation existing) {
        if (!existing.reservation().materialFingerprint().equals(proposed.materialFingerprint())) {
            throw new IllegalArgumentException("Managed requestId is already reserved for different run material: "
                    + requestId);
        }
        return existing.reservation();
    }

    private Optional<StoredReservation> load(String requestId, boolean lock) {
        String sql = lock ? SELECT_FOR_UPDATE : SELECT_FOR_UPDATE.replace(" FOR UPDATE", "");
        return jdbc.query(sql, (rs, rowNum) -> new StoredReservation(
                        readReservation(rs.getString("reservation_json")), rs.getString("state"),
                        rs.getString("completion_kind"), rs.getString("evidence_run_id"),
                        rs.getInt("recovery_attempts")), requestId)
                .stream().findFirst();
    }

    private VisualRunRecoveryReservation readReservation(String json) {
        try {
            VisualRunRecoveryReservation reservation = objectMapper.readValue(json,
                    VisualRunRecoveryReservation.class);
            if (!reservation.materialFingerprint().equals(reservation.recomputedMaterialFingerprint())) {
                throw new IllegalStateException("Visual run recovery reservation fingerprint verification failed");
            }
            return reservation;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Failed to deserialize visual run recovery reservation", exception);
        }
    }

    private void markCompleted(String requestId, String state, String kind, String runId, int attempts, Instant now) {
        int updated = jdbc.update(COMPLETE, state, kind, runId, attempts, now.toString(), now.toString(), requestId);
        if (updated != 1) {
            throw new IllegalStateException("Recovery reservation transition lost for requestId: " + requestId);
        }
    }

    private void recordFailure(String requestId, RuntimeException exception, Instant now) {
        try {
            jdbc.update(RECORD_FAILURE, safeMessage(exception), now.toString(), requestId);
        } catch (RuntimeException updateFailure) {
            log.error("Failed to record recovery error for {}", requestId, updateFailure);
        }
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable == null ? "Unknown recovery failure" : throwable.getMessage();
        if (message == null || message.isBlank()) {
            message = throwable == null ? "Unknown recovery failure" : throwable.getClass().getSimpleName();
        }
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private record StoredReservation(VisualRunRecoveryReservation reservation, String state,
                                     String completionKind, String evidenceRunId, int attempts) {
    }

    public record RecoverySweepResult(int scanned, int recovered, int skipped, List<String> failures) {
        public RecoverySweepResult {
            scanned = Math.max(0, scanned);
            recovered = Math.max(0, recovered);
            skipped = Math.max(0, skipped);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }
}
