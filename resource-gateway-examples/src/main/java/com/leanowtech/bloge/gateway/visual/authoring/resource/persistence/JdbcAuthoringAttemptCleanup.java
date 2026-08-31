package com.leanowtech.bloge.gateway.visual.authoring.resource.persistence;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Objects;

/**
 * Projection-specific cleanup delegated by the generic JDBC claim
 * coordinator.
 *
 * <p>The claim store invokes this helper while its journal transaction still
 * owns the prior attempt lock. It removes only an abandoned nested Connection
 * stage when no pending secret, outcome, or binding row can still require
 * that historical attempt for recovery. Keeping this operation in a focused
 * collaborator prevents the generic claim seam from owning Connection
 * projection decisions while preserving the claim/takeover transaction.</p>
 */
final class JdbcAuthoringAttemptCleanup {
    private final JdbcTemplate jdbc;

    JdbcAuthoringAttemptCleanup(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Deletes a providerless nested stage only when its attempt has no durable recovery provenance. */
    void deleteAbandonedNestedConnectionStage(String commandId, int attemptNo, String attemptToken) {
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_leases "
                        + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", Long.class,
                commandId, attemptNo, attemptToken);
        Long outcomes = jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_pending_secret_outcomes "
                        + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", Long.class,
                commandId, attemptNo, attemptToken);
        Long bindings = jdbc.queryForObject("SELECT COUNT(*) FROM rg_api_connection_secret_bindings "
                        + "WHERE command_id=? AND attempt_no=? AND attempt_token=?", Long.class,
                commandId, attemptNo, attemptToken);
        if (positive(pending) || positive(outcomes) || positive(bindings)) return;
        jdbc.update("DELETE FROM rg_api_connection_heads WHERE command_id=? AND attempt_no=? AND attempt_token=?",
                commandId, attemptNo, attemptToken);
        jdbc.update("DELETE FROM rg_api_connection_revisions WHERE command_id=? AND attempt_no=? "
                        + "AND attempt_token=? AND state IN ('STAGED', 'COMMITTED')", commandId, attemptNo,
                attemptToken);
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }
}
