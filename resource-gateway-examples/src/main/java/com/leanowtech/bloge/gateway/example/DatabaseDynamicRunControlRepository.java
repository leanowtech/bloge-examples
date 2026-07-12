package com.leanowtech.bloge.gateway.example;

import jakarta.annotation.PostConstruct;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** Database-backed controlled-run state machine with row locking and owner epochs. */
public final class DatabaseDynamicRunControlRepository implements DynamicRunControlRepository {
    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS dynamic_run_controls (
                request_id VARCHAR(128) PRIMARY KEY,
                fence_digest VARCHAR(64) NOT NULL,
                owner_id VARCHAR(128) NOT NULL,
                owner_epoch BIGINT NOT NULL,
                lease_expires_at VARCHAR(64),
                cancellation_grace_ms BIGINT NOT NULL,
                engine_execution_id VARCHAR(255) NOT NULL,
                status VARCHAR(64) NOT NULL,
                reason_code VARCHAR(128) NOT NULL,
                revision BIGINT NOT NULL,
                deadline_at VARCHAR(64),
                started_at VARCHAR(64),
                cancel_requested_at VARCHAR(64),
                terminal_at VARCHAR(64),
                termination_confirmed BOOLEAN NOT NULL,
                side_effects_in_flight BOOLEAN NOT NULL,
                recovery_disposition VARCHAR(64) NOT NULL,
                updated_at VARCHAR(64) NOT NULL
            )
            """;
    private static final String SELECT = "SELECT * FROM dynamic_run_controls WHERE request_id = ?";
    private static final String SELECT_FOR_UPDATE = SELECT + " FOR UPDATE";
    private static final String INSERT = """
            INSERT INTO dynamic_run_controls (
                request_id, fence_digest, owner_id, owner_epoch, lease_expires_at, cancellation_grace_ms,
                engine_execution_id, status, reason_code, revision, deadline_at, started_at,
                cancel_requested_at, terminal_at, termination_confirmed, side_effects_in_flight,
                recovery_disposition, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE = """
            UPDATE dynamic_run_controls SET
                owner_id = ?, owner_epoch = ?, lease_expires_at = ?, cancellation_grace_ms = ?,
                engine_execution_id = ?, status = ?, reason_code = ?, revision = ?, deadline_at = ?,
                started_at = ?, cancel_requested_at = ?, terminal_at = ?, termination_confirmed = ?,
                side_effects_in_flight = ?, recovery_disposition = ?, updated_at = ?
            WHERE request_id = ?
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public DatabaseDynamicRunControlRepository(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        if (jdbc == null || transactionManager == null) {
            throw new IllegalArgumentException("JDBC and transaction manager are required for durable run control");
        }
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void init() {
        jdbc.execute(CREATE_TABLE);
    }

    @Override
    public Claim claim(DynamicRunIntent intent, String ownerId, Instant leaseExpiresAt) {
        State created = new State(new DynamicRunControlView("", intent.requestId(), "", "QUEUED", "ACCEPTED", 1,
                intent.deadlineAt(), null, null, null, false, false),
                InMemoryDynamicRunControlRepository.digest(intent.fencingToken()), new Owner(ownerId, 1),
                leaseExpiresAt, intent.cancellationGraceMs(), "NONE");
        try {
            jdbc.update(INSERT, insertArgs(created, Instant.now()));
            return new Claim(true, "RG.RUN_CONTROL.CLAIMED", "", created);
        } catch (DuplicateKeyException duplicate) {
            State existing = find(intent.requestId(), Instant.now()).orElse(null);
            return new Claim(false, "RG.RUN_CONTROL.DUPLICATE_REQUEST", "Run request id is already registered.",
                    existing);
        }
    }

    @Override
    public Optional<State> find(String requestId, Instant now) {
        return inTransaction(requestId, state -> expire(state, now));
    }

    @Override
    public Optional<State> start(String requestId, Owner owner, Instant now, Instant leaseExpiresAt) {
        return ownedMutation(requestId, owner, state -> {
            if (!"QUEUED".equals(state.view().status())) {
                return state;
            }
            return changed(state, "RUNNING", "EXECUTION_STARTED", state.view().engineExecutionId(), now,
                    state.view().cancelRequestedAt(), null, false, false, leaseExpiresAt, "NONE");
        });
    }

    @Override
    public Optional<State> observeExecutionId(String requestId, Owner owner, String executionId,
                                              Instant leaseExpiresAt) {
        return ownedMutation(requestId, owner, state -> {
            if (executionId == null || executionId.isBlank() || !state.view().engineExecutionId().isBlank()) {
                return withLease(state, leaseExpiresAt);
            }
            DynamicRunControlView view = state.view();
            return changed(state, view.status(), view.reasonCode(), executionId, view.startedAt(),
                    view.cancelRequestedAt(), view.terminalAt(), view.terminationConfirmed(),
                    view.sideEffectsMayBeInFlight(), leaseExpiresAt, state.recoveryDisposition());
        });
    }

    @Override
    public Optional<State> requestOwnerStop(String requestId, Owner owner, String status, String reasonCode,
                                            Instant now, Instant leaseExpiresAt) {
        return ownedMutation(requestId, owner, state -> {
            if (InMemoryDynamicRunControlRepository.terminal(state.view())
                    || InMemoryDynamicRunControlRepository.stopRequested(state.view())) {
                return withLease(state, leaseExpiresAt);
            }
            DynamicRunControlView view = state.view();
            return changed(state, status, reasonCode, view.engineExecutionId(), view.startedAt(), now, null,
                    false, true, leaseExpiresAt, "NONE");
        });
    }

    @Override
    public CommandResult requestCallerCancel(DynamicRunControlCommand command, Instant now) {
        CommandResult result = transactions.execute(transaction -> {
            State loaded = load(command.requestId(), true).orElse(null);
            if (loaded == null) {
                return new CommandResult(false, "RG.RUN_CONTROL.NOT_FOUND", "Controlled run was not found.", null);
            }
            State current = expire(loaded, now);
            if (!constantDigest(current.fenceDigest(), command.fencingToken())) {
                return new CommandResult(false, "RG.RUN_CONTROL.FENCE_MISMATCH",
                        "Control command fencing token does not match the run intent.", current);
            }
            if (command.expectedRevision() > 0 && command.expectedRevision() != current.view().revision()) {
                return new CommandResult(false, "RG.RUN_CONTROL.REVISION_CONFLICT",
                        "Control command expectedRevision is stale.", current);
            }
            if (InMemoryDynamicRunControlRepository.terminal(current.view())
                    || InMemoryDynamicRunControlRepository.stopRequested(current.view())) {
                return new CommandResult(false, "RG.RUN_CONTROL.ALREADY_TERMINAL",
                        "Controlled run has already stopped accepting cancellation commands.", current);
            }
            DynamicRunControlView view = current.view();
            State changed = changed(current, "CANCEL_REQUESTED", "USER_CANCEL_REQUESTED",
                    view.engineExecutionId(), view.startedAt(), now, null, false, true,
                    current.leaseExpiresAt(), current.recoveryDisposition());
            persist(changed, now);
            return new CommandResult(true, "RG.RUN_CONTROL.CANCEL_ACCEPTED", "", changed);
        });
        return result == null
                ? new CommandResult(false, "RG.RUN_CONTROL.REPOSITORY_UNAVAILABLE",
                        "Run control transaction did not return a result.", null)
                : result;
    }

    @Override
    public Optional<State> markUnconfirmed(String requestId, Owner owner, String reasonCode, Instant now) {
        return ownedMutation(requestId, owner, state -> {
            DynamicRunControlView view = state.view();
            return changed(state, "TERMINATION_UNCONFIRMED", reasonCode, view.engineExecutionId(),
                    view.startedAt(), view.cancelRequestedAt(), now, false, true, now, "QUARANTINE");
        });
    }

    @Override
    public Optional<State> finish(String requestId, Owner owner, String status, String reasonCode, Instant now) {
        return ownedMutation(requestId, owner, state -> {
            DynamicRunControlView view = state.view();
            return changed(state, status, reasonCode, view.engineExecutionId(), view.startedAt(),
                    view.cancelRequestedAt(), now, true, false, now, "COMPLETED");
        });
    }

    @Override
    public Optional<State> renew(String requestId, Owner owner, Instant leaseExpiresAt) {
        return ownedMutation(requestId, owner, state -> withLease(state, leaseExpiresAt));
    }

    @Override
    public void purgeTerminalBefore(Instant cutoff) {
        jdbc.update("DELETE FROM dynamic_run_controls WHERE termination_confirmed = TRUE "
                        + "AND terminal_at IS NOT NULL AND terminal_at < ?",
                text(cutoff));
    }

    private Optional<State> ownedMutation(String requestId, Owner owner, Function<State, State> mutation) {
        return inTransaction(requestId, state -> {
            State current = expire(state, Instant.now());
            if (!current.owner().equals(owner) || "ABANDONED".equals(current.recoveryDisposition())) {
                return current;
            }
            State changed = mutation.apply(current);
            if (!changed.equals(current)) {
                persist(changed, Instant.now());
            }
            return changed;
        }).filter(state -> state.owner().equals(owner) && !"ABANDONED".equals(state.recoveryDisposition()));
    }

    private Optional<State> inTransaction(String requestId, Function<State, State> operation) {
        return transactions.execute(status -> {
            Optional<State> loaded = load(requestId, true);
            return loaded.map(operation);
        });
    }

    private State expire(State state, Instant now) {
        if (state == null || now == null || InMemoryDynamicRunControlRepository.terminal(state.view())
                || state.leaseExpiresAt() == null || now.isBefore(state.leaseExpiresAt())) {
            return state;
        }
        DynamicRunControlView view = state.view();
        State expired = changed(state, "TERMINATION_UNCONFIRMED", "OWNER_LEASE_EXPIRED",
                view.engineExecutionId(), view.startedAt(), view.cancelRequestedAt(), now,
                false, true, now, "ABANDONED");
        persist(expired, now);
        return expired;
    }

    private State withLease(State state, Instant lease) {
        return new State(state.view(), state.fenceDigest(), state.owner(), lease,
                state.cancellationGraceMs(), state.recoveryDisposition());
    }

    private State changed(State state, String status, String reason, String executionId, Instant startedAt,
                          Instant cancelAt, Instant terminalAt, boolean confirmed, boolean sideEffects,
                          Instant lease, String recovery) {
        DynamicRunControlView old = state.view();
        DynamicRunControlView view = new DynamicRunControlView("", old.requestId(), executionId, status, reason,
                old.revision() + 1, old.deadlineAt(), startedAt, cancelAt, terminalAt, confirmed, sideEffects);
        return new State(view, state.fenceDigest(), state.owner(), lease, state.cancellationGraceMs(), recovery);
    }

    private Optional<State> load(String requestId, boolean lock) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        List<State> rows = jdbc.query(lock ? SELECT_FOR_UPDATE : SELECT,
                (rs, rowNum) -> map(rs), requestId.trim());
        return rows.stream().findFirst();
    }

    private State map(ResultSet rs) throws SQLException {
        DynamicRunControlView view = new DynamicRunControlView("", rs.getString("request_id"),
                rs.getString("engine_execution_id"), rs.getString("status"), rs.getString("reason_code"),
                rs.getLong("revision"), instant(rs.getString("deadline_at")), instant(rs.getString("started_at")),
                instant(rs.getString("cancel_requested_at")), instant(rs.getString("terminal_at")),
                rs.getBoolean("termination_confirmed"), rs.getBoolean("side_effects_in_flight"));
        return new State(view, rs.getString("fence_digest"),
                new Owner(rs.getString("owner_id"), rs.getLong("owner_epoch")),
                instant(rs.getString("lease_expires_at")), rs.getLong("cancellation_grace_ms"),
                rs.getString("recovery_disposition"));
    }

    private void persist(State state, Instant updatedAt) {
        DynamicRunControlView view = state.view();
        jdbc.update(UPDATE, state.owner().id(), state.owner().epoch(), text(state.leaseExpiresAt()),
                state.cancellationGraceMs(), view.engineExecutionId(), view.status(), view.reasonCode(),
                view.revision(), text(view.deadlineAt()), text(view.startedAt()), text(view.cancelRequestedAt()),
                text(view.terminalAt()), view.terminationConfirmed(), view.sideEffectsMayBeInFlight(),
                state.recoveryDisposition(), text(updatedAt), view.requestId());
    }

    private static Object[] insertArgs(State state, Instant updatedAt) {
        DynamicRunControlView view = state.view();
        return new Object[]{view.requestId(), state.fenceDigest(), state.owner().id(), state.owner().epoch(),
                text(state.leaseExpiresAt()), state.cancellationGraceMs(), view.engineExecutionId(), view.status(),
                view.reasonCode(), view.revision(), text(view.deadlineAt()), text(view.startedAt()),
                text(view.cancelRequestedAt()), text(view.terminalAt()), view.terminationConfirmed(),
                view.sideEffectsMayBeInFlight(), state.recoveryDisposition(), text(updatedAt)};
    }

    private static boolean constantDigest(String expectedDigest, String token) {
        return java.security.MessageDigest.isEqual(
                (expectedDigest == null ? "" : expectedDigest).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                InMemoryDynamicRunControlRepository.digest(token)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String text(Instant value) {
        return value == null ? null : value.toString();
    }

    private static Instant instant(String value) {
        return value == null || value.isBlank() ? null : Instant.parse(value);
    }
}
