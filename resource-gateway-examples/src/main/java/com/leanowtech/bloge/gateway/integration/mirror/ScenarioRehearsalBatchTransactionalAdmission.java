package com.leanowtech.bloge.gateway.integration.mirror;

/**
 * Internal transaction-participating admission boundary for a precompiled Scenario batch.
 *
 * <p>The public batch repository starts an isolated transaction for ordinary API calls. A
 * reviewed remediation must instead admit its successor in the same transaction as the immutable
 * remediation receipt. Implementations of this boundary therefore join an already active local
 * transaction and must not publish a result independently.</p>
 */
public interface ScenarioRehearsalBatchTransactionalAdmission {
    /**
     * Admits or exactly recovers one batch inside the caller's active database transaction.
     *
     * @param submission complete exact-plan submission
     * @param policy server-owned queue policy
     * @return durable admission result
     * @throws IllegalStateException when no transaction is active
     */
    ScenarioRehearsalBatchRepository.SubmissionResult
    submitInCurrentTransaction(
            ScenarioRehearsalBatchRepository.Submission submission,
            ScenarioRehearsalBatchPolicy policy);
}
