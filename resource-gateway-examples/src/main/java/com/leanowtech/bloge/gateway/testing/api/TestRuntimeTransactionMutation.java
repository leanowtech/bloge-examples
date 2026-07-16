package com.leanowtech.bloge.gateway.testing.api;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * One local write that must participate in the isolated test-runtime database transaction.
 *
 * <p>Implementations may use only the supplied transaction-bound JDBC facade. Network calls,
 * another datasource, or deferred work would escape the caller's atomic commit boundary.</p>
 */
@FunctionalInterface
public interface TestRuntimeTransactionMutation {

    /**
     * Applies the local write through the transaction-bound test-runtime connection.
     *
     * @param jdbc JDBC facade enlisted in the caller's local transaction
     */
    void apply(JdbcTemplate jdbc);

    /**
     * Returns the neutral mutation used by callers that require no companion write.
     *
     * @return a transaction-safe no-op
     */
    static TestRuntimeTransactionMutation noop() {
        return ignored -> { };
    }
}
