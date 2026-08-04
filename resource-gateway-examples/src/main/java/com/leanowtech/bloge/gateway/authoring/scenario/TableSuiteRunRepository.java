package com.leanowtech.bloge.gateway.authoring.scenario;

import java.util.Optional;

/** Scope-isolated durable store for payload-free Scenario table batches. */
public interface TableSuiteRunRepository {

    Optional<TableSuiteRunBatch> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String batchId);

    Optional<TableSuiteRunBatch> findByRequest(
            ScenarioDraftSet.EnterpriseScope scope,
            String requestId);

    TableSuiteRunBatch create(TableSuiteRunBatch batch);

    boolean replace(TableSuiteRunBatch batch, long expectedRevision);
}
