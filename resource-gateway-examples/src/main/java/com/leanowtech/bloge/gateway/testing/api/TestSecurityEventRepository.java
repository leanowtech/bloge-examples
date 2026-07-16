package com.leanowtech.bloge.gateway.testing.api;

import java.util.List;

/** Append-only security-event sink for the caller-driven test surface. */
public interface TestSecurityEventRepository {
    TestSecurityEvent append(TestSecurityEvent event);

    /**
     * Binds an event write to a future test-runtime transaction.
     *
     * <p>The returned mutation must use only the JDBC facade supplied at application time. This is
     * required when an authorized security decision and its durable control mutation must either
     * both commit or both roll back.</p>
     *
     * @param event complete payload-free security event
     * @return transaction-participating append mutation
     */
    default TestRuntimeTransactionMutation boundAppend(TestSecurityEvent event) {
        throw new UnsupportedOperationException(
                "Security-event repository does not support transaction-bound appends");
    }

    List<TestSecurityEvent> recent(int limit);
}
