package com.leanowtech.bloge.gateway.example;

import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlRecoveryPort;
import com.leanowtech.bloge.gateway.visual.runtime.VisualRunControlView;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Adapts gateway run-control persistence to the visual runtime's recovery port. */
@Component
public final class DynamicRunControlRecoveryAdapter implements VisualRunControlRecoveryPort {
    private static final String SELECT_CANDIDATES = """
            SELECT r.request_id
            FROM visual_run_recovery_reservations r
            LEFT JOIN dynamic_run_controls c ON c.request_id = r.request_id
            WHERE r.state = 'PENDING'
              AND (
                (c.request_id IS NULL AND r.reserved_at <= ?)
                OR c.recovery_disposition = 'ABANDONED'
                OR (c.recovery_disposition = 'COMPLETED' AND c.terminal_at IS NOT NULL AND c.terminal_at <= ?)
                OR (c.termination_confirmed = FALSE AND c.lease_expires_at IS NOT NULL AND c.lease_expires_at <= ?)
              )
            ORDER BY r.reserved_at ASC, r.request_id ASC
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;
    private final DynamicRunControlRepository repository;

    public DynamicRunControlRecoveryAdapter(JdbcTemplate jdbc, DynamicRunControlRepository repository) {
        this.jdbc = jdbc;
        this.repository = repository;
    }

    @Override
    public List<String> recoveryCandidates(Instant missingControlCutoff,
                                           Instant terminalControlCutoff,
                                           Instant leaseExpiryCutoff,
                                           int limit) {
        return jdbc.query(SELECT_CANDIDATES, (rs, rowNum) -> rs.getString("request_id"),
                missingControlCutoff.toString(), terminalControlCutoff.toString(),
                leaseExpiryCutoff.toString(), limit);
    }

    @Override
    public Optional<State> find(String requestId, Instant now) {
        return repository.find(requestId, now).map(state -> new State(visualControl(state.view()),
                state.recoveryDisposition()));
    }

    private static VisualRunControlView visualControl(DynamicRunControlView source) {
        return new VisualRunControlView("", source.requestId(), source.engineExecutionId(), source.status(),
                source.reasonCode(), source.revision(), source.deadlineAt(), source.startedAt(),
                source.cancelRequestedAt(), source.terminalAt(), source.terminationConfirmed(),
                source.sideEffectsMayBeInFlight());
    }
}
