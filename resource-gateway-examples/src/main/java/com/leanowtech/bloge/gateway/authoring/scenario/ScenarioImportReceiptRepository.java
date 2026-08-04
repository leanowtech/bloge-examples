package com.leanowtech.bloge.gateway.authoring.scenario;

import java.util.Optional;

/** Durable idempotency boundary for payload-free Scenario import results. */
public interface ScenarioImportReceiptRepository {

    /** Finds one exact plan result in the complete enterprise scope. */
    Optional<ScenarioImportMaterializationResult> find(
            ScenarioDraftSet.EnterpriseScope scope,
            String planFingerprint);

    /** Stores once and returns the winning result for concurrent identical requests. */
    ScenarioImportMaterializationResult saveIfAbsent(
            ScenarioDraftSet.EnterpriseScope scope,
            String planFingerprint,
            ScenarioImportMaterializationResult result);
}
